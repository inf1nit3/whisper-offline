using System.IO;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Platform.Storage;
using Avalonia.Threading;

namespace WhisperOffline;

// Bereiche im Hauptfenster, im Code aufgebaut: Einstellungen, Wörterbuch,
// Statistik, Verlauf, Sprachmodelle, App-Profile. Teil von MainWindow.
public partial class MainWindow
{
    /// Beim Schließen eines Unterbereichs zurück zum übergeordneten.
    private Action? panelBack;

    private static IBrush BrandBrush(string key, IBrush fallback) =>
        Application.Current?.TryFindResource(key, Application.Current.ActualThemeVariant, out var res) == true && res is IBrush b
            ? b : fallback;

    private static IBrush PrimaryBrush => BrandBrush("BrandPrimaryBrush", Brushes.SteelBlue);
    private static IBrush TertiaryBrush => BrandBrush("BrandTertiaryBrush", Brushes.SeaGreen);
    private static IBrush ErrorBrush => BrandBrush("BrandErrorBrush", Brushes.IndianRed);
    private static IBrush SubtleBrush => BrandBrush("BrandSubtleTextBrush", Brushes.Gray);
    private static IBrush CardBorderBrush => BrandBrush("BrandCardBorderBrush", Brushes.LightGray);

    private void ShowPanel(string title, string? subtitle, Control content, params Control[] actions)
    {
        PanelTitle.Text = title;
        PanelSubtitle.Text = subtitle ?? "";
        PanelSubtitle.IsVisible = subtitle != null;
        PanelActions.Children.Clear();
        foreach (var a in actions)
        {
            if (a is Button b && !b.Classes.Contains("chip") && !b.Classes.Contains("primaryAction"))
                b.Classes.Add("chip");
            PanelActions.Children.Add(a);
        }
        PanelContent.Content = content;
        PanelHost.IsVisible = true;
        MainPanel.IsVisible = false;
    }

    private void OnPanelClose(object? sender, RoutedEventArgs e) => ClosePanel();

    private void ClosePanel()
    {
        settingsHotkeyRefresh = null;
        if (panelBack != null)
        {
            var back = panelBack;
            panelBack = null;
            back();
            return;
        }
        PanelHost.IsVisible = false;
        PanelContent.Content = null;
        MainPanel.IsVisible = true;
    }

    // ---------- Bausteine (synchronisiert mit Screens.kt auf Android) ----------

    private static TextBlock Section(string text) => new()
    {
        Text = text, FontSize = 14, FontWeight = FontWeight.SemiBold, Foreground = PrimaryBrush,
        Margin = new Thickness(0, 18, 0, 6),
    };

    private static TextBlock Hint(string text) => new()
    {
        Text = text, FontSize = 12, Foreground = SubtleBrush, TextWrapping = TextWrapping.Wrap,
    };

    private Control Toggle(string label, string? hint, bool value, Action<bool> set)
    {
        var box = new CheckBox { Content = label, IsChecked = value, FontWeight = FontWeight.Medium };
        box.IsCheckedChanged += (_, _) => { set(box.IsChecked == true); settings.Save(); };
        if (hint == null) return box;
        return new StackPanel { Spacing = 2, Children = { box, Hint(hint).With(h => h.Margin = new Thickness(28, 0, 0, 4)) } };
    }

    // Farben aus App.axaml (Border.card) — folgen Hell/Dunkel
    private static Border Card(Control child) => new()
    {
        Classes = { "card" },
        Child = child,
    };

    private static ScrollViewer Scroll(Control c) => new()
    {
        Content = c,
        HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
    };

