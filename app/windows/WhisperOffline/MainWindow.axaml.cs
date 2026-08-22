using System.Diagnostics;
using System.IO;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
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

    private string SettingsPath => Path.Combine(WhisperCli.BaseDir, "settings.json");

    private record Settings(string Model);

    public MainWindow()
    {
        InitializeComponent();
        LanguageBox.SelectionChanged += (_, _) =>
        {
            language = (LanguageBox.SelectedItem as ComboBoxItem)?.Tag as string ?? "auto";
        };

        // Gespeichertes Modell aktivieren, falls vorhanden; sonst Auswahl öffnen
        string? stored = null;
        try
        {
            if (File.Exists(SettingsPath))
                stored = JsonSerializer.Deserialize<Settings>(File.ReadAllText(SettingsPath))?.Model;
        }
        catch { }

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
        HotkeyHook.Start(() => Dispatcher.UIThread.Post(ToggleDictation));
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
            ToolTipText = "Whisper Offline — Strg+Alt+Leertaste: Diktat",
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
            StatusLabel.Text = "🎙 Diktat läuft — Strg+Alt+Leertaste beendet & fügt ein";
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
        var lang = Lang;
        var text = await Task.Run(() =>
        {
            var wav = Path.Combine(Path.GetTempPath(), "whisper_offline_dict.wav");
            WavWriter.Write16kMono(wav, samples);
            return WhisperCli.Transcribe(wav, lang, out var err);
        });
        text = text.Trim();
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
        try
        {
            Directory.CreateDirectory(WhisperCli.ModelsDir);
            File.WriteAllText(SettingsPath,
                JsonSerializer.Serialize(new Settings(file)));
        }
        catch { }
        UpdateModelLabel();
        ClosePicker();
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
        var lang = Lang;
        var result = await Task.Run(() =>
        {
            var wav = Path.Combine(Path.GetTempPath(), "whisper_offline_rec.wav");
            WavWriter.Write16kMono(wav, samples);
            return WhisperCli.Transcribe(wav, lang, out var err) + (err.Length > 0 ? $"\n[{err}]" : "");
        });
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
    }

    private async void OnCopyClick(object? sender, RoutedEventArgs e)
    {
        if (TranscriptBox.Text?.Length > 0)
            await Clipboard!.SetTextAsync(TranscriptBox.Text);
    }

    private void OnClearClick(object? sender, RoutedEventArgs e) => TranscriptBox.Text = "";
}
