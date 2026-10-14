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
    private readonly WaveformView level = new() { Height = 28 };
    private readonly DispatcherTimer timer = new() { Interval = TimeSpan.FromMilliseconds(80) };
    private DateTime started;

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

    public void ShowRecording(Func<float> levelOf, Func<float>? peakOf, string mode)
    {
        started = DateTime.Now;
        modeText = mode;
        state.Text = "🎙  Aufnahme";
        level.ShowRecording(levelOf, peakOf);
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

    /// Transkribieren, Verbessern …: die Welle wird ruhig.
    public void ShowWorking(string what)
    {
        state.Text = what;
        started = default;
        detail.Text = modeText;
        level.ShowWorking();
        if (!IsVisible) { Place(); Show(); }
    }

    public void HideOverlay()
    {
        timer.Stop();
        level.ShowIdle();
        Hide();
    }
}

internal static class ControlExtensions
{
    public static T With<T>(this T c, Action<T> a) { a(c); return c; }
}
