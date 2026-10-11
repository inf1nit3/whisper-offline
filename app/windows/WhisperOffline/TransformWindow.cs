using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;

namespace WhisperOffline;

/// Aktionen zum Umformen — gleiche Liste wie auf Android.
public static class TransformActions
{
    public static readonly (string Key, string Label)[] All =
    {
        ("improve", "✨ Verbessern"), ("spelling", "Rechtschreibung"), ("shorten", "Kürzen"),
        ("expand", "Ausführlicher"), ("professional", "Professioneller"), ("casual", "Lockerer"),
        ("bullets", "Aufzählung"), ("todo", "Aufgabenliste"), ("translate", "Übersetzen"),
    };

    /// Anweisung für eine Aktion; „Übersetzen“ geht in die jeweils andere Sprache.
    public static string Instruction(string key, string text) =>
        Prompts.Action(key, TextRules.GuessLanguage(text) == "en" ? "German" : "English");
}

/// Kleines Fenster „Auswahl bearbeiten“: Aktion wählen oder eigene Anweisung
/// tippen/diktieren. Liefert die Anweisung (oder null bei Abbruch).
public sealed class TransformWindow : Window
{
    private readonly TaskCompletionSource<(string Instruction, string Label)?> result = new();
    public Task<(string Instruction, string Label)?> Result => result.Task;

    /// [startDictation]/[stopDictation]: gesprochene Anweisung — erster Klick
    /// auf 🎙 startet die Aufnahme, zweiter beendet sie und transkribiert.
    public TransformWindow(string selectedText, Func<bool>? startDictation, Func<Task<string?>>? stopDictation)
    {
        Title = "Auswahl bearbeiten";
        Width = 460;
        SizeToContent = SizeToContent.Height;
        Topmost = true;
        CanResize = false;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;

        var preview = new TextBlock
        {
            Text = selectedText.Length > 240 ? selectedText[..240] + " …" : selectedText,
            TextWrapping = TextWrapping.Wrap, FontSize = 12, Foreground = Brushes.Gray, MaxLines = 4,
            TextTrimming = TextTrimming.CharacterEllipsis,
        };
        var actions = new WrapPanel { ItemSpacing = 6, LineSpacing = 6 };
        foreach (var (key, label) in TransformActions.All)
        {
            var b = new Button { Content = label };
            b.Click += (_, _) => Finish((TransformActions.Instruction(key, selectedText), label.TrimStart('✨', ' ')));
            actions.Children.Add(b);
        }
        var custom = new TextBox { Watermark = "Eigene Anweisung, z. B. „als freundliche Absage“", MaxLength = 1000 };
        var go = new Button { Content = "Anwenden", IsDefault = true };
        go.Click += (_, _) =>
        {
            if (!string.IsNullOrWhiteSpace(custom.Text)) Finish((custom.Text.Trim(), "Eigene Anweisung"));
        };
        var mic = new Button { Content = "🎙", IsVisible = startDictation != null && stopDictation != null };
        ToolTip.SetTip(mic, "Anweisung diktieren (nochmal klicken zum Beenden)");
        bool listening = false;
        mic.Click += async (_, _) =>
        {
            if (startDictation == null || stopDictation == null) return;
            if (!listening)
            {
                if (!startDictation()) { custom.Watermark = "Mikrofon nicht verfügbar"; return; }
                listening = true;
                mic.Content = "⏹";
                custom.Watermark = "Sprich deine Anweisung … dann ⏹";
                return;
            }
            listening = false;
            mic.IsEnabled = false;
            custom.Watermark = "Erkenne Anweisung …";
            var said = await stopDictation();
            mic.IsEnabled = true;
            mic.Content = "🎙";
            if (!string.IsNullOrWhiteSpace(said)) { custom.Text = said; Finish((said.Trim(), "Eigene Anweisung")); }
            else custom.Watermark = "Nicht verstanden — tippen oder nochmal sprechen";
        };
        var cancel = new Button { Content = "Abbrechen", IsCancel = true };
        cancel.Click += (_, _) => Finish(null);

        Content = new StackPanel
        {
            Margin = new Thickness(16),
            Spacing = 10,
            Children =
            {
                new TextBlock { Text = "Markierter Text", FontWeight = FontWeight.SemiBold },
                preview,
                actions,
                new DockPanel
                {
                    Children =
                    {
                        new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6, Children = { mic, go } }
                            .With(p => DockPanel.SetDock(p, Dock.Right)),
                        custom,
                    },
                },
                new TextBlock
                {
                    Text = "Das Ergebnis ersetzt die Auswahl. Mit Strg+Z in der Ziel-App rückgängig machen.",
                    FontSize = 11, Foreground = Brushes.Gray, TextWrapping = TextWrapping.Wrap,
                },
                cancel.With(c => c.HorizontalAlignment = HorizontalAlignment.Right),
            },
        };
        Closed += (_, _) => result.TrySetResult(null);
        Opened += (_, _) => custom.Focus();
    }

    private void Finish((string, string)? r)
    {
        result.TrySetResult(r);
        Close();
    }

    protected override void OnKeyDown(KeyEventArgs e)
    {
        if (e.Key == Key.Escape) { Finish(null); e.Handled = true; return; }
        base.OnKeyDown(e);
    }
}
