using System.Diagnostics;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Threading;

namespace WhisperOffline;

/// Wellenform für Aufnahme und Transkription: drei überlagerte Sinuswellen,
/// deren Höhe dem Mikrofonpegel folgt (weich nachgeführt); beim Transkribieren
/// eine ruhige Welle, im Leerlauf eine stille Linie. Der Timer läuft nur,
/// solange sich etwas bewegt — im Leerlauf kostet die Anzeige nichts.
/// Gegenstück zu LiveWaveform in der Android-App.
public sealed class WaveformView : Control
{
    private enum Mode { Idle, Recording, Working }

    private static readonly Color RecordColor = Color.Parse("#EF4444");
    private static readonly Color RecordAccent = Color.Parse("#EC4899");
    private static readonly Color WorkColor = Color.Parse("#8B5CF6");
    private static readonly Color WorkAccent = Color.Parse("#38BDF8");
    private static readonly Color IdleColor = Color.Parse("#808080");

    // (Deckkraft, Strichstärke, Frequenz)
    private static readonly (double Alpha, double Stroke, double Freq)[] Layers =
        { (1.0, 2.8, 1.0), (0.55, 2.0, 1.6), (0.28, 1.4, 2.3) };

    private readonly DispatcherTimer timer = new() { Interval = TimeSpan.FromMilliseconds(16) };
    private readonly Stopwatch clock = new();
    private Func<float>? levelSource;
    private Mode mode = Mode.Idle;
    private double phase, amplitude, target;
    private double lastTick;

    public WaveformView()
    {
        MinHeight = 24;
        timer.Tick += (_, _) => Tick();
    }

    /// Aufnahme: [levelOf] liefert den Pegel 0..1 (AudioRecorder.Level).
    public void ShowRecording(Func<float> levelOf)
    {
        levelSource = levelOf;
        Switch(Mode.Recording);
    }

    public void ShowWorking()
    {
        levelSource = null;
        Switch(Mode.Working);
    }

    public void ShowIdle()
    {
        levelSource = null;
        Switch(Mode.Idle);
    }

    private void Switch(Mode m)
    {
        mode = m;
        target = m == Mode.Working ? 0.22 : 0.0;
        if (!timer.IsEnabled)
        {
            clock.Restart();
            lastTick = 0;
            timer.Start();
        }
    }

    private void Tick()
    {
        double now = clock.Elapsed.TotalSeconds;
        double dt = Math.Min(0.1, now - lastTick);
        lastTick = now;

        if (mode == Mode.Recording && levelSource != null)
            // Wurzel: Sprache liegt meist im unteren Pegelbereich
            target = Math.Clamp(Math.Sqrt(Math.Clamp(levelSource(), 0f, 1f)), 0.08, 1.0);
        double speed = mode == Mode.Recording ? 5.7 : mode == Mode.Working ? 3.2 : 1.5; // rad/s
        phase = (phase + speed * dt) % (2 * Math.PI);
        // Exponentielle Glättung, unabhängig von der Bildrate
        amplitude += (target - amplitude) * (1 - Math.Exp(-dt * 11));

        if (mode == Mode.Idle && amplitude < 0.002)
        {
            amplitude = 0;
            timer.Stop();
        }
        InvalidateVisual();
    }

    protected override void OnDetachedFromVisualTree(VisualTreeAttachmentEventArgs e)
    {
        timer.Stop();
        base.OnDetachedFromVisualTree(e);
    }

    public override void Render(DrawingContext context)
    {
        double w = Bounds.Width, h = Bounds.Height;
        if (w < 2 || h < 2) return;
        double mid = h / 2;
        var (c1, c2) = mode switch
        {
            Mode.Recording => (RecordAccent, RecordColor),
            Mode.Working => (WorkColor, WorkAccent),
            _ => (IdleColor, IdleColor),
        };

        for (int i = 0; i < Layers.Length; i++)
        {
            var (alpha, stroke, freq) = Layers[i];
            var geometry = new StreamGeometry();
            using (var g = geometry.Open())
            {
                const double step = 3;
                for (double x = 0; x <= w + step; x += step)
                {
                    double nx = Math.Min(1, x / w);
                    // Fenster: an den Rändern läuft die Welle sanft auf null aus
                    double envelope = Math.Sin(nx * Math.PI);
                    double angle = nx * 4 * Math.PI * freq + phase * (i + 1);
                    var p = new Point(nx * w, mid + Math.Sin(angle) * mid * 0.84 * amplitude * envelope);
                    if (x == 0) g.BeginFigure(p, false); else g.LineTo(p);
                }
                g.EndFigure(false);
            }
            var brush = new LinearGradientBrush
            {
                StartPoint = new RelativePoint(0, 0.5, RelativeUnit.Relative),
                EndPoint = new RelativePoint(1, 0.5, RelativeUnit.Relative),
                GradientStops =
                {
                    new GradientStop(Colors.Transparent, 0),
                    new GradientStop(WithAlpha(c1, alpha), 0.2),
                    new GradientStop(WithAlpha(c2, alpha), 0.8),
                    new GradientStop(Colors.Transparent, 1),
                },
            };
            context.DrawGeometry(null, new Pen(brush, stroke, lineCap: PenLineCap.Round, lineJoin: PenLineJoin.Round), geometry);
        }
    }

    private static Color WithAlpha(Color c, double a) => Color.FromArgb((byte)(c.A * a), c.R, c.G, c.B);
}
