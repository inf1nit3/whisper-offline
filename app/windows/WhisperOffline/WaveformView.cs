using System.Diagnostics;
using System.Globalization;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Media.Immutable;
using Avalonia.Threading;

namespace WhisperOffline;

/// Wellenform für Aufnahme und Transkription.
///
/// Aufnahme: die echte Signalauslenkung des Mikrofons als laufende
/// Balken-Welle (wie in Sprachmemo-Apps). Jeder Bildwechsel holt den
/// Spitzenwert seit dem letzten Bild ab und hängt ihn rechts an; die Welle
/// läuft nach links. Ein langsamer Nachregler (Auto-Gain) sorgt dafür, dass
/// auch leise Mikrofone eine deutliche Welle zeigen — deshalb „funktioniert“
/// die Anzeige unabhängig von der Aufnahmelautstärke. Rechts steht die
/// Aufnahmezeit.
///
/// Transkribieren: eine ruhige, dreamatische Welle aus drei überlagerten
/// Sinusläufen. Leerlauf: eine dezente Verlaufslinie ohne Animation.
/// Der Timer läuft nur, solange sich etwas bewegt.
/// Gegenstück zu LiveWaveform in der Android-App.
public sealed class WaveformView : Control
{
    private enum Mode { Idle, Recording, Working }

    private static readonly Color RecordFrom = Color.Parse("#F472B6");
    private static readonly Color RecordTo = Color.Parse("#EF4444");
    private static readonly Color WorkFrom = Color.Parse("#8B5CF6");
    private static readonly Color WorkTo = Color.Parse("#38BDF8");
    private static readonly Color IdleFrom = Color.Parse("#94A3B8");
    private static readonly Color IdleTo = Color.Parse("#64748B");

    // (Deckkraft, Strichstärke, Frequenz) der Arbeits-Wellen
    private static readonly (double Alpha, double Stroke, double Freq)[] Layers =
        { (1.0, 2.8, 1.0), (0.55, 2.0, 1.6), (0.28, 1.4, 2.3) };

    private const double ColumnWidth = 3;   // Balkenbreite in px
    private const double ColumnGap = 2;     // Abstand zwischen Balken
    private const double ColumnStep = ColumnWidth + ColumnGap;

    // Einmal gebaut statt pro Bild neu: die Welle läuft mit 60 fps, und
    // Pinsel/Text pro Frame würden den GC dauerhaft beschäftigen.
    private static readonly ImmutableSolidColorBrush PillBrush =
        new(Color.FromArgb(165, 15, 18, 24));
    private static readonly ImmutableSolidColorBrush MidlineBrush =
        new(Color.FromArgb(41, 239, 68, 68));
    private static readonly ImmutableSolidColorBrush HintBrush =
        new(Color.FromArgb(220, 255, 205, 120));
    private LinearGradientBrush? recordBrush, idleBrush;
    private readonly LinearGradientBrush?[] workBrushes = new LinearGradientBrush?[Layers.Length];
    private FormattedText? timeLabel, noSignalLabel;
    private string timeLabelText = "";

    private readonly DispatcherTimer timer = new() { Interval = TimeSpan.FromMilliseconds(16) };
    private readonly Stopwatch clock = new();
    private Func<float>? levelSource;
    private Func<float>? peakSource;
    private Mode mode = Mode.Idle;
    private double phase, amplitude, target;
    private double lastTick;

    // Laufende Welle: Ring der letzten Balkenhöhen (0..1), ältester zuerst.
    private float[] columns = Array.Empty<float>();
    private int colHead, colCount;
    private int colSlots;
    private double gain = 0.08;             // Auto-Gain: höchster kürzlicher Spitzenwert
    private int silentTicks;                // > 2 s ohne jedes Signal → Hinweis
    private DateTime recordStart;

    public WaveformView()
    {
        MinHeight = 24;
        timer.Tick += (_, _) => Tick();
    }

    /// Aufnahme: [levelOf] liefert den RMS-Pegel 0..1 (AudioRecorder.Level),
    /// [peakOf] optional den Spitzenwert seit dem letzten Abruf und setzt ihn
    /// zurück (AudioRecorder.ConsumePeak) — daraus wird die echte Welle.
    public void ShowRecording(Func<float> levelOf, Func<float>? peakOf = null)
    {
        levelSource = levelOf;
        peakSource = peakOf;
        recordStart = DateTime.UtcNow;
        gain = 0.08;
        colCount = 0;
        silentTicks = 0;
        Switch(Mode.Recording);
    }

