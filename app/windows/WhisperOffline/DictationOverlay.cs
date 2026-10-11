using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Threading;

namespace WhisperOffline;

/// Kleines Fenster unten in der Bildschirmmitte während eines Diktats:
/// Zustand, Aufnahmezeit, Pegel, Live-Text, Modus. Nimmt nie den Fokus —
/// der Text soll ja ins vorher aktive Fenster.
public sealed class DictationOverlay : Window
{
    private readonly TextBlock state = new() { FontWeight = FontWeight.SemiBold, Foreground = Brushes.White, FontSize = 14 };
    private readonly TextBlock detail = new() { Foreground = new SolidColorBrush(Color.FromRgb(0xCC, 0xCC, 0xCC)), FontSize = 12 };
    private readonly TextBlock live = new()
    {
        Foreground = Brushes.White, FontSize = 13, TextWrapping = TextWrapping.Wrap, MaxLines = 2,
        TextTrimming = TextTrimming.CharacterEllipsis, IsVisible = false,
    };
    private readonly ProgressBar level = new() { Minimum = 0, Maximum = 1, Height = 4, MinWidth = 0, Foreground = Brushes.IndianRed };
    private readonly DispatcherTimer timer = new() { Interval = TimeSpan.FromMilliseconds(80) };
    private DateTime started;
    private Func<float>? levelSource;

    public DictationOverlay()
    {
        SystemDecorations = SystemDecorations.None;
        Topmost = true;
        ShowActivated = false;
        ShowInTaskbar = false;
        CanResize = false;
        Width = 420;
        SizeToContent = SizeToContent.Height;
        Background = Brushes.Transparent;
        TransparencyLevelHint = new[] { WindowTransparencyLevel.Transparent };
        Content = new Border
        {
            Background = new SolidColorBrush(Color.FromArgb(235, 32, 34, 40)),
            CornerRadius = new CornerRadius(14),
            Padding = new Thickness(16, 10),
            Child = new StackPanel
            {
                Spacing = 4,
                Children =
                {
                    new DockPanel { Children = { detail.With(d => DockPanel.SetDock(d, Dock.Right)), state } },
                    level,
                    live,
                },
            },
        };
        timer.Tick += (_, _) =>
        {
            if (levelSource != null) level.Value = levelSource();
            if (started != default) detail.Text = (DateTime.Now - started).ToString(@"m\:ss") + (modeText.Length > 0 ? "  ·  " + modeText : "");
        };
    }

    private string modeText = "";

    /// Unten mittig auf dem Hauptbildschirm.
    private void Place()
    {
        var screen = Screens.Primary;
        if (screen == null) return;
        var area = screen.WorkingArea;
        double scale = screen.Scaling;
        int w = (int)(Width * scale);
        Position = new PixelPoint(area.X + (area.Width - w) / 2, area.Y + area.Height - (int)(140 * scale));
    }

    public void ShowRecording(Func<float> levelOf, string mode)
    {
        started = DateTime.Now;
        levelSource = levelOf;
        modeText = mode;
        state.Text = "🎙  Aufnahme";
        level.IsVisible = true;
        level.IsIndeterminate = false;
        live.Text = "";
        live.IsVisible = false;
        if (!IsVisible) { Place(); Show(); }
        timer.Start();
    }

    public void SetLive(string text)
    {
        live.Text = text.Length > 160 ? "…" + text[^160..] : text;
        live.IsVisible = text.Length > 0;
    }

    /// Transkribieren, Verbessern …: Pegel wird zum unbestimmten Balken.
    public void ShowWorking(string what)
    {
        state.Text = what;
        levelSource = null;
        started = default;
        detail.Text = modeText;
        level.IsVisible = true;
        level.IsIndeterminate = true;
        if (!IsVisible) { Place(); Show(); }
    }

    public void HideOverlay()
    {
        timer.Stop();
        levelSource = null;
        Hide();
    }
}

internal static class ControlExtensions
{
    public static T With<T>(this T c, Action<T> a) { a(c); return c; }
}
