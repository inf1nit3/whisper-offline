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
        ShortCtxBox.IsChecked = settings.ShortCtx;
        UpdateHotkeyLabel();
        suppressSettingsEvents = false;

        // Gespeichertes Modell aktivieren, falls vorhanden; sonst Auswahl öffnen
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
        else
        {
            _ = OpenPickerAsync();
        }

        // In Tray minimieren statt beenden; Hotkey aktivieren
        Closing += (_, e) =>
        {
            if (!allowClose) { e.Cancel = true; Hide(); }
        };
        SetupTray();
        HotkeyHook.Start(() => Dispatcher.UIThread.Post(ToggleDictation),
                         settings.HotkeyModifiers, settings.HotkeyVk);

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
            ? $"Engine geladen im Hintergrund · {WhisperNative.BackendInfo}"
            : WhisperNative.Available
                ? "Engine noch nicht geladen"
                : "whisper_shim.dll nicht gefunden — Rückfall auf whisper-cli (langsamer)";

        // Parakeet: mehrsprachig, kein festes Encoder-Fenster — beide
        // Bedienelemente hätten dort keine Wirkung.
        var parakeet = engineWarm && WhisperNative.IsParakeet;
        LanguageBox.IsEnabled = !parakeet;
        ShortCtxBox.IsEnabled = !parakeet;
        if (parakeet) EngineLabel.Text += " · mehrsprachig, ohne Sprachauswahl";
    }

    /// Transkribiert Mikrofon-Samples. Bevorzugt die geladene Engine; nur wenn
    /// die DLL fehlt oder scheitert, wird whisper-cli als Unterprozess benutzt.
    private string TranscribeSamples(float[] samples)
    {
        var lang = string.IsNullOrEmpty(Lang) ? "auto" : Lang;
        if (WhisperNative.EnsureLoaded(WhisperCli.SelectedModel))
        {
            var native = WhisperNative.Transcribe(samples, lang, settings.ShortCtx);
            if (native != null) return native.Trim();
        }
        var wav = Path.Combine(Path.GetTempPath(), "whisper_offline_rec.wav");
        WavWriter.Write16kMono(wav, samples);
        return WhisperCli.Transcribe(wav, Lang, out _).Trim();
    }

    // ---------- Hotkey und Hintergrundbetrieb ----------

    private void UpdateHotkeyLabel()
    {
        HotkeyButton.Content = capturingHotkey
            ? "Kombination drücken…"
            : HotkeySpec.Describe(settings.HotkeyModifiers, settings.HotkeyVk);
        HotkeyHint.Text = HotkeyHook.LastBindOk
            ? ""
            : "Kombination ist belegt — bitte eine andere wählen";
        if (tray != null)
            tray.ToolTipText = "Scheisssewasser's Whisper — " +
                HotkeySpec.Describe(settings.HotkeyModifiers, settings.HotkeyVk) + ": Diktat";
    }

    private void OnHotkeyCapture(object? sender, RoutedEventArgs e)
    {
        capturingHotkey = true;
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

        var vk = HotkeySpec.ToVk(e.Key);
        var mods = HotkeySpec.ToModifiers(e.KeyModifiers);
        if (vk == 0 || mods == 0)
        {
            // Ohne Modifikator würde der Hotkey die Taste systemweit schlucken.
            HotkeyHint.Text = "Bitte mit Strg, Alt, Umschalt oder Windows kombinieren";
            e.Handled = true;
            return;
        }

        settings.HotkeyModifiers = mods;
        settings.HotkeyVk = vk;
        settings.Save();
        capturingHotkey = false;
        HotkeyHook.Rebind(mods, vk);
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

    private void OnShortCtxChanged(object? sender, RoutedEventArgs e)
    {
        if (suppressSettingsEvents) return;
        settings.ShortCtx = ShortCtxBox.IsChecked == true;
        settings.Save();
    }

    private void SetupTray()
    {
        // 32x32-Icon aus dem Code zeichnen (blauer Grund, weißes Mikrofon-Symbol)
        var rtb = new RenderTargetBitmap(new PixelSize(32, 32));
        using (var ctx = rtb.CreateDrawingContext())
        {
            ctx.DrawRectangle(Brushes.SteelBlue, null, new Rect(0, 0, 32, 32), 6, 6);
            ctx.DrawEllipse(Brushes.White, null, new Point(16, 13), 4, 6);
            ctx.DrawRectangle(Brushes.White, null, new Rect(12, 19, 8, 2), 1, 1);
            ctx.DrawRectangle(Brushes.White, null, new Rect(15, 21, 2, 5), 1, 1);
            ctx.DrawRectangle(Brushes.White, null, new Rect(10, 25, 12, 2), 1, 1);
        }

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
            Icon = new WindowIcon(rtb),
            Menu = menu,
            ToolTipText = "Scheisssewasser's Whisper — Strg+Alt+Leertaste: Diktat",
        };
        tray.Clicked += (_, _) => { Show(); Activate(); };
        TrayIcon.SetIcons(Application.Current, new TrayIcons { tray });
    }

    /// Strg+Alt+Leertaste: Aufnahme starten/beenden; danach wird der Text
    /// automatisch per Strg+V ins zuvor fokussierte Fenster (z. B. Chat) eingefügt.
    private async void ToggleDictation()
    {
        if (busy || pickerOpen || downloading != null || dictating && !recorder.IsRecording) return;
        if (!File.Exists(WhisperCli.SelectedModel)) return;

        if (!dictating)
        {
            dictationTarget = PasteHelper.ForegroundWindow();
            if (dictationTarget == IntPtr.Zero) return;
            if (!recorder.Start()) return;
            dictating = true;
            StatusLabel.Text = "🎙 Diktat läuft — " +
                HotkeySpec.Describe(settings.HotkeyModifiers, settings.HotkeyVk) +
                " beendet & fügt ein";
            return;
        }

        var samples = recorder.Stop();
        dictating = false;
        if (samples.Length < 1600) // < 0,1 s
        {
            StatusLabel.Text = "Diktat zu kurz";
            return;
        }

        busy = true;
        StatusLabel.Text = "Transkribiere Diktat…";
        var text = (await Task.Run(() => TranscribeSamples(samples))).Trim();
        if (text.Length == 0)
        {
            StatusLabel.Text = "Kein Text erkannt";
            busy = false;
            return;
        }

        AppendTranscript(text);
        if (Clipboard != null) await Clipboard.SetTextAsync(text);
        await Task.Delay(250);              // Zwischenablage settling
        PasteHelper.FocusWindow(dictationTarget);
        await Task.Delay(150);              // Fokuswechsel settling
        PasteHelper.CtrlV();                // fügt in den Chat ein
        StatusLabel.Text = "Diktat eingefügt ✓";
        busy = false;
    }

    private void UpdateModelLabel() =>
        ModelLabel.Text = Path.GetFileName(WhisperCli.SelectedModel) + " bereit";

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
        PickerList.Children.Add(new TextBlock { Text = "Frage Server nach verfügbaren Modellen…", Foreground = Brushes.Gray });
        try
        {
            manifest = await ModelRegistry.FetchManifest();
        }
        catch (Exception ex)
        {
            PickerList.Children.Clear();
            PickerError.Text = "Server nicht erreichbar: " + ex.Message;
            var retry = new Button { Content = "Erneut versuchen", HorizontalAlignment = HorizontalAlignment.Left };
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

            var border = new Border
            {
                BorderBrush = Brushes.LightGray,
                BorderThickness = new Avalonia.Thickness(1),
                CornerRadius = new Avalonia.CornerRadius(6),
                Padding = new Avalonia.Thickness(14),
                Child = BuildModelCard(info, isLocal, isCurrent),
            };
            PickerList.Children.Add(border);
        }

        // Lokale Modelle ohne Manifest-Eintrag
        foreach (var f in local.Where(f => manifest.All(m => m.File != f)))
        {
            var panel = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12 };
            panel.Children.Add(new TextBlock { Text = f, VerticalAlignment = VerticalAlignment.Center });
            var b = new Button { Content = "Aktivieren" };
            b.Click += (_, _) => Activate(f);
            panel.Children.Add(b);
            PickerList.Children.Add(panel);
        }
    }

    private StackPanel BuildModelCard(ModelInfo info, bool isLocal, bool isCurrent)
    {
        var card = new StackPanel { Spacing = 6 };

        var head = new StackPanel { Orientation = Orientation.Horizontal };
        head.Children.Add(new TextBlock
        {
            Text = info.Label, FontWeight = FontWeight.Bold,
            VerticalAlignment = VerticalAlignment.Center,
        });
        head.Children.Add(new TextBlock
        {
            Text = info.SizeText, Foreground = Brushes.Gray, FontSize = 12,
            VerticalAlignment = VerticalAlignment.Center, Margin = new Avalonia.Thickness(10, 0, 0, 0),
        });
        card.Children.Add(head);
        card.Children.Add(new TextBlock
        {
            Text = info.Tagline, Foreground = Brushes.SteelBlue, FontSize = 12,
        });
        foreach (var p in info.Pros)
            card.Children.Add(new TextBlock { Text = "✓  " + p, FontSize = 12 });
        foreach (var c in info.Cons)
            card.Children.Add(new TextBlock
            {
                Text = "✗  " + c, FontSize = 12, Foreground = Brushes.Gray,
            });

        if (isCurrent)
        {
            card.Children.Add(new TextBlock
            {
                Text = "✓ Aktiv", FontWeight = FontWeight.Bold, Foreground = Brushes.Green,
            });
            return card;
        }

        var action = new Button();
        if (isLocal)
        {
            action.Content = "Aktivieren (bereits geladen)";
            action.Click += (_, _) => Activate(info.File);
        }
        else
        {
            action.Content = $"⬇  Herunterladen ({info.SizeText})";
            action.IsEnabled = downloading == null;
            action.Click += async (_, _) => await DownloadAsync(info);
        }
        card.Children.Add(action);

        var progress = new ProgressBar
        {
            Minimum = 0, Maximum = 1, Value = 0, Height = 14, IsVisible = false,
        };
        var progressLabel = new TextBlock { Text = "", IsVisible = false, FontSize = 12 };
        card.Children.Add(progress);
        card.Children.Add(progressLabel);
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
                    PickerError.Text = ""; // keine Fehler während des Laufs
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
            UpdateButton.Content = $"⬆  Update auf {release.Tag}";
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
        if (pendingUpdate != null) { await ApplyUpdateAsync(pendingUpdate); return; }
        await CheckUpdateAsync(silent: false);
    }

    private async Task ApplyUpdateAsync(GhRelease release)
    {
        if (release.ZipUrl == null)
        {
            StatusLabel.Text = "Release enthält kein Zip-Paket.";
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
            var srcDir = await Task.Run(() => SelfUpdater.DownloadAndExtractAsync(release.ZipUrl, progress));
            StatusLabel.Text = "Installation wird angewendet — App startet neu…";
            SelfUpdater.LaunchUpdater(srcDir);
            await Task.Delay(400); // Skript-Start abwarten
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

    private async void OnRecordClick(object? sender, RoutedEventArgs e)
    {
        if (busy || !File.Exists(WhisperCli.SelectedModel)) return;
        if (!recorder.IsRecording)
        {
            if (!recorder.Start())
            {
                StatusLabel.Text = "Mikrofon konnte nicht geöffnet werden.";
                return;
            }
            RecordButton.Content = "⏹  Aufnahme beenden";
            StatusLabel.Text = "Aufnahme läuft…";
            return;
        }

        busy = true;
        var samples = recorder.Stop();
        RecordButton.Content = "🎙  Aufnahme starten";
        StatusLabel.Text = $"Transkribiere {samples.Length / 16000f:F1} s Audio…";
        var result = await Task.Run(() => TranscribeSamples(samples));
        AppendTranscript(result);
        StatusLabel.Text = $"Fertig ({samples.Length / 16000f:F1} s Audio transkribiert).";
        busy = false;
    }

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
                    Patterns = new[] { "*.wav", "*.mp3", "*.flac", "*.ogg", "*.m4a", "*.mp4", "*.mkv", "*.webm" }
                },
                new FilePickerFileType("Alle Dateien") { Patterns = new[] { "*.*" } }
            }
        });
        if (files.Count == 0) return;

        busy = true;
        FileButton.IsEnabled = RecordButton.IsEnabled = false;
        var path = files[0].TryGetLocalPath();
        StatusLabel.Text = $"Transkribiere „{Path.GetFileName(path)}“…";
        var lang = Lang;
        var result = await Task.Run(() => WhisperCli.Transcribe(path!, lang, out var err)
                                            + (err.Length > 0 ? $"\n[{err}]" : ""));
        AppendTranscript(result);
        StatusLabel.Text = "Fertig.";
        FileButton.IsEnabled = RecordButton.IsEnabled = true;
        busy = false;
    }

    private void AppendTranscript(string text)
    {
        var t = text.Trim();
        if (t.Length == 0) return;
        TranscriptBox.Text = TranscriptBox.Text?.Length > 0
            ? TranscriptBox.Text + "\n\n" + t
            : t;
        HistoryStore.Add(new HistoryEntry(
            DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            t,
            Path.GetFileName(WhisperCli.SelectedModel),
            language,
            0));
    }

    // ---------- Verlauf ----------

    private void OnOpenHistory(object? sender, RoutedEventArgs e)
    {
        BuildHistoryList();
        HistoryPanel.IsVisible = true;
        MainPanel.IsVisible = false;
        PickerPanel.IsVisible = false;
    }

    private void OnHistoryClose(object? sender, RoutedEventArgs e)
    {
        HistoryPanel.IsVisible = false;
        MainPanel.IsVisible = true;
    }

    private void OnHistoryClear(object? sender, RoutedEventArgs e)
    {
        HistoryStore.Clear();
        BuildHistoryList();
    }

    private void BuildHistoryList()
    {
        HistoryList.Children.Clear();
        var entries = HistoryStore.Load();
        HistoryTitle.Text = $"Verlauf ({entries.Count})";
        if (entries.Count == 0)
        {
            HistoryList.Children.Add(new TextBlock { Text = "Noch keine Einträge.", Foreground = Brushes.Gray });
            return;
        }
        foreach (var e in entries)
        {
            var border = new Border
            {
                BorderBrush = Brushes.LightGray,
                BorderThickness = new Avalonia.Thickness(1),
                CornerRadius = new Avalonia.CornerRadius(6),
                Padding = new Avalonia.Thickness(12),
                Child = BuildHistoryCard(e),
            };
            HistoryList.Children.Add(border);
        }
    }

    private StackPanel BuildHistoryCard(HistoryEntry e)
    {
        var card = new StackPanel { Spacing = 4 };
        var head = new StackPanel { Orientation = Orientation.Horizontal };
        head.Children.Add(new TextBlock
        {
            Text = e.DateText, FontWeight = FontWeight.Bold,
            VerticalAlignment = VerticalAlignment.Center,
        });
        head.Children.Add(new TextBlock
        {
            Text = $"  {e.Model} · {e.Language}",
            FontSize = 12, Foreground = Brushes.Gray,
            VerticalAlignment = VerticalAlignment.Center,
        });
        var spacer = new Control();
        head.Children.Add(spacer);
        var copy = new Button { Content = "Kopieren", FontSize = 12, HorizontalAlignment = HorizontalAlignment.Right };
        copy.Click += async (_, _) =>
        {
            if (Clipboard != null) await Clipboard.SetTextAsync(e.Text);
        };
        head.Children.Add(copy);
        var del = new Button { Content = "Löschen", FontSize = 12, Margin = new Avalonia.Thickness(6, 0, 0, 0) };
        del.Click += (_, _) => { HistoryStore.Delete(e); BuildHistoryList(); };
        head.Children.Add(del);
        card.Children.Add(head);
        card.Children.Add(new TextBlock { Text = e.Text, TextWrapping = TextWrapping.Wrap, MaxHeight = 120 });
        return card;
    }

    private async void OnCopyClick(object? sender, RoutedEventArgs e)
    {
        if (TranscriptBox.Text?.Length > 0)
            await Clipboard!.SetTextAsync(TranscriptBox.Text);
    }

    private void OnClearClick(object? sender, RoutedEventArgs e) => TranscriptBox.Text = "";
}