    public void ShowWorking()
    {
        levelSource = null;
        peakSource = null;
        Switch(Mode.Working);
    }

    public void ShowIdle()
    {
        levelSource = null;
        peakSource = null;
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
        InvalidateVisual();
    }

    private void Tick()
    {
        double now = clock.Elapsed.TotalSeconds;
        double dt = Math.Min(0.1, now - lastTick);
        lastTick = now;

        if (mode == Mode.Recording)
        {
            // Wurzel: Sprache liegt meist im unteren Pegelbereich
            if (levelSource != null)
                target = Math.Clamp(Math.Sqrt(Math.Clamp(levelSource(), 0f, 1f)), 0.08, 1.0);

            // Neuer Balken aus dem echten Signal; Auto-Gain stellt sich dem
            // Mikrofon nach (schnell hoch, langsam wieder herunter), damit
            // die Welle auch bei leisen Geräten voll sichtbar ist.
            double peak = peakSource != null ? peakSource()
                : Math.Clamp(levelSource?.Invoke() ?? 0f, 0f, 1f);
            gain = Math.Max(0.025, Math.Max(peak, gain * (1 - 0.004)));
            double v = Math.Sqrt(Math.Min(1.0, peak / gain));
            AppendColumn((float)v);
            silentTicks = peak < 0.0005 ? Math.Min(silentTicks + 1, 1000) : 0;
        }

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

    private void AppendColumn(float v)
    {
        if (columns.Length == 0) return;
        int slot = (colHead + colCount) % columns.Length;
        if (colCount < columns.Length) colCount++;
        else colHead = (colHead + 1) % columns.Length; // ältesten überschreiben
        columns[slot] = v;
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

        switch (mode)
        {
            case Mode.Recording:
                RenderRecording(context, w, h, mid);
                break;
            case Mode.Working:
                RenderWaves(context, w, h, mid);
                break;
            default:
                RenderIdle(context, w, mid);
                break;
        }
    }

    /// Laufsende Balken aus dem echten Signal, mit Zeit rechts.
    private void RenderRecording(DrawingContext context, double w, double h, double mid)
    {
        EnsureColumns(w);

        recordBrush ??= new LinearGradientBrush
        {
            StartPoint = new RelativePoint(0, 0.5, RelativeUnit.Relative),
            EndPoint = new RelativePoint(1, 0.5, RelativeUnit.Relative),
            GradientStops =
            {
                new GradientStop(WithAlpha(RecordFrom, 0), 0),
                new GradientStop(WithAlpha(RecordFrom, 1), 0.12),
                new GradientStop(WithAlpha(RecordTo, 1), 0.9),
                new GradientStop(WithAlpha(RecordTo, 0.75), 1),
            },
        };
        var brush = recordBrush;
        var pen = new Pen(MidlineBrush, 1);

        // Balken von rechts (neueste) nach links zeichnen
        double usable = h - 4;
        int drawn = 0;
        for (int i = colCount - 1; i >= 0; i--)
        {
            double x = w - ColumnGap - ColumnWidth - i * ColumnStep;
            if (x < 0) break;
            float v = columns[(colHead + i) % columns.Length];
            double bar = 3 + v * usable * 0.94;
            var rect = new Rect(x, mid - bar / 2, ColumnWidth, bar);
            context.DrawRectangle(brush, null, rect, ColumnWidth / 2, ColumnWidth / 2);
            drawn++;
        }
        // Auch ohne Balken (ganz am Anfang): gepunktete Grundlinie zeigt Leben
        if (drawn == 0)
            for (double x = w - ColumnWidth; x >= 0; x -= ColumnStep)
                context.DrawRectangle(brush, null, new Rect(x, mid - 1, ColumnWidth, 3), 1.5, 1.5);

        // Sanfte Mittellinie verbindet die Balken optisch
        if (drawn > 0)
            context.DrawLine(pen, new Point(0, mid), new Point(w, mid));

        // Aufnahmezeit rechts: helle Schrift auf dunkler Pille, über den
        // Balken — der Text wechselt nur einmal pro Sekunde, also cachebar.
        var elapsed = DateTime.UtcNow - recordStart;
        var label = $"{(int)elapsed.TotalMinutes:0}:{elapsed.Seconds:00}";
        if (timeLabel == null || timeLabelText != label)
        {
            timeLabelText = label;
            timeLabel = new FormattedText(
                label, CultureInfo.CurrentCulture, FlowDirection.LeftToRight, Typeface.Default, 11,
                Brushes.White);
        }
        double tw = timeLabel.WidthIncludingTrailingWhitespace + 10;
        double th = timeLabel.Height + 4;
        var pill = new Rect(w - tw - 4, mid - th / 2, tw, th);
        context.DrawRectangle(PillBrush, null, pill, th / 2, th / 2);
        context.DrawText(timeLabel, new Point(pill.X + 5, mid - timeLabel.Height / 2));

        // Länger als zwei Sekunden nicht das geringste Signal: Das Mikrofon
        // liefert nichts (stumm, verboten oder falsches Gerät) — direkt in
        // der Welle sagen, statt eine tote Anzeige zu zeigen.
        if (silentTicks > 125)
        {
            noSignalLabel ??= new FormattedText(
                "Kein Eingangssignal — Mikrofon prüfen",
                CultureInfo.CurrentCulture, FlowDirection.LeftToRight, Typeface.Default, 11,
                HintBrush);
            context.DrawText(noSignalLabel, new Point(10, mid - noSignalLabel.Height / 2));
        }
    }

    /// Ruhige Welle beim Transkribieren: drei überlagerte Sinusläufe.
    private void RenderWaves(DrawingContext context, double w, double h, double mid)
    {
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
            // Pro Ebene ein Pinsel: die Deckkraft gehört zur Ebene
            var brush = workBrushes[i] ??= new LinearGradientBrush
            {
                StartPoint = new RelativePoint(0, 0.5, RelativeUnit.Relative),
                EndPoint = new RelativePoint(1, 0.5, RelativeUnit.Relative),
                GradientStops =
                {
                    new GradientStop(Colors.Transparent, 0),
                    new GradientStop(WithAlpha(WorkFrom, alpha), 0.2),
                    new GradientStop(WithAlpha(WorkTo, alpha), 0.8),
                    new GradientStop(Colors.Transparent, 1),
                },
            };
            context.DrawGeometry(null, new Pen(brush, stroke, lineCap: PenLineCap.Round, lineJoin: PenLineJoin.Round), geometry);
        }
    }