    /// Einfacher Ja/Nein-Dialog im Marken-Stil.
    private async Task<bool> Confirm(string title, string text, string yes = "Löschen")
    {
        var tcs = new TaskCompletionSource<bool>();
        var w = new Window
        {
            Title = title, Width = 400, SizeToContent = SizeToContent.Height, CanResize = false,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Background = BrandBrush("BrandSurfaceBrush", Brushes.White),
            Icon = AppIcon.Get(),
        };
        var ok = new Button { Content = yes, Classes = { "primaryAction" }, Background = ErrorBrush };
        var no = new Button { Content = "Abbrechen", Classes = { "chip" }, IsCancel = true };
        ok.Click += (_, _) => { tcs.TrySetResult(true); w.Close(); };
        no.Click += (_, _) => { tcs.TrySetResult(false); w.Close(); };
        w.Closed += (_, _) => tcs.TrySetResult(false);
        w.Content = new Border
        {
            Margin = new Thickness(16),
            Classes = { "card" },
            Child = new StackPanel
            {
                Spacing = 14,
                Children =
                {
                    new TextBlock { Text = title, FontSize = 16, FontWeight = FontWeight.SemiBold },
                    new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap, Foreground = SubtleBrush },
                    new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, HorizontalAlignment = HorizontalAlignment.Right, Children = { no, ok } },
                },
            },
        };
        await w.ShowDialog(this);
        return await tcs.Task;
    }

    // ---------- Einstellungen ----------

    private void OnOpenSettings(object? sender, RoutedEventArgs e) => ShowSettings();

    private void ShowSettings()
    {
        var p = new StackPanel { Spacing = 8, Margin = new Thickness(0, 0, 12, 24) };

        // Diktat
        p.Children.Add(Section("Diktat"));
        var mics = AudioRecorder.InputDevices();
        var micBox = new ComboBox { MinWidth = 320, CornerRadius = new CornerRadius(14) };
        micBox.Items.Add("Standardgerät von Windows");
        foreach (var m in mics) micBox.Items.Add(m.Name);
        micBox.SelectedIndex = Math.Max(0, mics.FindIndex(m => m.Id == settings.MicDeviceId) + 1);
        micBox.SelectionChanged += (_, _) =>
        {
            settings.MicDeviceId = micBox.SelectedIndex <= 0 ? "" : mics[micBox.SelectedIndex - 1].Id;
            settings.Save();
        };

        var dictCard = new StackPanel { Spacing = 8 };
        dictCard.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10, Children = { new TextBlock { Text = "Mikrofon:", VerticalAlignment = VerticalAlignment.Center, FontWeight = FontWeight.Medium }, micBox } });
        dictCard.Children.Add(Toggle("Töne", "Kurzer Ton bei Start, Ende und Fehlern", settings.AudioFeedback, v => settings.AudioFeedback = v));

        var duckSlider = new Slider { Minimum = 0, Maximum = 80, Value = settings.DuckingLevel, Width = 200 };
        var duckLabel = new TextBlock { Text = $"{settings.DuckingLevel} %", VerticalAlignment = VerticalAlignment.Center, Foreground = SubtleBrush };
        duckSlider.PropertyChanged += (_, ev) =>
        {
            if (ev.Property != Slider.ValueProperty) return;
            settings.DuckingLevel = (int)duckSlider.Value;
            duckLabel.Text = $"{settings.DuckingLevel} %";
            settings.Save();
        };
        dictCard.Children.Add(Toggle("Andere Wiedergabe leiser", "Musik und Videos laufen während der Aufnahme leiser", settings.Ducking, v => settings.Ducking = v));
        dictCard.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Margin = new Thickness(28, 0, 0, 4),
            Children = { new TextBlock { Text = "Lautstärke dabei:", VerticalAlignment = VerticalAlignment.Center, FontSize = 12, Foreground = SubtleBrush }, duckSlider, duckLabel } });
        dictCard.Children.Add(Toggle("Sprachbefehle", "„Komma“, „Punkt“, „Fragezeichen“, „neue Zeile“, „neuer Absatz“ werden umgesetzt", settings.SpokenCommands, v => settings.SpokenCommands = v));
        dictCard.Children.Add(Toggle("Live-Transkription", "Text erscheint schon beim Sprechen (im Overlay bzw. im Fenster); das Ergebnis steht nach dem Stoppen fast sofort da", settings.LiveTranscription, v => settings.LiveTranscription = v));
        dictCard.Children.Add(Toggle("Ins Englische übersetzen", "Nur Whisper-Modelle", settings.Translate, v => { settings.Translate = v; TranslateBox.IsChecked = v; }));
        dictCard.Children.Add(Toggle("Overlay anzeigen", "Kleines Fenster unten am Bildschirm während eines Diktats", settings.ShowOverlay, v => settings.ShowOverlay = v));
        dictCard.Children.Add(Toggle("Zwischenablage erhalten", "Nach dem Einfügen liegt wieder drin, was vorher drin war (Text, Bilder, Dateien)", settings.PreserveClipboard, v => settings.PreserveClipboard = v));
        dictCard.Children.Add(Toggle("Verlauf speichern", "Aus: Transkripte werden nirgends gespeichert, auch nicht für die Statistik", settings.HistoryEnabled, v => { settings.HistoryEnabled = v; HistoryStore.Enabled = v; }));
        p.Children.Add(Card(dictCard));

        // Tastenkürzel
        p.Children.Add(Section("Tastenkürzel"));
        var hkCard = new StackPanel { Spacing = 8 };
        var modeBox = new ComboBox { Items = { "Antippen: Start und Stopp", "Gedrückt halten (Push-to-Talk)" }, SelectedIndex = settings.PushToTalk ? 1 : 0, CornerRadius = new CornerRadius(14) };
        modeBox.SelectionChanged += (_, _) => { settings.PushToTalk = modeBox.SelectedIndex == 1; settings.Save(); UpdateHotkeyLabel(); };
        hkCard.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Children = { new TextBlock { Text = "Diktat-Hotkey:", Width = 170, VerticalAlignment = VerticalAlignment.Center }, modeBox } });

        var singleBox = new ComboBox { MinWidth = 220, CornerRadius = new CornerRadius(14) };
        foreach (var k in HotkeySpec.SingleKeys) singleBox.Items.Add(k.Name);
        singleBox.SelectedIndex = Math.Max(0, Array.FindIndex(HotkeySpec.SingleKeys, k => k.Vk == settings.SingleKeyVk));
        singleBox.SelectionChanged += (_, _) =>
        {
            settings.SingleKeyVk = HotkeySpec.SingleKeys[Math.Max(0, singleBox.SelectedIndex)].Vk;
            settings.Save();
            HotkeyHook.SetSingleKey(settings.SingleKeyVk);
            UpdateHotkeyLabel();
        };
        hkCard.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Children = { new TextBlock { Text = "Zusätzlich Einzeltaste:", Width = 170, VerticalAlignment = VerticalAlignment.Center }, singleBox } });
        hkCard.Children.Add(Hint("Einzeltaste: antippen = Start/Stopp (bzw. halten bei Push-to-Talk). Zusammen mit anderen Tasten bleibt sie ein normaler Kurzbefehl."));

        var enhBtn = new Button { MinWidth = 220, Classes = { "chip" } };
        var trBtn = new Button { MinWidth = 220, Classes = { "chip" } };
        void RefreshHotkeyButtons()
        {
            enhBtn.Content = capturingHotkey && capturingTarget == HotkeyAction.DictateEnhance ? "Kombination drücken … (Entf = aus)"
                : settings.EnhanceHotkeyVk == 0 ? "aus — klicken zum Belegen" : HotkeySpec.Describe(settings.EnhanceHotkeyModifiers, settings.EnhanceHotkeyVk);
            trBtn.Content = capturingHotkey && capturingTarget == HotkeyAction.Transform ? "Kombination drücken … (Entf = aus)"
                : settings.TransformHotkeyVk == 0 ? "aus — klicken zum Belegen" : HotkeySpec.Describe(settings.TransformHotkeyModifiers, settings.TransformHotkeyVk);
        }
        RefreshHotkeyButtons();
        settingsHotkeyRefresh = RefreshHotkeyButtons;
        enhBtn.Click += (_, _) => BeginHotkeyCapture(HotkeyAction.DictateEnhance);
        trBtn.Click += (_, _) => BeginHotkeyCapture(HotkeyAction.Transform);
        hkCard.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Children = { new TextBlock { Text = "Diktat + Enhance:", Width = 170, VerticalAlignment = VerticalAlignment.Center }, enhBtn } });
        hkCard.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Children = { new TextBlock { Text = "Auswahl bearbeiten:", Width = 170, VerticalAlignment = VerticalAlignment.Center }, trBtn } });
        hkCard.Children.Add(Hint("„Diktat + Enhance“ verbessert immer, auch wenn „automatisch verbessern“ aus ist. „Auswahl bearbeiten“ kopiert den markierten Text, bietet Kürzen, Professioneller, Übersetzen … an und setzt das Ergebnis ein."));
        p.Children.Add(Card(hkCard));

        // Enter-Liste
        p.Children.Add(Section("Enter nach dem Einfügen"));
        var enterCard = new StackPanel { Spacing = 8 };
        enterCard.Children.Add(Hint("Nur in diesen Programmen — praktisch für Chats und Terminals, in denen Enter abschickt."));
        var enterList = new WrapPanel { ItemSpacing = 6, LineSpacing = 6 };
        void RefreshEnter()
        {
            enterList.Children.Clear();
            foreach (var app in settings.AutoEnterApps.ToList())
            {
                var b = new Button { Content = app + "  ✕", Classes = { "chip" } };
                b.Click += (_, _) => { settings.AutoEnterApps.Remove(app); settings.Save(); RefreshEnter(); };
                enterList.Children.Add(b);
            }
        }
        RefreshEnter();
        var running = new ComboBox { MinWidth = 220, PlaceholderText = "Laufendes Programm wählen", CornerRadius = new CornerRadius(14) };
        foreach (var r in ForegroundInfo.RunningApps()) running.Items.Add(r);
        var addEnter = new Button { Content = "+ Hinzufügen", Classes = { "chip" } };
        addEnter.Click += (_, _) =>
        {
            if (running.SelectedItem is string app && !settings.AutoEnterApps.Contains(app))
            {
                settings.AutoEnterApps.Add(app); settings.Save(); RefreshEnter();
            }
        };
        enterCard.Children.Add(enterList);
        enterCard.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Children = { running, addEnter } });
        p.Children.Add(Card(enterCard));

        // Enhance
        p.Children.Add(Section("Enhance — Text verbessern"));
        var enhCard = new StackPanel { Spacing = 8 };
        var local = new RadioButton { Content = "Lokales Sprachmodell (offline)", GroupName = "engine", IsChecked = settings.EnhanceEngine != "cli" };
        var cli = new RadioButton { Content = "Eigener Befehl (z. B. Claude Code, Codex, Gemini)", GroupName = "engine", IsChecked = settings.EnhanceEngine == "cli" };
        var localPanel = new StackPanel { Spacing = 6, Margin = new Thickness(28, 0, 0, 0) };
        var cliPanel = new StackPanel { Spacing = 6, Margin = new Thickness(28, 0, 0, 0) };
        void RefreshEngine()
        {
            localPanel.IsVisible = local.IsChecked == true;
            cliPanel.IsVisible = cli.IsChecked == true;
        }
        local.IsCheckedChanged += (_, _) => { if (local.IsChecked == true) { settings.EnhanceEngine = "local"; settings.Save(); } RefreshEngine(); };
        cli.IsCheckedChanged += (_, _) => { if (cli.IsChecked == true) { settings.EnhanceEngine = "cli"; settings.Save(); } RefreshEngine(); };
        var modelLabel = Hint(Enhancer.LocalModel(settings) is { } lm ? "Aktiv: " + Path.GetFileNameWithoutExtension(lm) : "Noch kein Sprachmodell geladen");
        var llmBtn = new Button { Content = "Sprachmodelle …", Classes = { "chip" } };
        llmBtn.Click += (_, _) => { panelBack = ShowSettings; ShowLlmPicker(); };
        localPanel.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Children = { llmBtn, modelLabel.With(l => l.VerticalAlignment = VerticalAlignment.Center) } });
        localPanel.Children.Add(Toggle("Modell im Speicher behalten", "Schneller bei häufiger Nutzung, belegt aber dauerhaft 1–3 GB Arbeitsspeicher", settings.LlmKeepLoaded, v => { settings.LlmKeepLoaded = v; if (!v) LlmClient.Shared.Kill(); }));
        if (!File.Exists(LlmClient.RunnerPath)) localPanel.Children.Add(Hint("⚠ engine/llm/llm-runner.exe fehlt in diesem Paket."));

        var cmdBox = new TextBox { Text = settings.CliCommand, Watermark = "Befehl, z. B. claude -p --output-format text", MinWidth = 400 };
        cmdBox.LostFocus += (_, _) => { settings.CliCommand = cmdBox.Text?.Trim() ?? ""; settings.Save(); };
        var tplBox = new ComboBox { PlaceholderText = "Vorlage laden", CornerRadius = new CornerRadius(14) };
        foreach (var t in CliEnhancer.Templates) tplBox.Items.Add(t.Name);
        tplBox.SelectionChanged += (_, _) =>
        {
            if (tplBox.SelectedIndex < 0) return;
            cmdBox.Text = CliEnhancer.Templates[tplBox.SelectedIndex].Command;
            settings.CliCommand = cmdBox.Text; settings.Save();
        };
        var timeout = new NumericUpDown { Minimum = 5, Maximum = 600, Value = settings.CliTimeoutSeconds, Width = 130, FormatString = "0" };
        timeout.ValueChanged += (_, _) => { settings.CliTimeoutSeconds = (int)(timeout.Value ?? 90); settings.Save(); };
        var testResult = Hint("");
        var test = new Button { Content = "Testen", Classes = { "chip" } };
        test.Click += async (_, _) =>
        {
            settings.CliCommand = cmdBox.Text?.Trim() ?? ""; settings.Save();
            test.IsEnabled = false; testResult.Text = "Teste …";
            var sw = System.Diagnostics.Stopwatch.StartNew();
            try
            {
                var o = await CliEnhancer.RunAsync(settings.CliCommand, Prompts.EnhanceSystem(settings, FormatMode.Standard, "de"),
                    "<dictation>\näh also das ist ein test ob der befehl funktioniert\n</dictation>", settings.CliTimeoutSeconds, CancellationToken.None);
                testResult.Text = $"✓ Befehl funktioniert ({sw.Elapsed.TotalSeconds:F1} s): „{(o.Length > 120 ? o[..120] + "…" : o)}“";
            }
            catch (Exception ex) { testResult.Text = "Befehl fehlgeschlagen: " + ex.Message; }
            test.IsEnabled = true;
        };
        cliPanel.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Children = { tplBox, cmdBox } });
        cliPanel.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Children = { new TextBlock { Text = "Timeout (s):", VerticalAlignment = VerticalAlignment.Center }, timeout, test } });
        cliPanel.Children.Add(testResult);
        cliPanel.Children.Add(Hint("Der Prompt kommt auf stdin und steht zusätzlich in WHISPER_FULL_PROMPT, WHISPER_SYSTEM_PROMPT und WHISPER_USER_PROMPT. Die Antwort ist die Ausgabe des Befehls."));
        cliPanel.Children.Add(Hint("⚠ Mit einer Cloud-CLI (Claude, Codex, Gemini) gehen Transkript und Formatierungs-Kontext an den jeweiligen Anbieter — das Diktat bleibt dann nicht mehr auf dem Rechner."));
        enhCard.Children.Add(local); enhCard.Children.Add(localPanel); enhCard.Children.Add(cli); enhCard.Children.Add(cliPanel);
        RefreshEngine();
        enhCard.Children.Add(Toggle("Diktate automatisch verbessern", "Gilt für den normalen Diktat-Hotkey und die Aufnahme im Fenster. Füllwörter raus, Zeichensetzung, Formatierung — dauert ein paar Sekunden länger.", settings.EnhanceAuto, v => settings.EnhanceAuto = v));
        enhCard.Children.Add(Toggle("App-Kontext verwenden", "Erkennt Chat, E-Mail, Notiz, Code … am Zielprogramm (im Browser am Fenstertitel) und formatiert passend. Gespeichert wird davon nichts.", settings.AppContext, v => settings.AppContext = v));
        var profilesBtn = new Button { Content = "App-Profile …", Classes = { "chip" } };
        profilesBtn.Click += (_, _) => { panelBack = ShowSettings; ShowProfiles(); };
        var promptBtn = new Button { Content = settings.EnhancePrompt == null ? "System-Prompt (Standard) …" : "System-Prompt (eigene Regeln) …", Classes = { "chip" } };
        promptBtn.Click += async (_, _) => { await EditPrompt(); promptBtn.Content = settings.EnhancePrompt == null ? "System-Prompt (Standard) …" : "System-Prompt (eigene Regeln) …"; };
        enhCard.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Children = { profilesBtn, promptBtn } });
        p.Children.Add(Card(enhCard));

        // Smart Routing (TypeSafe Jev)
        p.Children.Add(Section("Smart Routing (TypeSafe Jev.ai)"));
        p.Children.Add(Card(BuildJevSettings()));

        // Meeting
        p.Children.Add(Section("Meeting"));
        var meetCard = new StackPanel { Spacing = 8 };
        meetCard.Children.Add(Toggle("Notizen automatisch schreiben", "Nach dem Meeting: Zusammenfassung, Entscheidungen, Aufgaben, offene Fragen (braucht Enhance)", settings.MeetingNotesAuto, v => settings.MeetingNotesAuto = v));
        var meetModels = new Button { Content = "Sprechererkennung laden …", Classes = { "chip" } };
        meetModels.Click += (_, _) => { panelBack = ShowSettings; ShowMeetingModels(); };
        meetCard.Children.Add(meetModels);
        p.Children.Add(Card(meetCard));

        ShowPanel("Einstellungen", "Änderungen gelten sofort", Scroll(p));
    }

    // ---------- Smart Routing (Jev) ----------

    private string JevModelOf() =>
        string.IsNullOrWhiteSpace(settings.JevModel) ? JevQuestions.DefaultModel : settings.JevModel.Trim();

    /// Smart Routing: Schalter, API-Key (maskiert), Verbindungstest, Modell,
    /// Projekt-Tags. Ohne Key bleibt der Schalter gesperrt — die App
    /// funktioniert dann wie bisher offline.
    private Control BuildJevSettings()
    {
        var card = new StackPanel { Spacing = 8 };

        var testResult = Hint("");
        var enable = new CheckBox { Content = "Jev Post-Processing aktivieren", IsChecked = settings.JevEnabled, FontWeight = FontWeight.Medium };
        var keyBox = new TextBox { Text = settings.JevApiKey, Watermark = "TypeSafe API Key", PasswordChar = '•', MinWidth = 360 };
        void SyncEnable()
        {
            enable.IsEnabled = !string.IsNullOrWhiteSpace(keyBox.Text);
            if (!enable.IsEnabled && enable.IsChecked == true)
            {
                enable.IsChecked = false;
                settings.JevEnabled = false;
                settings.Save();
            }
        }
        keyBox.TextChanged += (_, _) =>
        {
            settings.JevApiKey = (keyBox.Text ?? "").Trim();
            settings.Save();
            testResult.Text = "";
            SyncEnable();
        };
        enable.IsCheckedChanged += (_, _) => { settings.JevEnabled = enable.IsChecked == true; settings.Save(); };
        SyncEnable();

        var test = new Button { Content = "Verbindung testen", Classes = { "chip" } };
        test.Click += async (_, _) =>
        {
            var key = (keyBox.Text ?? "").Trim();
            if (key.Length == 0) return;
            test.IsEnabled = false;
            testResult.Text = "Teste …";
            var r = await Jev.TestConnectionAsync(key, JevModelOf());
            testResult.Text = r switch
            {
                JevClient.Result.Success => "✓ Verbindung steht",
                JevClient.Result.Failure f => f.Message,
                _ => "",
            };
            test.IsEnabled = true;
        };

        var modelBox = new TextBox { Text = JevModelOf(), Watermark = "jev-1.13.0", MinWidth = 240 };
        modelBox.LostFocus += (_, _) => { settings.JevModel = (modelBox.Text ?? "").Trim(); settings.Save(); };

        var tagsBtn = new Button { Content = $"Projekt-Tags ({Jev.ProjectTags(settings).Count}) …", Classes = { "chip" } };
        tagsBtn.Click += async (_, _) =>
        {
            await ShowProjectTags();
            tagsBtn.Content = $"Projekt-Tags ({Jev.ProjectTags(settings).Count}) …";
        };

        card.Children.Add(enable);
        card.Children.Add(Hint("Ordnet Aufnahmen nach Kategorie, Projekt und Dringlichkeit ein und lässt „Enhance“ nur laufen, wenn der Text es braucht. Sendet den Transkripttext an api.typesafe.ai; ohne Internet oder Key bleibt alles wie gehabt."));
        card.Children.Add(keyBox);
        card.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10, Children = { test, testResult.With(t => t.VerticalAlignment = VerticalAlignment.Center) } });
        card.Children.Add(modelBox);
        card.Children.Add(Hint($"Gepinnt, damit sich Schwellenwerte bei Modell-Updates nicht verschieben. Standard: {JevQuestions.DefaultModel}"));
        card.Children.Add(tagsBtn);
        return card;
    }

    /// Eigene Projekte (Schlüssel + kurze Beschreibung); sie landen als
    /// Kriterien von `project_context` in jeder Anfrage. „unassigned“ ergänzt
    /// die App selbst.
    private async Task ShowProjectTags()
    {
        var tags = new List<JevProjectTag>(Jev.ProjectTags(settings));
        var tcs = new TaskCompletionSource<bool>();
        var w = new Window
        {
            Title = "Projekt-Tags", Width = 560, SizeToContent = SizeToContent.Height, CanResize = false,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Background = BrandBrush("BrandSurfaceBrush", Brushes.White),
        };

        var list = new StackPanel { Spacing = 6 };
        var add = new Button { Content = "Tag hinzufügen", Classes = { "chip" } };
        void Render()
        {
            list.Children.Clear();
            foreach (var t in tags)
            {
                var tag = t;
                var del = new Button { Content = "✕", Classes = { "chip" }, Foreground = ErrorBrush, Padding = new Thickness(8, 3) };
                del.Click += (_, _) => { tags.Remove(tag); Render(); };
                list.Children.Add(new DockPanel
                {
                    Children =
                    {
                        del.With(d => DockPanel.SetDock(d, Dock.Right)),
                        new StackPanel
                        {
                            Spacing = 1,
                            Children =
                            {
                                new TextBlock { Text = tag.Key, FontWeight = FontWeight.Medium },
                                new TextBlock { Text = tag.Description, FontSize = 12, Foreground = SubtleBrush, TextWrapping = TextWrapping.Wrap },
                            },
                        },
                    },
                });
            }
            add.IsEnabled = CanAdd();
        }

        var keyBox = new TextBox { Watermark = "Schlüssel (z. B. whisper_app)", MinWidth = 240 };
        var descBox = new TextBox { Watermark = "Woran Jev das Projekt erkennt (z. B. Server, Docker, Netzwerk)", TextWrapping = TextWrapping.Wrap };
        bool CanAdd() => (keyBox.Text ?? "").Trim().Length > 0 &&
                         (keyBox.Text ?? "").Trim() != JevDecision.ProjectUnassigned &&
                         tags.Count < JevDecision.MaxTags;
        void Add()
        {
            if (!CanAdd()) return;
            var key = (keyBox.Text ?? "").Trim();
            tags = JevDecision.SanitizeTags(tags.Where(t => t.Key != key).Append(new JevProjectTag(key, (descBox.Text ?? "").Trim())));
            keyBox.Text = "";
            descBox.Text = "";
            Render();
        }
        add.Click += (_, _) => Add();
        keyBox.KeyDown += (_, e) => { if (e.Key == Key.Enter) Add(); };

        var save = new Button { Content = "Speichern", Classes = { "primaryAction" } };
        save.Click += (_, _) => { settings.JevProjectTags = JevDecision.SanitizeTags(tags); settings.Save(); tcs.TrySetResult(true); w.Close(); };
        var standard = new Button { Content = "Standard", Classes = { "chip" } };
        standard.Click += (_, _) => { tags = new List<JevProjectTag>(JevQuestions.DefaultTags); Render(); };
        var cancel = new Button { Content = "Abbrechen", Classes = { "chip" }, IsCancel = true };
        cancel.Click += (_, _) => { tcs.TrySetResult(false); w.Close(); };
        w.Closed += (_, _) => tcs.TrySetResult(false);

        w.Content = new Border
        {
            Margin = new Thickness(16),
            Classes = { "card" },
            Child = new StackPanel
            {
                Spacing = 12,
                Children =
                {
                    new TextBlock { Text = "Projekt-Tags", FontSize = 16, FontWeight = FontWeight.SemiBold },
                    Hint("Schlüssel (z. B. whisper_app) und eine kurze Beschreibung, woran Jev das Projekt erkennt."),
                    new ScrollViewer { Content = list, MaxHeight = 300, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled },
                    keyBox,
                    descBox,
                    new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Children = { add, standard } },
                    new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, HorizontalAlignment = HorizontalAlignment.Right, Children = { cancel, save } },
                },
            },
        };
        Render();
        await w.ShowDialog(this);
        await tcs.Task;
    }

    private async Task EditPrompt()
    {
        var def = Prompts.DefaultEnhanceRules();
        var box = new TextBox { Text = settings.EnhancePrompt ?? def, AcceptsReturn = true, TextWrapping = TextWrapping.Wrap, Height = 320, FontSize = 12 };
        var w = new Window
        {
            Title = "System-Prompt", Width = 640, SizeToContent = SizeToContent.Height,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Background = BrandBrush("BrandSurfaceBrush", Brushes.White),
            Icon = AppIcon.Get(),
        };
        var save = new Button { Content = "Speichern", Classes = { "primaryAction" } };
        var reset = new Button { Content = "Standard", Classes = { "chip" } };
        var cancel = new Button { Content = "Abbrechen", Classes = { "chip" }, IsCancel = true };
        save.Click += (_, _) =>
        {
            var t = box.Text?.Trim() ?? "";
            settings.EnhancePrompt = t.Length == 0 || t == def.Trim() ? null : t;
            settings.Save();
            w.Close();
        };
        reset.Click += (_, _) => box.Text = def;
        cancel.Click += (_, _) => w.Close();
        w.Content = new Border
        {
            Margin = new Thickness(16),
            Classes = { "card" },
            Child = new StackPanel
            {
                Spacing = 12,
                Children =
                {
                    Hint("Regeln, nach denen Enhance Diktate überarbeitet. Der Standard ist englisch, damit das Modell ihn zuverlässig befolgt — eigene Regeln gehen in jeder Sprache. Sprache, Modus, Wortliste und die reine Textausgabe hängt die App selbst an."),
                    box,
                    new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, HorizontalAlignment = HorizontalAlignment.Right, Children = { reset, cancel, save } },
                },
            },
        };
        await w.ShowDialog(this);
    }

    // ---------- Wörterbuch (mit Live-Filterung) ----------

    private void OnOpenDictionary(object? sender, RoutedEventArgs e) => ShowDictionary();

    private void ShowDictionary(int tab = 0)
    {
        var tabs = new TabControl { SelectedIndex = tab };
        tabs.Items.Add(new TabItem { Header = "Eigene Wörter", Content = WordsTab() });
        tabs.Items.Add(new TabItem { Header = "Ersetzungen", Content = ReplacementsTab() });
        tabs.Items.Add(new TabItem { Header = "Snippets", Content = SnippetsTab() });
        ShowPanel("Wörterbuch", "Gilt für Diktat, Aufnahme und Dateien", tabs);
    }

    private Control WordsTab()
    {
        var list = new WrapPanel { ItemSpacing = 6, LineSpacing = 6 };
        var count = Hint("");
        var filterBox = new TextBox { Watermark = "Wörter filtern …", Width = 220 };
        // Leeres Wörterbuch: Standardliste (häufige englische Begriffe) nachladen
        var restore = new Button { Content = "Häufige englische Begriffe hinzufügen", Classes = { "chip" } };
        restore.Click += (_, _) =>
        {
            foreach (var w in VocabularyDefaults.Words)
                if (!settings.Vocabulary.Any(x => x.Equals(w, StringComparison.OrdinalIgnoreCase)))
                    settings.Vocabulary.Add(w);
            settings.Save();
            Refresh();
        };

        void Refresh()
        {
            var q = filterBox.Text?.Trim() ?? "";
            list.Children.Clear();
            var filtered = settings.Vocabulary
                .Where(w => q.Length == 0 || w.Contains(q, StringComparison.OrdinalIgnoreCase))
                .ToList();
            foreach (var w in filtered)
            {
                var b = new Button { Content = w + "  ✕", Classes = { "chip" } };
                ToolTip.SetTip(b, "Wort entfernen");
                b.Click += (_, _) => { settings.Vocabulary.Remove(w); settings.Save(); Refresh(); };
                list.Children.Add(b);
            }
            restore.IsVisible = settings.Vocabulary.Count == 0 && VocabularyDefaults.Words.Count > 0;
            count.Text = q.Length == 0
                ? $"{settings.Vocabulary.Count} Wörter"
                : $"{filtered.Count} von {settings.Vocabulary.Count} Wörtern";
        }
        filterBox.TextChanged += (_, _) => Refresh();
        Refresh();

        var input = new TextBox { Watermark = "Neues Wort hinzufügen …", MinWidth = 280 };
        var add = new Button { Content = "+ Hinzufügen", Classes = { "primaryAction" }, IsDefault = true };
        void Add()
        {
            var w = input.Text?.Trim() ?? "";
            if (w.Length == 0 || settings.Vocabulary.Any(x => x.Equals(w, StringComparison.OrdinalIgnoreCase))) return;
            settings.Vocabulary.Insert(0, w);
            settings.Save();
            input.Text = "";
            Refresh();
        }
        add.Click += (_, _) => Add();

        return Scroll(new StackPanel
        {
            Spacing = 12, Margin = new Thickness(0, 12, 12, 12),
            Children =
            {
                Card(new StackPanel
                {
                    Spacing = 10,
                    Children =
                    {
                        Hint("Namen und Fachbegriffe in der gewünschten Schreibweise. Whisper bekommt die Liste vorab und schreibt sie dann eher richtig; ähnlich Klingendes („Scheiße Wasser“) wird danach korrigiert. Enhance hält sich ebenfalls daran."),
                        new WrapPanel { ItemSpacing = 8, LineSpacing = 8, Children = { input, add, filterBox } },
                    },
                }),
                new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12, Children = { count.With(c => c.VerticalAlignment = VerticalAlignment.Center), restore } },
                list,
            },
        });
    }

    private Control ReplacementsTab()
    {
        var list = new StackPanel { Spacing = 8 };
        void Refresh()
        {
            list.Children.Clear();
            if (settings.Replacements.Count == 0) list.Children.Add(Hint("Noch keine Regeln. Typische Kandidaten: Abkürzungen, feste Schreibweisen, wiederkehrende Fehler."));
            foreach (var r in settings.Replacements.ToList())
            {
                var del = new Button { Content = "✕  Löschen", Classes = { "chip" }, Foreground = ErrorBrush };
                del.Click += (_, _) => { settings.Replacements.Remove(r); settings.Save(); Refresh(); };
                list.Children.Add(Card(new DockPanel
                {
                    Children =
                    {
                        del.With(d => DockPanel.SetDock(d, Dock.Right)),
                        new StackPanel
                        {
                            Orientation = Orientation.Horizontal, Spacing = 10, VerticalAlignment = VerticalAlignment.Center,
                            Children =
                            {
                                new TextBlock { Text = r.From, FontWeight = FontWeight.Medium },
                                new TextBlock { Text = "→", Foreground = SubtleBrush },
                                new TextBlock { Text = r.To.Length == 0 ? "(entfernen)" : r.To, Foreground = PrimaryBrush, FontWeight = FontWeight.SemiBold },
                            },
                        },
                    },
                }));
            }
        }
        Refresh();
        var from = new TextBox { Watermark = "Erkannt wird (z. B. Scheißewasser)", MinWidth = 220 };
        var to = new TextBox { Watermark = "Ersetzen durch (z. B. Scheisssewasser)", MinWidth = 220 };
        var add = new Button { Content = "+ Regel hinzufügen", Classes = { "primaryAction" } };
        add.Click += (_, _) =>
        {
            var f = from.Text?.Trim() ?? "";
            if (f.Length == 0) return;
            settings.Replacements.RemoveAll(x => x.From.Equals(f, StringComparison.OrdinalIgnoreCase));
            settings.Replacements.Add(new ReplacementRule(f, to.Text?.Trim() ?? ""));
            settings.Save(); from.Text = ""; to.Text = ""; Refresh();
        };
        return Scroll(new StackPanel
        {
            Spacing = 12, Margin = new Thickness(0, 12, 12, 12),
            Children =
            {
                Card(new StackPanel
                {
                    Spacing = 10,
                    Children =
                    {
                        Hint("Gilt für ganze Wörter, Groß-/Kleinschreibung egal. Für Namen sind „Eigene Wörter“ meist bequemer."),
                        new WrapPanel { ItemSpacing = 8, LineSpacing = 8, Children = { from, to, add } },
                    },
                }),
                list,
            },
        });
    }

    private Control SnippetsTab()
    {
        var list = new StackPanel { Spacing = 8 };
        var trigger = new TextBox { Watermark = "Trigger-Phrase (z. B. meine Adresse)", MinWidth = 260 };
        var text = new TextBox { Watermark = "Einzufügender Text", AcceptsReturn = true, Height = 76, TextWrapping = TextWrapping.Wrap };
        Snippet? editing = null;
        void Refresh()
        {
            list.Children.Clear();
            if (settings.Snippets.Count == 0) list.Children.Add(Hint("Noch keine Snippets. Praktisch für Adresse, Signatur, Links oder Textbausteine."));
            foreach (var sn in settings.Snippets.ToList())
            {
                var edit = new Button { Content = "Bearbeiten", Classes = { "chip" } };
                edit.Click += (_, _) => { editing = sn; trigger.Text = sn.Trigger; text.Text = sn.Text; };
                var del = new Button { Content = "✕", Classes = { "chip" }, Foreground = ErrorBrush };
                del.Click += (_, _) => { settings.Snippets.Remove(sn); settings.Save(); Refresh(); };
                list.Children.Add(Card(new DockPanel
                {
                    Children =
                    {
                        new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6, Children = { edit, del } }.With(x => DockPanel.SetDock(x, Dock.Right)),
                        new StackPanel { Spacing = 2, Children = { new TextBlock { Text = "„" + sn.Trigger + "“", FontWeight = FontWeight.SemiBold, Foreground = PrimaryBrush }, Hint(sn.Text.Length > 140 ? sn.Text[..140] + " …" : sn.Text) } },
                    },
                }));
            }
        }
        Refresh();
        var save = new Button { Content = "+ Snippet speichern", Classes = { "primaryAction" }, HorizontalAlignment = HorizontalAlignment.Left };
        save.Click += (_, _) =>
        {
            var t = trigger.Text?.Trim() ?? "";
            if (t.Length == 0 || string.IsNullOrEmpty(text.Text)) return;
            settings.Snippets.RemoveAll(x => x == editing || x.Trigger.Equals(t, StringComparison.OrdinalIgnoreCase));
            settings.Snippets.Add(new Snippet(t, text.Text));
            settings.Save(); editing = null; trigger.Text = ""; text.Text = ""; Refresh();
        };
        return Scroll(new StackPanel
        {
            Spacing = 12, Margin = new Thickness(0, 12, 12, 12),
            Children =
            {
                Card(new StackPanel
                {
                    Spacing = 10,
                    Children =
                    {
                        Hint("Sagt man nur die Trigger-Phrase — ohne weitere Worte —, wird stattdessen der hinterlegte Text eingefügt."),
                        trigger, text, save,
                    },
                }),
                list,
            },
        });
    }

    // ---------- Statistik (mit responsivem UniformGrid-Diagramm) ----------

    private void OnOpenStats(object? sender, RoutedEventArgs e) => ShowStats(30, null);

    private void ShowStats(int days, string? source)
    {
        var all = HistoryStore.Load();
        var st = Stats.Compute(all, days, source, DateTimeOffset.Now);
        var p = new StackPanel { Spacing = 12, Margin = new Thickness(0, 0, 12, 12) };
        var range = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
        foreach (var (d, label) in new[] { (7, "7 Tage"), (30, "30 Tage"), (0, "Gesamt") })
        {
            var b = new ToggleButton { Content = label, IsChecked = d == days, CornerRadius = new CornerRadius(18), Padding = new Thickness(12, 6) };
            b.Click += (_, _) => ShowStats(d, source);
            range.Children.Add(b);
        }
        var src = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
        foreach (var (s, label) in new (string?, string)[] { (null, "Alle"), ("dictation", "Diktat"), ("file", "Datei"), ("meeting", "Meeting") })
        {
            var b = new ToggleButton { Content = label, IsChecked = s == source, CornerRadius = new CornerRadius(18), Padding = new Thickness(12, 6) };
            b.Click += (_, _) => ShowStats(days, s);
            src.Children.Add(b);
        }
        p.Children.Add(new WrapPanel { ItemSpacing = 16, LineSpacing = 6, Children = { range, src } });
        if (!settings.HistoryEnabled) p.Children.Add(Hint("Der Verlauf ist abgeschaltet — neue Transkriptionen fließen nicht ein."));
        if (st.Count == 0)
        {
            p.Children.Add(Card(Hint("Noch keine Daten für diese Auswahl — starte eine Transkription, sie erscheint dann hier.")));
            ShowPanel("Statistik", null, Scroll(p));
            return;
        }
        Control Tile(string label, string value) => Card(new StackPanel
        {
            MinWidth = 135, Spacing = 4,
            Children = { Hint(label), new TextBlock { Text = value, FontSize = 22, FontWeight = FontWeight.Bold, Foreground = PrimaryBrush } },
        });
        string Hours(float sec) { int m = (int)(sec / 60); return m < 60 ? $"{m} min" : $"{m / 60} h {m % 60} min"; }
        p.Children.Add(new WrapPanel
        {
            ItemSpacing = 10, LineSpacing = 10,
            Children =
            {
                Tile("Transkriptionen", st.Count.ToString()), Tile("Wörter", st.Words.ToString()),
                Tile("Gesprochen", Hours(st.AudioSeconds)), Tile("Ø Dauer", $"{st.AvgSeconds:F1} s"),
                Tile("Zeit gespart", Hours(st.SavedSeconds)),
            },
        });
        p.Children.Add(Hint($"„Zeit gespart“ rechnet mit {Stats.TypingWpm:F0} getippten Wörtern pro Minute."));
        // Responsives Balkendiagramm: UniformGrid skaliert automatisch mit der Fensterbreite
        int max = Math.Max(1, st.PerDay.Max(x => x.Words));
        var bars = new UniformGrid { Rows = 1, Columns = Math.Max(1, st.PerDay.Count), Height = 140, Margin = new Thickness(0, 8, 0, 4) };
        foreach (var (label, words) in st.PerDay)
        {
            double ratio = (double)words / max;
            var track = new Grid
            {
                Margin = new Thickness(2, 0),
                VerticalAlignment = VerticalAlignment.Stretch,
                Children =
                {
                    new Border
                    {
                        Background = CardBorderBrush, Opacity = 0.35,
                        CornerRadius = new CornerRadius(4),
                    },
                    new Border
                    {
                        Height = words > 0 ? Math.Max(4, 140.0 * ratio) : 0,
                        Background = PrimaryBrush,
                        CornerRadius = new CornerRadius(4),
                        VerticalAlignment = VerticalAlignment.Bottom,
                    },
                },
            };
            ToolTip.SetTip(track, $"{label}: {words} Wörter");
            bars.Children.Add(track);
        }
        p.Children.Add(Card(new StackPanel
        {
            Spacing = 6,
            Children =
            {
                new TextBlock { Text = "Wörter pro Tag", FontWeight = FontWeight.SemiBold },
                bars,
                new DockPanel { Children = { Hint(st.PerDay[^1].Label).With(h => DockPanel.SetDock(h, Dock.Right)), Hint(st.PerDay[0].Label + $"   (max. {max})") } },
            },
        }));
        if (st.Languages.Count > 0)
        {
            var langs = new StackPanel { Spacing = 6, Children = { new TextBlock { Text = "Sprachen", FontWeight = FontWeight.SemiBold } } };
            foreach (var (l, n) in st.Languages)
            {
                langs.Children.Add(new DockPanel
                {
                    Children =
                    {
                        new TextBlock { Text = $"{n}×", Foreground = SubtleBrush }.With(t => DockPanel.SetDock(t, Dock.Right)),
                        new TextBlock { Text = l switch { "de" => "Deutsch", "en" => "Englisch", "" => "Mehrsprachig (Parakeet)", _ => l } },
                    },
                });
            }
            p.Children.Add(Card(langs));
        }
        ShowPanel("Statistik", null, Scroll(p));
    }

    // ---------- Verlauf ----------

    private void OnOpenHistory(object? sender, RoutedEventArgs e) => ShowHistory("", 0, false);

    private void ShowHistory(string query, int days, bool oldestFirst)
    {
        var all = HistoryStore.Load();
        long since = days > 0 ? DateTimeOffset.UtcNow.AddDays(-days).ToUnixTimeMilliseconds() : 0;
        var shown = all.Where(x => x.TimeMs >= since && (query.Length == 0 || x.Text.Contains(query, StringComparison.OrdinalIgnoreCase))).ToList();
        if (oldestFirst) shown.Reverse();

        var search = new TextBox { Text = query, Watermark = "Im Verlauf suchen …", MinWidth = 240 };
        search.KeyUp += (_, ev) => { if (ev.Key == Avalonia.Input.Key.Enter) ShowHistory(search.Text?.Trim() ?? "", days, oldestFirst); };
        var go = new Button { Content = "Suchen", Classes = { "chip" } };
        go.Click += (_, _) => ShowHistory(search.Text?.Trim() ?? "", days, oldestFirst);
        var range = new ComboBox { Items = { "Alle", "7 Tage", "30 Tage" }, SelectedIndex = days switch { 7 => 1, 30 => 2, _ => 0 }, CornerRadius = new CornerRadius(18) };
        range.SelectionChanged += (_, _) => ShowHistory(query, range.SelectedIndex switch { 1 => 7, 2 => 30, _ => 0 }, oldestFirst);
        var sort = new Button { Content = oldestFirst ? "↑ Älteste zuerst" : "↓ Neueste zuerst", Classes = { "chip" } };
        sort.Click += (_, _) => ShowHistory(query, days, !oldestFirst);
        var export = new Button { Content = "Exportieren …", Classes = { "chip" } };
        export.Click += async (_, _) =>
        {
            var f = await StorageProvider.SaveFilePickerAsync(new FilePickerSaveOptions
            {
                SuggestedFileName = "Whisper-Verlauf.txt", DefaultExtension = "txt",
            });
            var path = f?.TryGetLocalPath();
            if (path == null) return;
            File.WriteAllText(path, string.Join("\n\n", shown.Select(x => $"{x.DateText} · {x.Model} · {x.Language}\n{x.Text}")));
        };
        var clear = new Button { Content = "Alle löschen", Classes = { "chip" }, Foreground = ErrorBrush };
        clear.Click += async (_, _) =>
        {
            if (await Confirm("Verlauf löschen?", $"Alle {all.Count} Einträge werden entfernt. Das lässt sich nicht rückgängig machen."))
            {
                HistoryStore.Clear();
                ShowHistory("", 0, false);
            }
        };

        var list = new StackPanel { Spacing = 10 };
        if (!settings.HistoryEnabled) list.Children.Add(Hint("Der Verlauf ist abgeschaltet (Einstellungen) — neue Transkriptionen werden nicht gespeichert."));
        if (shown.Count == 0) list.Children.Add(Card(Hint(all.Count == 0 ? "Noch keine Einträge — jede Transkription landet automatisch hier." : "Keine Treffer für diese Suche.")));
        foreach (var x in shown.Take(300))
        {
            var copy = new Button { Content = "Kopieren", Classes = { "chip" }, FontSize = 12 };
            copy.Click += async (_, _) => { if (Clipboard != null) await Clipboard.SetTextAsync(x.Text); };
            var del = new Button { Content = "✕", Classes = { "chip" }, FontSize = 12, Foreground = ErrorBrush };
            del.Click += (_, _) => { HistoryStore.Delete(x); ShowHistory(query, days, oldestFirst); };
            var src = x.Source switch { "dictation" => "Diktat", "file" => "Datei", "meeting" => "Meeting", "selection" => "Umgeformt", _ => "Aufnahme" };
            list.Children.Add(Card(new StackPanel
            {
                Spacing = 6,
                Children =
                {
                    new DockPanel
                    {
                        Children =
                        {
                            new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6, Children = { copy, del } }.With(b => DockPanel.SetDock(b, Dock.Right)),
                            new StackPanel
                            {
                                Children =
                                {
                                    new TextBlock { Text = x.DateText, FontWeight = FontWeight.SemiBold },
                                    Hint(string.Join(" · ", new[] { src, x.Model, x.Language, x.AudioSeconds > 0 ? $"{x.AudioSeconds:F1} s Audio" : "" }.Where(t => t.Length > 0))),
                                },
                            },
                        },
                    },
                    new SelectableTextBlock { Text = x.Text, TextWrapping = TextWrapping.Wrap, MaxHeight = 140 },
                },
            }));
        }
        var content = new DockPanel
        {
            Children =
            {
                new WrapPanel { ItemSpacing = 8, LineSpacing = 6, Margin = new Thickness(0, 0, 0, 12), Children = { search, go, range, sort, export, clear } }
                    .With(w => DockPanel.SetDock(w, Dock.Top)),
                Scroll(list),
            },
        };
        ShowPanel("Verlauf", query.Length > 0 || days > 0 ? $"{shown.Count} von {all.Count} Einträgen" : $"{all.Count} Einträge", content);
    }

    // ---------- Sprachmodelle (Enhance) ----------

    private async void ShowLlmPicker()
    {
        var list = new StackPanel { Spacing = 10, Margin = new Thickness(0, 0, 12, 12) };
        var status = Hint("Frage Server nach Sprachmodellen …");
        list.Children.Add(Hint("Für Enhance, Umformen und Meeting-Notizen. Läuft komplett auf diesem Rechner. Größere Modelle formulieren deutlich besser — Empfehlung: Qwen3 4B."));
        list.Children.Add(status);
        ShowPanel("Sprachmodelle", "Download einmalig, danach offline", Scroll(list));
        List<ModelInfo> catalog;
        try { catalog = (await ModelRegistry.FetchCatalog()).Llm; status.Text = ""; }
        catch (Exception ex) { status.Text = "Server nicht erreichbar: " + ex.Message; return; }
        void Build()
        {
            while (list.Children.Count > 2) list.Children.RemoveAt(2);
            foreach (var info in catalog)
            {
                bool isLocal = File.Exists(Path.Combine(Enhancer.LlmDir, info.File));
                bool active = settings.LlmModel == info.File && isLocal;
                var card = new StackPanel { Spacing = 6 };
                card.Children.Add(new DockPanel
                {
                    Children =
                    {
                        new TextBlock { Text = info.SizeText, Foreground = SubtleBrush, FontSize = 12, VerticalAlignment = VerticalAlignment.Center }.With(s => DockPanel.SetDock(s, Dock.Right)),
                        new TextBlock { Text = info.Label, FontWeight = FontWeight.Bold, FontSize = 15 },
                    },
                });
                card.Children.Add(new TextBlock { Text = info.Tagline, Foreground = PrimaryBrush, FontSize = 12 });
                foreach (var pr in info.Pros) card.Children.Add(new TextBlock { Text = "✓  " + pr, FontSize = 12 });
                foreach (var c in info.Cons) card.Children.Add(new TextBlock { Text = "–  " + c, FontSize = 12, Foreground = SubtleBrush });
                var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Margin = new Thickness(0, 4, 0, 0) };
                if (active) row.Children.Add(new TextBlock { Text = "✓ Aktiv", FontWeight = FontWeight.Bold, Foreground = TertiaryBrush, VerticalAlignment = VerticalAlignment.Center });
                else if (isLocal)
                {
                    var act = new Button { Content = "Aktivieren", Classes = { "primaryAction" } };
                    act.Click += (_, _) => { LlmClient.Shared.Kill(); settings.LlmModel = info.File; settings.EnhanceEngine = "local"; settings.Save(); Build(); };
                    row.Children.Add(act);
                }
                else
                {
                    var dl = new Button { Content = $"⬇  Herunterladen ({info.SizeText})", Classes = { "chip" } };
                    var bar = new ProgressBar { Minimum = 0, Maximum = 1, Width = 220, IsVisible = false };
                    dl.Click += async (_, _) =>
                    {
                        dl.IsEnabled = false; bar.IsVisible = true;
                        try
                        {
                            await ModelRegistry.DownloadTo(info, Enhancer.LlmDir, new Progress<double>(v => bar.Value = v));
                            settings.LlmModel = info.File; settings.EnhanceEngine = "local"; settings.Save();
                        }
                        catch (Exception ex) { status.Text = "Download fehlgeschlagen: " + ex.Message; }
                        Build();
                    };
                    row.Children.Add(dl); row.Children.Add(bar);
                }
                if (isLocal)
                {
                    var del = new Button { Content = "Löschen", Classes = { "chip" }, Foreground = ErrorBrush };
                    del.Click += async (_, _) =>
                    {
                        if (!await Confirm("Sprachmodell löschen?", $"„{info.Label}“ wird vom Rechner entfernt und kann jederzeit neu geladen werden.")) return;
                        if (settings.LlmModel == info.File) { LlmClient.Shared.Kill(); settings.LlmModel = ""; settings.Save(); }
                        try { File.Delete(Path.Combine(Enhancer.LlmDir, info.File)); } catch { }
                        Build();
                    };
                    row.Children.Add(del);
                }
                card.Children.Add(row);
                list.Children.Add(Card(card));
            }
        }
        Build();
    }

    // ---------- App-Profile ----------

    private void ShowProfiles()
    {
        var list = new StackPanel { Spacing = 10, Margin = new Thickness(0, 0, 12, 12) };
        void Build()
        {
            list.Children.Clear();
            var running = new ComboBox { MinWidth = 220, PlaceholderText = "Laufendes Programm", CornerRadius = new CornerRadius(14) };
            foreach (var r in ForegroundInfo.RunningApps().Where(r => !settings.AppProfiles.ContainsKey(r))) running.Items.Add(r);
            var manual = new TextBox { Watermark = "oder Prozessname, z. B. slack", MinWidth = 200 };
            var add = new Button { Content = "+ Profil anlegen", Classes = { "primaryAction" } };
            add.Click += (_, _) =>
            {
                var name = (manual.Text?.Trim().ToLowerInvariant().Replace(".exe", "") is { Length: > 0 } m) ? m : running.SelectedItem as string;
                if (string.IsNullOrEmpty(name)) return;
                settings.AppProfiles[name] = new AppProfile { Mode = FormatMode.Standard };
                settings.Save(); Build();
            };
            list.Children.Add(Card(new StackPanel
            {
                Spacing = 10,
                Children =
                {
                    Hint("Ohne Profil erkennt die App den Modus selbst (Prozess, im Browser der Fenstertitel). Ausgeschlossene Programme bekommen rohes Diktat — kein Enhance, kein „Auswahl bearbeiten“."),
                    new WrapPanel { ItemSpacing = 8, LineSpacing = 8, Children = { running, manual, add } },
                },
            }));
            foreach (var (name, prof) in settings.AppProfiles.OrderBy(k => k.Key).ToList())
            {
                var mode = new ComboBox { MinWidth = 160, CornerRadius = new CornerRadius(14) };
                foreach (var m in FormatModes.Selectable) mode.Items.Add(m.Label());
                mode.SelectedIndex = Array.IndexOf(FormatModes.Selectable, prof.Mode);
                mode.SelectionChanged += (_, _) => { prof.Mode = FormatModes.Selectable[Math.Max(0, mode.SelectedIndex)]; settings.Save(); };
                var excl = new CheckBox { Content = "Ausschließen (rohes Diktat)", IsChecked = prof.Excluded };
                excl.IsCheckedChanged += (_, _) => { prof.Excluded = excl.IsChecked == true; settings.Save(); };
                var del = new Button { Content = "✕  Entfernen", Classes = { "chip" }, Foreground = ErrorBrush };
                del.Click += (_, _) => { settings.AppProfiles.Remove(name); settings.Save(); Build(); };
                list.Children.Add(Card(new DockPanel
                {
                    Children =
                    {
                        del.With(d => DockPanel.SetDock(d, Dock.Right)),
                        new WrapPanel
                        {
                            ItemSpacing = 12, LineSpacing = 8, VerticalAlignment = VerticalAlignment.Center,
                            Children = { new TextBlock { Text = name, MinWidth = 160, FontWeight = FontWeight.SemiBold, VerticalAlignment = VerticalAlignment.Center }, mode, excl },
                        },
                    },
                }));
            }
        }
        Build();
        ShowPanel("App-Profile", "Modus pro Programm festlegen oder Programme ausschließen", Scroll(list));
    }
}
