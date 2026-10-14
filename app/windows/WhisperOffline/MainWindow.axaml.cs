using System.Diagnostics;
using System.IO;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Platform;
using Avalonia.Platform.Storage;
using Avalonia.Threading;

namespace WhisperOffline;

public partial class MainWindow : Window
{
    private readonly AudioRecorder recorder = new();
    private string language = "auto";
    private bool busy = false;

    private List<ModelInfo>? manifest;
    private CancellationTokenSource? downloadCts;
    private ModelInfo? downloading;
    private bool pickerOpen = false;

    // Diktat-Modus (globaler Hotkey)
    private bool dictating = false;
    private IntPtr dictationTarget = IntPtr.Zero;
    private bool allowClose = false;
    private TrayIcon? tray;

    private readonly AppSettings settings = AppSettings.Load();
    private bool capturingHotkey = false;
    private bool suppressSettingsEvents = true;

    public MainWindow()
    {
        InitializeComponent();

        settings.SeedVocabulary();
        language = settings.Language;
        foreach (var item in LanguageBox.Items.OfType<ComboBoxItem>())
            if (item.Tag as string == language) LanguageBox.SelectedItem = item;

        LanguageBox.SelectionChanged += (_, _) =>
        {
            language = (LanguageBox.SelectedItem as ComboBoxItem)?.Tag as string ?? "de";
            if (suppressSettingsEvents) return;
            settings.Language = language;
            settings.Save();
        };

        AutostartBox.IsChecked = settings.Autostart;
        StartMinimizedBox.IsChecked = settings.StartMinimized;
        TranslateBox.IsChecked = settings.Translate;
        HistoryStore.Enabled = settings.HistoryEnabled;
        recorder.Settings = settings;
        UpdateHotkeyLabel();
        suppressSettingsEvents = false;

        // Gespeichertes Modell aktivieren, falls vorhanden; sonst Auswahl öffnen.
        // Beim allerersten Start kommt zuerst die Einführung.
        string? stored = string.IsNullOrEmpty(settings.Model) ? null : settings.Model;

        var local = ModelRegistry.LocalModelFiles();
        if (stored != null && local.Contains(stored))
        {
            WhisperCli.SelectedModel = Path.Combine(WhisperCli.ModelsDir, stored);
            UpdateModelLabel();
        }
        else if (local.Count > 0)
        {
            WhisperCli.SelectedModel = Path.Combine(WhisperCli.ModelsDir, local[0]);
            UpdateModelLabel();
        }

        if (!settings.OnboardingDone)
        {
            ShowOnboarding();
        }
        else if (local.Count == 0)
        {
            _ = OpenPickerAsync();
        }

        // In Tray minimieren statt beenden; Hotkey aktivieren
        Closing += (_, e) =>
        {
            if (!allowClose) { e.Cancel = true; Hide(); }
        };
        SetupTray();
        ApplyHotkeys();
        HotkeyHook.Start((action, edge) => Dispatcher.UIThread.Post(() => OnHotkey(action, edge)));
        Opened += (_, _) => OfferMeetingRecovery();

        // Modell einmal in die Engine laden, damit der erste Hotkey-Druck nicht
        // auf 180+ MB von der Platte wartet. Läuft im Hintergrund weiter.
        Opened += (_, _) => { _ = WarmUpAsync(); _ = CheckUpdateAsync(silent: true); };
        if (Program.StartHidden || settings.StartMinimized)
        {
            // Ohne Fenster starten: nur Infobereich, wartet auf den Hotkey.
            Opened += (_, _) => Hide();
            _ = WarmUpAsync();
        }
    }

    /// Lädt das Modell in die In-Process-Engine. Der Prozess belegt danach den
    /// Modellspeicher dauerhaft, spart aber bei jedem Diktat das Neuladen.
    private async Task WarmUpAsync()
    {
        var model = WhisperCli.SelectedModel;
        if (!WhisperNative.Available || !File.Exists(model)) { UpdateEngineLabel(); return; }
        var ok = await Task.Run(() => WhisperNative.EnsureLoaded(model));
        Dispatcher.UIThread.Post(() =>
        {
            if (ok) engineWarm = true;
            UpdateEngineLabel();
        });
    }

    private bool engineWarm = false;

    private void UpdateEngineLabel()
    {
        EngineLabel.Text = engineWarm
            ? "Bereit für Diktate und Transkription"
            : WhisperNative.Available
                ? "Engine wird im Hintergrund geladen…"
                : "whisper_shim.dll nicht gefunden — Rückfall auf whisper-cli (langsamer)";

        // Parakeet: mehrsprachig, kein festes Encoder-Fenster — die
        // Sprachauswahl hätte dort keine Wirkung.
        var parakeet = engineWarm && WhisperNative.IsParakeet;
        LanguageBox.IsEnabled = !parakeet;
        if (parakeet) EngineLabel.Text += " · mehrsprachig";
    }

    /// Transkribiert Mikrofon-Samples. Bevorzugt die geladene Engine; nur wenn
    /// die DLL fehlt oder scheitert, wird whisper-cli als Unterprozess benutzt.
    /// Eigene Wörter (Prompt + Korrektur) und Ersetzungen wirken auf beiden Wegen.
    private string TranscribeSamples(float[] samples) =>
        Transcriber.Transcribe(samples, settings, Lang, settings.Translate);

    private static bool IsParakeetModel =>
        Path.GetFileName(WhisperCli.SelectedModel).Contains("parakeet", StringComparison.OrdinalIgnoreCase);