    /// Dezente Ruhelinie — sichtbar auf hellen wie dunklen Karten.
    private void RenderIdle(DrawingContext context, double w, double mid)
    {
        idleBrush ??= new LinearGradientBrush
        {
            StartPoint = new RelativePoint(0, 0.5, RelativeUnit.Relative),
            EndPoint = new RelativePoint(1, 0.5, RelativeUnit.Relative),
            GradientStops =
            {
                new GradientStop(Colors.Transparent, 0),
                new GradientStop(WithAlpha(IdleFrom, 0.9), 0.15),
                new GradientStop(WithAlpha(IdleTo, 0.9), 0.85),
                new GradientStop(Colors.Transparent, 1),
            },
        };
        context.DrawLine(new Pen(idleBrush, 2, lineCap: PenLineCap.Round), new Point(2, mid), new Point(w - 2, mid));
    }

    /// Ringpuffer an die Breite anpassen: ~140 Balken passen immer, bei mehr
    /// Platz läuft die Welle weiter in die Vergangenheit.
    private void EnsureColumns(double w)
    {
        int slots = Math.Max(16, (int)(w / ColumnStep));
        if (slots == colSlots && columns.Length > 0) return;
        var fresh = new float[slots];
        int keep = Math.Min(colCount, slots);
        for (int i = 0; i < keep; i++)
            fresh[i] = columns[(colHead + colCount - keep + i) % columns.Length];
        columns = fresh;
        colSlots = slots;
        colHead = 0;
        colCount = keep;
    }

    private static Color WithAlpha(Color c, double a) => Color.FromArgb((byte)(c.A * a), c.R, c.G, c.B);
}