    /// Transkribiert und zeigt dabei Fortschritt und Abbrechen-Knopf. Der
    /// Balken kombiniert die Engine-Meldung mit einer Zeitschätzung, die pro
    /// Modell auf diesem Rechner gelernt wird.
    private async Task<string> TranscribeWithProgress(float[] samples, LiveTranscriber? live = null)
    {
        var seconds = samples.Length / 16000.0;
        var model = Path.GetFileName(WhisperCli.SelectedModel);
        var parakeet = IsParakeetModel;
        var expected = TranscriptionEstimate.ExpectedMs(settings, model, seconds, parakeet);
        var clock = Stopwatch.StartNew();

        ShowWork(cancellable: true);
        var timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(100) };
        timer.Tick += (_, _) =>
        {
            var elapsed = clock.ElapsedMilliseconds;
            var fraction = TranscriptionEstimate.Fraction(WhisperNative.Progress, elapsed, expected);
            WorkProgress.IsIndeterminate = fraction == null;
            if (fraction != null) WorkProgress.Value = fraction.Value;
            if (WorkCancelButton.IsEnabled)
                WorkDetail.Text = expected == null ? $"läuft seit {elapsed / 1000} s"
                    : expected - elapsed > 1500 ? $"noch ca. {(expected - elapsed + 999) / 1000} s"
                    : "gleich fertig …";
        };
        timer.Start();
        try
        {
            var text = live != null
                ? await live.FinishAsync(samples)
                : await Task.Run(() => TranscribeSamples(samples));
            if (live == null && text.Length > 0 && !WhisperNative.WasCancelled)
                TranscriptionEstimate.Record(settings, model, seconds, parakeet, clock.ElapsedMilliseconds);
            return text;
        }
        finally
        {
            timer.Stop();
            HideWork();
        }
    }

    private void ShowWork(bool cancellable)
    {
        WorkProgress.IsIndeterminate = true;
        WorkProgress.Value = 0;
        WorkDetail.Text = "";
        WorkCancelButton.IsVisible = cancellable;
        WorkCancelButton.IsEnabled = true;
        WorkPanel.IsVisible = true;
    }

    private void HideWork() => WorkPanel.IsVisible = false;

    private void OnWorkCancel(object? sender, RoutedEventArgs e)
    {
        WhisperNative.Cancel();
        enhanceCts?.Cancel();
        WorkCancelButton.IsEnabled = false;
        WorkDetail.Text = "Wird abgebrochen…";
    }

    /// Statuszeile nach einer Transkription.
    private static string ResultStatus(string text, double seconds) =>
        WhisperNative.WasCancelled ? "Transkription abgebrochen." :
        text.Length == 0 ? "Keine Sprache erkannt." :
        $"Fertig ({seconds:F1} s Audio transkribiert).";

    // ---------- Hotkey und Hintergrundbetrieb ----------

    private void UpdateHotkeyLabel()
    {
        HotkeyButton.Content = capturingHotkey && capturingTarget == HotkeyAction.Dictate
            ? "Kombination drücken…"
            : HotkeySpec.Describe(settings.HotkeyModifiers, settings.HotkeyVk);
        HotkeyHint.Text = HotkeyHook.LastBindOk
            ? (settings.PushToTalk ? "Gedrückt halten zum Sprechen" : "Überall diktieren per Hotkey")
            : "Kombination ist belegt — bitte eine andere wählen";
        var more = new List<string>();
        if (settings.SingleKeyVk != 0) more.Add(HotkeySpec.DescribeSingle(settings.SingleKeyVk) + " allein: Diktat");
        if (settings.EnhanceHotkeyVk != 0)
            more.Add(HotkeySpec.Describe(settings.EnhanceHotkeyModifiers, settings.EnhanceHotkeyVk) + ": Diktat + Enhance" +
                     (HotkeyHook.BindOk(HotkeyAction.DictateEnhance) ? "" : " (belegt!)"));
        if (settings.TransformHotkeyVk != 0)
            more.Add(HotkeySpec.Describe(settings.TransformHotkeyModifiers, settings.TransformHotkeyVk) + ": Auswahl bearbeiten" +
                     (HotkeyHook.BindOk(HotkeyAction.Transform) ? "" : " (belegt!)"));
        HotkeyMoreLabel.Text = string.Join("   ·   ", more);
        HotkeyMoreLabel.IsVisible = more.Count > 0;
        if (tray != null)
            tray.ToolTipText = "Scheisssewasser's Whisper — " +
                HotkeySpec.Describe(settings.HotkeyModifiers, settings.HotkeyVk) + ": Diktat";
        settingsHotkeyRefresh?.Invoke();
    }

    /// Wird von den Einstellungen gesetzt, damit deren Hotkey-Knöpfe mitziehen.
    private Action? settingsHotkeyRefresh;
    private HotkeyAction capturingTarget = HotkeyAction.Dictate;

    private void OnHotkeyCapture(object? sender, RoutedEventArgs e) => BeginHotkeyCapture(HotkeyAction.Dictate);

    private void BeginHotkeyCapture(HotkeyAction target)
    {
        capturingHotkey = true;
        capturingTarget = target;
        UpdateHotkeyLabel();
        Focus();
    }

    protected override void OnKeyDown(KeyEventArgs e)
    {
        if (!capturingHotkey) { base.OnKeyDown(e); return; }

        // Modifikatoren allein sind kein Kurzbefehl — weiter warten.
        if (HotkeySpec.IsModifierKey(e.Key)) { e.Handled = true; return; }

        if (e.Key == Key.Escape)
        {
            capturingHotkey = false;
            UpdateHotkeyLabel();
            e.Handled = true;
            return;
        }

        // Entf/Rücktaste: Zusatz-Hotkey abschalten
        if (capturingTarget != HotkeyAction.Dictate && e.Key is Key.Delete or Key.Back && e.KeyModifiers == KeyModifiers.None)
        {
            SetHotkey(capturingTarget, 0, 0);
            capturingHotkey = false;
            UpdateHotkeyLabel();
            e.Handled = true;
            return;
        }

        var vk = HotkeySpec.ToVk(e.Key);
        var mods = HotkeySpec.ToModifiers(e.KeyModifiers);
        if (vk == 0 || mods == 0)
        {
            // Ohne Modifikator würde der Hotkey die Taste systemweit schlucken.
            HotkeyHint.Text = "Bitte mit Strg, Alt, Umschalt oder Windows kombinieren";
            e.Handled = true;
            return;
        }

        capturingHotkey = false;
        SetHotkey(capturingTarget, mods, vk);
        // RegisterHotKey läuft asynchron auf dem Hotkey-Thread; kurz warten,
        // damit LastBindOk den neuen Stand zeigt.
        Dispatcher.UIThread.Post(async () =>
        {
            await Task.Delay(120);
            UpdateHotkeyLabel();
        });
        UpdateHotkeyLabel();
        e.Handled = true;
    }

    private void OnAutostartChanged(object? sender, RoutedEventArgs e)
    {
        if (suppressSettingsEvents) return;
        settings.Autostart = AutostartBox.IsChecked == true;
        settings.Save();
        AppSettings.ApplyAutostart(settings.Autostart);
    }

    private void OnStartMinimizedChanged(object? sender, RoutedEventArgs e)
    {
        if (suppressSettingsEvents) return;
        settings.StartMinimized = StartMinimizedBox.IsChecked == true;
        settings.Save();
    }

    private void SetupTray()
    {
        var menu = new NativeMenu();
        var open = new NativeMenuItem { Header = "Öffnen" };
        open.Click += (_, _) => { Show(); Activate(); };
        var quit = new NativeMenuItem { Header = "Beenden" };
        quit.Click += (_, _) =>
        {
            allowClose = true;
            tray?.Dispose();
            Close();
            (Application.Current?.ApplicationLifetime as IClassicDesktopStyleApplicationLifetime)?.Shutdown();
        };
        menu.Items.Add(open);
        menu.Items.Add(new NativeMenuItemSeparator());
        menu.Items.Add(quit);

        tray = new TrayIcon
        {
            Icon = AppIcon.Get(),
            Menu = menu,
            ToolTipText = "Scheisssewasser's Whisper — Strg+Alt+Leertaste: Diktat",
        };
        tray.Clicked += (_, _) => { Show(); Activate(); };
        TrayIcon.SetIcons(Application.Current, new TrayIcons { tray });
    }

    /// Lesbarer Modellname für den Modell-Chip — wie modelDisplayName auf Android
    /// ("ggml-small-q5_1.bin" → "Whisper small", "ggml-parakeet-tdt-0.6b-v3-…" → "Parakeet v3").
    private static string FormatModelDisplayName(string? file, List<ModelInfo>? manifestList)
    {
        if (string.IsNullOrEmpty(file)) return "Modell wählen…";
        var hit = manifestList?.FirstOrDefault(m => m.File == file);
        if (hit != null) return hit.Label.Split(" — ")[0];
        var baseName = Path.GetFileNameWithoutExtension(file);
        if (baseName.StartsWith("ggml-")) baseName = baseName["ggml-".Length..];
        if (baseName.StartsWith("parakeet"))
        {
            var m = System.Text.RegularExpressions.Regex.Match(baseName, @"-(v\d+)");
            return m.Success ? $"Parakeet {m.Groups[1].Value}" : "Parakeet";
        }
        // Quantisierung (q5_1, q8_0 …) ist für die Anzeige Rauschen
        baseName = System.Text.RegularExpressions.Regex.Replace(baseName, @"-q\d+_\d+$", "");
        return "Whisper " + baseName;
    }

    private void UpdateModelLabel() =>
        ModelLabel.Text = File.Exists(WhisperCli.SelectedModel)
            ? FormatModelDisplayName(Path.GetFileName(WhisperCli.SelectedModel), manifest)
            : "Modell wählen…";

    private string Lang => language == "auto" ? "" : language;

    // ---------- Modell-Auswahl ----------

    private async Task OpenPickerAsync()
    {
        pickerOpen = true;
        PickerPanel.IsVisible = true;
        MainPanel.IsVisible = false;
        PickerCloseButton.IsVisible = File.Exists(WhisperCli.SelectedModel);
        PickerList.Children.Clear();
        PickerError.Text = "";
        PickerList.Children.Add(Hint("Frage Server nach verfügbaren Modellen…"));
        try
        {
            manifest = await ModelRegistry.FetchManifest();
            UpdateModelLabel();
        }
        catch (Exception ex)
        {
            PickerList.Children.Clear();
            PickerError.Text = "Server nicht erreichbar: " + ex.Message;
            var retry = new Button { Content = "Erneut versuchen", Classes = { "chip" }, HorizontalAlignment = HorizontalAlignment.Left };
            retry.Click += async (_, _) => await OpenPickerAsync();
            PickerList.Children.Add(retry);
            return;
        }
        BuildPickerList();
    }

    private void BuildPickerList()
    {
        PickerList.Children.Clear();
        if (manifest == null) return;
        var local = ModelRegistry.LocalModelFiles();
        var current = Path.GetFileName(WhisperCli.SelectedModel);

        foreach (var info in manifest)
        {
            var isLocal = local.Contains(info.File);
            var isCurrent = current == info.File;
            var cardBorder = Card(BuildModelCard(info, isLocal, isCurrent));
            if (isCurrent) cardBorder.BorderBrush = PrimaryBrush;
            PickerList.Children.Add(cardBorder);
        }

        // Lokale Modelle ohne Manifest-Eintrag
        var extra = local.Where(f => manifest.All(m => m.File != f)).ToList();
        if (extra.Count > 0) PickerList.Children.Add(Section("Weitere lokale Modelle"));
        foreach (var f in extra)
        {
            // Das aktive Modell nicht löschen — die Engine hält die Datei offen
            var act = new Button { Content = "Aktivieren", Classes = { "chip" }, IsVisible = f != current };
            act.Click += (_, _) => Activate(f);
            var del = new Button { Content = "✕", Classes = { "chip" }, Foreground = ErrorBrush, IsVisible = f != current };
            del.Click += async (_, _) =>
            {
                if (!await Confirm("Modell löschen?", $"„{f}“ wird vom Rechner entfernt.")) return;
                try { File.Delete(Path.Combine(WhisperCli.ModelsDir, f)); } catch { }
                BuildPickerList();
            };
            PickerList.Children.Add(Card(new DockPanel
            {
                Children =
                {
                    new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6, Children = { act, del } }
                        .With(s => DockPanel.SetDock(s, Dock.Right)),
                    new TextBlock { Text = f, VerticalAlignment = VerticalAlignment.Center, FontWeight = FontWeight.Medium },
                },
            }));
        }
    }

    private StackPanel BuildModelCard(ModelInfo info, bool isLocal, bool isCurrent)
    {
        var card = new StackPanel { Spacing = 6 };

        card.Children.Add(new DockPanel
        {
            Children =
            {
                new TextBlock
                {
                    Text = info.SizeText, Foreground = SubtleBrush, FontSize = 12,
                    VerticalAlignment = VerticalAlignment.Center,
                }.With(s => DockPanel.SetDock(s, Dock.Right)),
                new TextBlock
                {
                    Text = info.Label, FontWeight = FontWeight.Bold, FontSize = 15,
                    VerticalAlignment = VerticalAlignment.Center,
                },
            },
        });
        card.Children.Add(new TextBlock
        {
            Text = info.Tagline, Foreground = PrimaryBrush, FontSize = 12,
        });
        foreach (var p in info.Pros)
            card.Children.Add(new TextBlock { Text = "✓  " + p, FontSize = 12 });
        foreach (var c in info.Cons)
            card.Children.Add(new TextBlock
            {
                Text = "–  " + c, FontSize = 12, Foreground = SubtleBrush,
            });

        var actionsRow = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Margin = new Thickness(0, 6, 0, 0) };
        if (isCurrent)
        {
            actionsRow.Children.Add(new TextBlock
            {
                Text = "✓ Aktiv", FontWeight = FontWeight.Bold, Foreground = TertiaryBrush,
                VerticalAlignment = VerticalAlignment.Center,
            });
            card.Children.Add(actionsRow);
            return card;
        }

        if (isLocal)
        {
            var act = new Button { Content = "Aktivieren", Classes = { "primaryAction" } };
            act.Click += (_, _) => Activate(info.File);
            var del = new Button { Content = "Löschen", Classes = { "chip" }, Foreground = ErrorBrush };
            del.Click += async (_, _) =>
            {
                if (!await Confirm("Modell löschen?", $"„{info.Label}“ wird vom Rechner entfernt und kann jederzeit neu geladen werden.")) return;
                try { File.Delete(Path.Combine(WhisperCli.ModelsDir, info.File)); } catch { }
                BuildPickerList();
            };
            actionsRow.Children.Add(act);
            actionsRow.Children.Add(del);
        }
        else
        {
            var dl = new Button
            {
                Content = $"⬇  Herunterladen ({info.SizeText})",
                Classes = { "chip" },
                IsEnabled = downloading == null,
            };
            dl.Click += async (_, _) => await DownloadAsync(info);
            actionsRow.Children.Add(dl);
        }
        card.Children.Add(actionsRow);
        return card;
    }

    private async Task DownloadAsync(ModelInfo info)
    {
        downloading = info;
        PickerError.Text = "";
        BuildPickerList();
        // Fortschritt in neu aufgebauter Liste finden: einfachste Variante —
        // Download läuft, Liste zeigt Spinner-Text; bei Ende wird neu gebaut.
        try
        {
            downloadCts = new CancellationTokenSource();
            var lastReport = 0.0;
            var progress = new Progress<double>(v =>
            {
                if (v - lastReport > 0.005)
                {
                    lastReport = v;
                    PickerError.Text = "";
                    SetDownloadStatus(info, v);
                }
            });
            await ModelRegistry.DownloadModel(info, progress, downloadCts.Token);
            Activate(info.File);
        }
        catch (Exception ex)
        {
            PickerError.Text = "Downloadfehler: " + ex.Message;
        }
        finally
        {
            downloading = null;
            downloadCts?.Dispose();
            downloadCts = null;
            BuildPickerList();
        }
    }

    private void SetDownloadStatus(ModelInfo info, double v)
    {
        // Einfache Statuszeile über der Liste statt Kartensuche
        PickerError.Text = "";
        if (PickerList.Children.Count > 0 && PickerList.Children[0] is TextBlock t && t.Tag as string == "dl")
        {
            t.Text = $"Lade {info.Label} … {v * 100:F0} %";
        }
        else
        {
            var status = new TextBlock
            {
                Text = $"Lade {info.Label} … {v * 100:F0} %",
                Tag = "dl",
                FontWeight = FontWeight.Bold,
                Foreground = PrimaryBrush,
            };
            PickerList.Children.Insert(0, status);
        }
    }

    private void Activate(string file)
    {
        WhisperCli.SelectedModel = Path.Combine(WhisperCli.ModelsDir, file);
        try { Directory.CreateDirectory(WhisperCli.ModelsDir); } catch { }
        settings.Model = file;
        settings.Save();
        UpdateModelLabel();
        ClosePicker();
        // Modellwechsel: alte Gewichte freigeben, neue vorladen
        engineWarm = false;
        _ = WarmUpAsync();
    }

    private void ClosePicker()
    {
        pickerOpen = false;
        PickerPanel.IsVisible = false;
        MainPanel.IsVisible = true;
    }

    private async void OnOpenModelPicker(object? sender, RoutedEventArgs e) => await OpenPickerAsync();

    private void OnClosePicker(object? sender, RoutedEventArgs e)
    {
        if (File.Exists(WhisperCli.SelectedModel)) ClosePicker();
    }

    // ---------- In-App-Update (GitHub Releases) ----------

    private GhRelease? pendingUpdate;
    private bool updateBusy = false;

    private async Task CheckUpdateAsync(bool silent)
    {
        if (updateBusy) return;
        var release = await Task.Run(() => UpdateChecker.FetchLatest());
        if (release == null)
        {
            if (!silent) StatusLabel.Text = "GitHub nicht erreichbar.";
            return;
        }
        var current = UpdateChecker.CurrentVersion();
        if (UpdateChecker.IsNewer(current, release.Tag))
        {
            pendingUpdate = release;
            UpdateButton.Content = $"⬆  Update ({release.Tag})";
            UpdateButton.IsVisible = true;
            if (!silent)
                StatusLabel.Text = $"Update verfügbar: {release.Tag} (installiert: {current})";
        }
        else if (!silent)
        {
            StatusLabel.Text = $"Version {current} ist aktuell.";
        }
    }

    private async void OnCheckUpdate(object? sender, RoutedEventArgs e)
    {
        if (pendingUpdate != null) { ShowUpdatePanel(pendingUpdate); return; }
        await CheckUpdateAsync(silent: false);
    }

    /// Update-Panel mit den Release-Notes anzeigen; Installation erst auf Klick.
    private void ShowUpdatePanel(GhRelease release)
    {
        UpdateTitle.Text = $"Update auf {release.Tag}";
        UpdateNotes.Text = release.Body.Trim();
        UpdateInstallButton.IsEnabled = release.ZipUrl != null;
        UpdatePanel.IsVisible = true;
        MainPanel.IsVisible = false;
        ChangelogPanel.IsVisible = false;
    }

    private void OnUpdateCancel(object? sender, RoutedEventArgs e)
    {
        UpdatePanel.IsVisible = false;
        MainPanel.IsVisible = true;
    }

    private async void OnUpdateInstall(object? sender, RoutedEventArgs e)
    {
        if (pendingUpdate == null || updateBusy) return;
        UpdateInstallButton.IsEnabled = false;
        await ApplyUpdateAsync(pendingUpdate);
        UpdateInstallButton.IsEnabled = true;
    }

    // ---------- Einführung beim ersten Start ----------

    private record OnboardingPage(string Title, string? Intro, string[] Bullets, string? Footer);

    private static readonly OnboardingPage[] OnboardingPages =
    {
        new("Willkommen!",
            "Dies ist Scheisssewasser's Whisper — eine private Sprach-Transkriptions-App von Scheisssewasser. Sie wandelt Gesprochenes in Text um, direkt auf deinem Gerät.",
            Array.Empty<string>(),
            "Privat entwickelt — für den persönlichen Einsatz, ohne Firma und ohne kommerzielle Interessen."),
        new("Was kann die App?",
            null,
            new[]
            {
                "🎙  Sprache zu Text: Mikrofonaufnahmen in Sekunden transkribieren",
                "📄  Dateien: Audio- und Videodateien in Text umwandeln",
                "⌨️  Diktat-Modus: per Hotkey (Standard: Strg+Alt+Leertaste) in jedes Textfeld",
                "🕓  Verlauf: alle Transkriptionen bleiben abrufbar",
                "🌐  Sprachen: Deutsch, English oder automatisch",
            }, null),
        new("Deine Vorteile",
            null,
            new[]
            {
                "🔒  100 % offline: Dein Audio verlässt das Gerät nie — keine Cloud, keine Datensammelei",
                "💸  Kostenlos und ohne Konto nutzbar",
                "⚙️  Mehrere Modelle wählbar — maximal genau oder blitzschnell",
                "🔄  Automatische Updates direkt aus der App",
            }, null),
        new("Los geht's!",
            "Im nächsten Schritt wählst du ein Sprachmodell. Es wird einmalig von einem Server von scheisssewasser.xyz bezogen und heruntergeladen — danach läuft alles komplett offline.",
            Array.Empty<string>(),
            "Klicke auf „Los geht's“, um zu beginnen."),
    };

    private int onboardingPage = 0;

    private void ShowOnboarding()
    {
        onboardingPage = 0;
        RenderOnboardingPage();
        OnboardingPanel.IsVisible = true;
        MainPanel.IsVisible = false;
    }

    private void RenderOnboardingPage()
    {
        var p = OnboardingPages[onboardingPage];
        OnboardingPageIndicator.Text = $"Seite {onboardingPage + 1} von {OnboardingPages.Length}";
        OnboardingTitle.Text = p.Title;
        OnboardingIntro.Text = p.Intro ?? "";
        OnboardingIntro.IsVisible = p.Intro != null;
        OnboardingBullets.Children.Clear();
        foreach (var b in p.Bullets)
            OnboardingBullets.Children.Add(new TextBlock { Text = "•  " + b, TextWrapping = TextWrapping.Wrap, FontSize = 14 });
        OnboardingFooter.Text = p.Footer ?? "";
        OnboardingFooter.IsVisible = p.Footer != null;
        OnboardingNextButton.Content = onboardingPage == OnboardingPages.Length - 1 ? "Los geht's" : "Weiter";
    }

    private void OnOnboardingNext(object? sender, RoutedEventArgs e)
    {
        if (onboardingPage < OnboardingPages.Length - 1)
        {
            onboardingPage++;
            RenderOnboardingPage();
            return;
        }
        settings.OnboardingDone = true;
        settings.Save();
        OnboardingPanel.IsVisible = false;
        MainPanel.IsVisible = true;
        if (ModelRegistry.LocalModelFiles().Count == 0)
            _ = OpenPickerAsync();
    }

    // ---------- Changelog (eingebettete CHANGELOG.md) ----------

    private void OnOpenChangelog(object? sender, RoutedEventArgs e)
    {
        ChangelogText.Text = LoadChangelog();
        ChangelogPanel.IsVisible = true;
        MainPanel.IsVisible = false;
        UpdatePanel.IsVisible = false;
    }

    private void OnChangelogClose(object? sender, RoutedEventArgs e)
    {
        ChangelogPanel.IsVisible = false;
        MainPanel.IsVisible = true;
    }

    private static string LoadChangelog()
    {
        try
        {
            var asm = typeof(MainWindow).Assembly;
            var name = asm.GetManifestResourceNames().FirstOrDefault(n => n.EndsWith("CHANGELOG.md"));
            if (name == null) return "Changelog nicht gefunden.";
            using var stream = asm.GetManifestResourceStream(name)!;
            using var reader = new StreamReader(stream);
            return reader.ReadToEnd().Trim();
        }
        catch
        {
            return "Changelog nicht gefunden.";
        }
    }

    private async Task ApplyUpdateAsync(GhRelease release)
    {
        if (release.ZipUrl == null)
        {
            StatusLabel.Text = "Release enthält kein Zip-Paket.";
            return;
        }
        if (release.SigUrl == null)
        {
            // Ohne Signatur lässt sich nicht prüfen, ob das Zip wirklich von uns stammt.
            StatusLabel.Text = "Release ist nicht signiert — Update bitte manuell von GitHub laden.";
            return;
        }
        updateBusy = true;
        UpdateButton.IsEnabled = false;
        try
        {
            StatusLabel.Text = $"Lade {release.Tag} herunter…";
            double last = -1;
            var progress = new Progress<double>(v =>
            {
                if (v - last > 0.04)
                {
                    last = v;
                    StatusLabel.Text = $"Lade {release.Tag} herunter… {v * 100:F0} %";
                }
            });
            var srcDir = await Task.Run(() => SelfUpdater.DownloadAndExtractAsync(release.ZipUrl, release.SigUrl, progress));
            StatusLabel.Text = "Installation wird angewendet — App startet neu…";
            SelfUpdater.LaunchUpdater(srcDir);
            await Task.Delay(400);
            allowClose = true;
            tray?.Dispose();
            Close();
            (Application.Current?.ApplicationLifetime as IClassicDesktopStyleApplicationLifetime)?.Shutdown();
        }
        catch (Exception ex)
        {
            StatusLabel.Text = "Update fehlgeschlagen: " + ex.Message;
            UpdateButton.IsEnabled = true;
            updateBusy = false;
        }
    }

    // ---------- Transkription ----------

    private LiveTranscriber? windowLive;
    private string transcriptBeforeLive = "";

    // Smart Routing (Jev): Klassifikation zum aktuellen Transkript und der
    // Text, unter dem er im Verlauf gespeichert ist (fürs Nachziehen beim
    // Umschalten der Kategorie).
    private JevAnalysis? analysis;
    private string? analysisHistoryText;

    /// Smart Routing: nur Aufnahmen im Fenster und Dateien werden klassifiziert.
    /// Höchstens ~1,5 s Wartezeit, bei jedem Problem null → alles wie ohne Jev.
    private async Task<JevDecisionResult?> ClassifyAsync(string text, double seconds, string source)
    {
        if (source is not ("app" or "file")) return null;
        if (!Jev.IsActive(settings)) return null;
        StatusLabel.Text = "Klassifiziere …";
        return await Jev.AnalyzeAsync(settings, text, seconds, WhisperNative.IsParakeet ? "" : Lang);
    }

    private async void OnRecordClick(object? sender, RoutedEventArgs e)
    {
        if (busy || dictating || !File.Exists(WhisperCli.SelectedModel)) return;
        if (!recorder.IsRecording)
        {
            if (!recorder.Start())
            {
                if (settings.AudioFeedback) Cues.Error();
                StatusLabel.Text = "Mikrofon konnte nicht geöffnet werden.";
                return;
            }
            RecordButton.Content = "⏹  Aufnahme beenden";
            RecordButton.Classes.Add("recording");
            Waveform.ShowRecording(() => recorder.Level, recorder.ConsumePeak);
            StatusLabel.Text = "Aufnahme läuft…";
            if (settings.LiveTranscription && WhisperNative.Available)
            {
                transcriptBeforeLive = TranscriptBox.Text ?? "";
                windowLive = new LiveTranscriber(recorder, settings, Lang, settings.Translate, t =>
                    Dispatcher.UIThread.Post(() =>
                    {
                        if (recorder.IsRecording)
                            TranscriptBox.Text = (transcriptBeforeLive.Length > 0 ? transcriptBeforeLive + "\n\n" : "") + "🔴 " + t;
                    }));
                windowLive.Start();
            }
            return;
        }

        busy = true;
        var samples = recorder.Stop();
        var live = windowLive;
        windowLive = null;
        if (live != null) TranscriptBox.Text = transcriptBeforeLive;
        RecordButton.Content = "🎙  Aufnahme starten";
        RecordButton.Classes.Remove("recording");
        Waveform.ShowWorking();
        StatusLabel.Text = $"Transkribiere {samples.Length / 16000f:F1} s Audio…";
        var result = await TranscribeWithProgress(samples, live);
        string? note = null;
        // Smart Routing klassifiziert den Rohtext (vor Enhance), damit die
        // Entscheidung nicht vom geglätteten Text abhängt.
        var decision = result.Length > 0 ? await ClassifyAsync(result, samples.Length / 16000.0, "app") : null;
        if (decision is { Discard: true })
        {
            // Mikrofontest oder Gemurmel: nicht als Notiz speichern, der Text bleibt sichtbar
            analysis = decision.Analysis;
            AppendTranscript(result, samples.Length / 16000f, "app", saveHistory: false);
            RenderAnalysis();
            StatusLabel.Text = "Als Rauschen erkannt — nicht im Verlauf gespeichert";
            Waveform.ShowIdle();
            busy = false;
            return;
        }
        if (result.Length > 0)
        {
            // Eigene Aufnahme = Diktat: Snippets, Sprachbefehle, ggf. Enhance.
            // Mit Jev läuft das Sprachmodell nur, wenn der Text es braucht;
            // ohne Antwort (offline …) bleibt es beim bisherigen Verhalten.
            bool enhance = settings.EnhanceAuto && Enhancer.IsConfigured(settings) &&
                           (decision?.Analysis.NeedsCleanup ?? true);
            if (enhance) StatusLabel.Text = "Verbessere Text…";
            ShowWork(cancellable: enhance);
            enhanceCts = new CancellationTokenSource();
            var r = await TextPipeline.FinishDictationAsync(settings, result, Transcriber.DictationLanguage(settings),
                FormatMode.Standard, enhance, enhanceCts.Token);
            HideWork();
            result = r.Text;
            note = r.Note;
        }
        analysis = decision?.Analysis;
        AppendTranscript(result, samples.Length / 16000f, "app", analysis);
        RenderAnalysis();
        StatusLabel.Text = ResultStatus(result, samples.Length / 16000.0) + (note != null ? " " + note : "");
        Waveform.ShowIdle();
        busy = false;
    }

    /// Abbruch für Enhance-Läufe aus dem Hauptfenster.
    private CancellationTokenSource? enhanceCts;

    private async void OnFileClick(object? sender, RoutedEventArgs e)
    {
        if (busy || !File.Exists(WhisperCli.SelectedModel)) return;
        var files = await StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
        {
            Title = "Audio- oder Videodatei wählen",
            AllowMultiple = false,
            FileTypeFilter = new[]
            {
                new FilePickerFileType("Audio/Video")
                {
                    Patterns = new[]
                    {
                        "*.wav", "*.mp3", "*.flac", "*.ogg", "*.m4a", "*.aac", "*.wma", "*.opus",
                        "*.mp4", "*.mkv", "*.webm", "*.mov", "*.avi",
                    }
                },
                new FilePickerFileType("Alle Dateien") { Patterns = new[] { "*.*" } }
            }
        });
        if (files.Count == 0) return;

        var path = files[0].TryGetLocalPath();
        // Vorab-Prüfung: nur Audio/Video ist transkribierbar (Filter der
        // Auswahl hat eine „Alle Dateien“-Option).
        var knownMedia = new[]
        {
            ".wav", ".mp3", ".flac", ".ogg", ".m4a", ".aac", ".opus", ".wma", ".amr", ".3gp",
            ".mp4", ".mkv", ".webm", ".mov", ".avi",
        };
        if (path == null || !knownMedia.Contains(Path.GetExtension(path).ToLowerInvariant()))
        {
            StatusLabel.Text = $"„{Path.GetFileName(path ?? "?")}“ ist keine Audio-/Videodatei — " +
                               "PDFs, Bilder und Dokumente enthalten keine transkribierbare Sprache.";
            return;
        }

        busy = true;
        FileButton.IsEnabled = RecordButton.IsEnabled = false;
        Waveform.ShowWorking();
        var name = Path.GetFileName(path);
        try
        {
            // Media Foundation dekodiert, die geladene Engine transkribiert — so
            // gehen auch M4A/MP4/WMA und Parakeet-Modelle. whisper-cli bleibt
            // Rückfall für Formate, die Windows nicht kennt (z. B. Ogg).
            StatusLabel.Text = $"Lese „{name}“…";
            ShowWork(cancellable: false);
            var samples = await Task.Run(() => AudioFileDecoder.TryDecode(path!));
            HideWork();

            if (samples is { Length: > 0 })
            {
                var seconds = samples.Length / 16000.0;
                StatusLabel.Text = $"Transkribiere „{name}“ ({seconds:F1} s Audio)…";
                var text = await TranscribeWithProgress(samples);
                await FinishFileAsync(text, seconds, ResultStatus(text, seconds));
            }
            else if (samples != null)
            {
                StatusLabel.Text = "Die Datei enthält keine Audiodaten.";
            }
            else if (IsParakeetModel)
            {
                StatusLabel.Text = "Windows kann dieses Format nicht dekodieren — bitte als MP3, M4A oder WAV speichern.";
            }
            else
            {
                StatusLabel.Text = $"Transkribiere „{name}“ über whisper-cli…";
                ShowWork(cancellable: false);
                var lang = Lang;
                string err = "";
                var text = await Task.Run(() => Transcriber.Post(WhisperCli.Transcribe(path!, lang, out err,
                    TextMatching.Prompt(settings.Vocabulary, VocabularyDefaults.Lower), settings.Translate), settings));
                HideWork();
                await FinishFileAsync(text, 0,
                    err.Length > 0 ? err : text.Trim().Length == 0 ? "Keine Sprache erkannt." : "Fertig.");
            }
        }
        finally
        {
            HideWork();
        }
        FileButton.IsEnabled = RecordButton.IsEnabled = true;
        Waveform.ShowIdle();
        busy = false;
    }

    private void AppendTranscript(string text, float audioSeconds, string source,
        JevAnalysis? analysis = null, bool saveHistory = true)
    {
        var t = text.Trim();
        if (t.Length == 0) return;
        TranscriptBox.Text = TranscriptBox.Text?.Length > 0
            ? TranscriptBox.Text + "\n\n" + t
            : t;
        if (saveHistory) AddHistory(t, audioSeconds, source, analysis);
        if (analysis != null) analysisHistoryText = t;
    }

    private void AddHistory(string text, float audioSeconds, string source, JevAnalysis? analysis = null)
    {
        var entry = new HistoryEntry(
            DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            text,
            Path.GetFileName(WhisperCli.SelectedModel),
            WhisperNative.IsParakeet ? "" : Transcriber.DictationLanguage(settings),
            audioSeconds,
            source);
        HistoryStore.Add(analysis != null ? entry.WithAnalysis(analysis) : entry);
    }

    /// Gemeinsames Ende für Datei-Transkriptionen: Smart Routing, Rauschen-Wurf, Verlauf, Status.
    private async Task FinishFileAsync(string text, double seconds, string status)
    {
        var decision = await ClassifyAsync(text, seconds, "file");
        if (decision is { Discard: true })
        {
            analysis = decision.Analysis;
            AppendTranscript(text, (float)seconds, "file", saveHistory: false);
            RenderAnalysis();
            StatusLabel.Text = "Als Rauschen erkannt — nicht im Verlauf gespeichert";
            return;
        }
        analysis = decision?.Analysis;
        AppendTranscript(text, (float)seconds, "file", analysis);
        RenderAnalysis();
        StatusLabel.Text = status;
    }

    // ---------- Smart Routing (Jev): Badges am Transkript ----------

    /// Kategorie, Projekt und Dringlichkeit als Badges; bei mittlerer
    /// Sicherheit bietet ein Chip die zweitwahrscheinlichste Kategorie zum
    /// Umschalten an; „Text glätten“ erscheint nur, wenn Jev Bedarf sieht
    /// und ein Sprachmodell eingerichtet ist.
    private void RenderAnalysis()
    {
        AnalysisBadges.Children.Clear();
        var a = analysis;
        if (a == null || string.IsNullOrWhiteSpace(TranscriptBox.Text))
        {
            AnalysisBadges.IsVisible = false;
            return;
        }
        AnalysisBadges.Children.Add(Badge(JevDecision.IntentLabel(a.Intent),
            "BrandPrimaryContainerBrush", "BrandPrimaryBrush"));
        if (a.ProjectTag != null)
            AnalysisBadges.Children.Add(Badge(JevDecision.PrettyKey(a.ProjectTag),
                "BrandBadgeProjectBrush", "BrandBadgeProjectTextBrush"));
        if (a.UrgencyScore is { } u)
            AnalysisBadges.Children.Add(Badge($"Prio: {u:F1}",
                "BrandBadgePrioBrush", "BrandBadgePrioTextBrush"));
        if (a.NeedsDisambiguation && a.TopAlternativeIntent is { } alt)
        {
            var chip = new Button { Classes = { "chip" }, Content = "⇄  Eher: " + JevDecision.IntentLabel(alt) + "?" };
            chip.Click += (_, _) => PickIntent(alt);
            AnalysisBadges.Children.Add(chip);
        }
        if (a.NeedsCleanup == true && Enhancer.IsConfigured(settings))
        {
            var smooth = new Button { Classes = { "chip" }, Content = "✨  Text glätten" };
            smooth.Click += async (_, _) => await SmoothTranscriptAsync();
            AnalysisBadges.Children.Add(smooth);
        }
        AnalysisBadges.IsVisible = AnalysisBadges.Children.Count > 0;
    }

    private static Control Badge(string text, string backgroundKey, string foregroundKey) => new Border
    {
        CornerRadius = new CornerRadius(8),
        Padding = new Thickness(10, 4),
        Background = BrandBrush(backgroundKey, Brushes.LightGray),
        Child = new TextBlock
        {
            Text = text, FontSize = 12, FontWeight = FontWeight.Medium,
            Foreground = BrandBrush(foregroundKey, Brushes.Black),
        },
    };

    /// Nutzer übersteuert die Kategorie: Badges und Verlauf nachziehen.
    private void PickIntent(string picked)
    {
        if (analysis is not { } current) return;
        var switched = current with { Intent = picked, TopAlternativeIntent = current.Intent };
        analysis = switched;
        if (analysisHistoryText is { Length: > 0 } h) HistoryStore.UpdateAnalysis(h, switched);
        RenderAnalysis();
    }

    /// „Text glätten“: Sprachmodell glättet nur die Anzeige — das Original
    /// im Verlauf behält seine Klassifikation.
    private async Task SmoothTranscriptAsync()
    {
        var text = TranscriptBox.Text?.Trim() ?? "";
        if (busy || text.Length == 0 || !Enhancer.IsConfigured(settings)) return;
        if (analysis is { } a) analysis = a with { NeedsCleanup = false };
        busy = true;
        ShowWork(cancellable: true);
        StatusLabel.Text = "Glätte Text …";
        enhanceCts = new CancellationTokenSource();
        var lang = TextRules.GuessLanguage(text);
        var o = await Enhancer.TransformAsync(settings, text, TransformActions.Instruction("improve", text),
            lang.Length > 0 ? lang : Transcriber.DictationLanguage(settings), enhanceCts.Token);
        HideWork();
        busy = false;
        if (o.Text != null)
        {
            TranscriptBox.Text = o.Text;
            StatusLabel.Text = "Text geglättet ✓ — der Verlauf behält das Original";
        }
        else
        {
            StatusLabel.Text = o.Cancelled ? "Abgebrochen." : "Sprachmodell fehlgeschlagen: " + o.Error;
        }
        RenderAnalysis();
    }

    // ---------- Verlauf: siehe MainWindow.Panels.cs ----------

    private async void OnCopyClick(object? sender, RoutedEventArgs e)
    {
        if (TranscriptBox.Text?.Length > 0)
            await Clipboard!.SetTextAsync(TranscriptBox.Text);
    }

    private void OnClearClick(object? sender, RoutedEventArgs e)
    {
        TranscriptBox.Text = "";
        analysis = null;
        analysisHistoryText = null;
        RenderAnalysis();
    }

    // ---------- Teilen ----------
    private void OnShareClick(object? sender, RoutedEventArgs e) { } // öffnet nur das Flyout

    /// WhatsApp mit vorbefülltem Text (wa.me-Link öffnet die Desktop-App).
    private void OnShareWhatsApp(object? sender, RoutedEventArgs e)
    {
        var text = TranscriptBox.Text;
        if (string.IsNullOrEmpty(text)) return;
        OpenExternal("https://wa.me/?text=" + Uri.EscapeDataString(ShareText(text)));
    }

    /// Standard-Mail-Programm mit Transkript als Entwurf.
    private void OnShareMail(object? sender, RoutedEventArgs e)
    {
        var text = TranscriptBox.Text;
        if (string.IsNullOrEmpty(text)) return;
        OpenExternal(
            $"mailto:?subject={Uri.EscapeDataString("Transkript – Scheisssewasser's Whisper")}" +
            $"&body={Uri.EscapeDataString(ShareText(text))}");
    }

    /// URLs/MAILTO haben praktische Längenlimits — den Rest gibt es über
    /// die Zwischenablage (Menüpunkt darunter).
    private static string ShareText(string s) =>
        s.Length > 1800 ? s[..1800] + " …" : s;

    private static void OpenExternal(string url) =>
        Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
}
