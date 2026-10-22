using NAudio.CoreAudioApi;
using NAudio.Wave;
using NAudio.Wave.SampleProviders;

namespace WhisperOffline;

/// Woher die Live-Transkription ihre Samples liest (16 kHz Mono).
public interface ISampleSource
{
    int SampleCount { get; }
    float[] Snapshot(int from, int to = -1);
}

/// Mikrofonaufnahme über WASAPI (Shared Mode). Liefert 16-kHz-Mono-Floats
/// durch Downmix und lineares Resampling der Eingangsrate.
///
/// Mit [AudioSettings] kümmert sich der Recorder auch um das gewählte
/// Mikrofon, Start-/Stopp-Ton und das Leiserstellen anderer Wiedergabe.
public sealed class AudioRecorder : ISampleSource
{
    private WasapiCapture? capture;
    private readonly List<float> mono = new(16000 * 30);
    private readonly object gate = new();
    private int sourceRate = 48000;
    private Ducker? ducker;

    // Maximalwert seit dem letzten Abholen — die Wellenform holt ihn pro Bild ab.
    private readonly object tapGate = new();
    private float peakTap;

    /// Gelesen bei Start/Stopp; null = ohne Extras.
    public AppSettings? Settings { get; set; }

    /// Bekommt jeden Block als 16-kHz-Mono (für Meetings direkt in eine Datei).
    public Action<float[]>? OnChunk { get; set; }

    /// Nur an OnChunk liefern, nichts im Speicher halten.
    public bool KeepInMemory { get; set; } = true;

    public bool IsRecording => capture != null;

    /// Pegel des letzten Blocks (RMS, grob 0..1).
    public float Level { get; private set; }

    /// Größter Sample-Wert seit dem letzten Abholen (0..1) und Zurücksetzen.
    /// Liefert die echte Signalauslenkung für die Live-Wellenform — unabhängig
    /// von der Mikrofonlautstärke, weil die Anzeige selbst nachregelt.
    public float ConsumePeak()
    {
        lock (tapGate)
        {
            float p = peakTap;
            peakTap = 0;
            return p;
        }
    }

    public bool Start()
    {
        if (IsRecording) return true;
        try
        {
            var device = FindDevice(Settings?.MicDeviceId);
            var cap = device != null ? new WasapiCapture(device) : new WasapiCapture();
            sourceRate = cap.WaveFormat.SampleRate;
            int channels = cap.WaveFormat.Channels;
            lock (gate) mono.Clear();
            lock (tapGate) peakTap = 0;
            var resampler = new StreamResampler(sourceRate);
            // Den eigenen Startton nicht mit aufnehmen
            int mute = Settings is { AudioFeedback: true } ? sourceRate * 15 / 100 : 0;

            cap.DataAvailable += (_, e) =>
            {
                // IEEE-Float-Samples (WASAPI Shared liefert 32-Bit-Float)
                int floatCount = e.BytesRecorded / 4;
                var buffer = new float[floatCount];
                Buffer.BlockCopy(e.Buffer, 0, buffer, 0, e.BytesRecorded);
                var frame = new float[floatCount / Math.Max(1, channels)];
                double sum = 0;
                float peak = 0;
                for (int f = 0, i = 0; f < frame.Length; f++, i += channels)
                {
                    float acc = 0;
                    for (int c = 0; c < channels; c++) acc += buffer[i + c];
                    frame[f] = mute > 0 ? 0f : acc / channels;
                    if (mute > 0) mute--;
                    sum += frame[f] * frame[f];
                    float a = Math.Abs(frame[f]);
                    if (a > peak) peak = a;
                }
                if (frame.Length > 0) Level = (float)Math.Min(1.0, Math.Sqrt(sum / frame.Length) * 4);
                if (peak > 0) lock (tapGate) if (peak > peakTap) peakTap = peak;
                if (KeepInMemory) lock (gate) mono.AddRange(frame);
                if (OnChunk != null) OnChunk(resampler.Process(frame));
            };

            if (Settings is { Ducking: true }) ducker = Ducker.Start(Settings.DuckingLevel);
            if (Settings is { AudioFeedback: true }) Cues.Start();
            cap.StartRecording();
            capture = cap;
            return true;
        }
        catch
        {
            capture?.Dispose();
            capture = null;
            ducker?.Restore();
            ducker = null;
            return false;
        }
    }

    public float[] Stop(bool cue = true)
    {
        var cap = capture;
        capture = null;
        Level = 0;
        lock (tapGate) peakTap = 0;
        if (cap != null)
        {
            try { cap.StopRecording(); } catch { }
            Thread.Sleep(150); // letzte Puffer abholen
            cap.Dispose();
        }
        ducker?.Restore();
        ducker = null;
        if (cap != null && cue && Settings is { AudioFeedback: true }) Cues.Stop();
        return Snapshot(0);
    }

    /// Stop außerhalb des UI-Threads: Warten auf die letzten Puffer (150 ms)
    /// und die Resample-Kopie des kompletten Mitschnitts blockieren sonst
    /// nach jeder Aufnahme kurz die Oberfläche.
    public Task<float[]> StopAsync(bool cue = true) => Task.Run(() => Stop(cue));

    /// Bisher aufgenommene Samples, umgerechnet auf 16 kHz.
    public int SampleCount
    {
        get { lock (gate) return (int)((long)mono.Count * 16000 / sourceRate); }
    }

    /// 16-kHz-Samples [from, to) — auch während der Aufnahme abrufbar.
    public float[] Snapshot(int from, int to = -1)
    {
        lock (gate)
        {
            int total = (int)((long)mono.Count * 16000 / sourceRate);
            int end = to < 0 ? total : Math.Min(to, total);
            int start = Math.Clamp(from, 0, end);
            var outp = new float[end - start];
            double ratio = (double)sourceRate / 16000;
            for (int i = 0; i < outp.Length; i++)
            {
                double src = (start + i) * ratio;
                int i0 = (int)src;
                int i1 = Math.Min(i0 + 1, mono.Count - 1);
                double frac = src - i0;
                outp[i] = (float)(mono[i0] * (1 - frac) + mono[i1] * frac);
            }
            return outp;
        }
    }

    /// Aufnahmegeräte für die Auswahl in den Einstellungen.
    public static List<(string Id, string Name)> InputDevices()
    {
        try
        {
            using var en = new MMDeviceEnumerator();
            return en.EnumerateAudioEndPoints(DataFlow.Capture, DeviceState.Active)
                .Select(d => (d.ID, d.FriendlyName)).ToList();
        }
        catch { return new(); }
    }

    private static MMDevice? FindDevice(string? id)
    {
        if (string.IsNullOrEmpty(id)) return null;
        try
        {
            using var en = new MMDeviceEnumerator();
            return en.GetDevice(id);
        }
        catch { return null; } // Gerät abgesteckt: Standardgerät nehmen
    }
}

/// Lineares Resampling auf 16 kHz über Blockgrenzen hinweg.
public sealed class StreamResampler
{
    private readonly double ratio;
    private double pos;
    private float last;

    public StreamResampler(int fromRate) { ratio = fromRate / 16000.0; }

    public float[] Process(float[] input)
    {
        if (Math.Abs(ratio - 1) < 1e-9) return input;
        var outp = new List<float>((int)(input.Length / ratio) + 2);
        while (pos < input.Length)
        {
            int i0 = (int)Math.Floor(pos);
            double frac = pos - i0;
            float a = i0 < 0 ? last : input[i0];
            float b = i0 + 1 < input.Length ? input[i0 + 1] : input[^1];
            outp.Add((float)(a * (1 - frac) + b * frac));
            pos += ratio;
        }
        pos -= input.Length;
        if (input.Length > 0) last = input[^1];
        return outp.ToArray();
    }
}

/// Kurze Signaltöne, im Code erzeugt (keine Sounddateien nötig).
public static class Cues
{
    public static void Start() => Play(new[] { (880.0, 0.07) });
    public static void Stop() => Play(new[] { (660.0, 0.06), (990.0, 0.08) });
    public static void Error() => Play(new[] { (330.0, 0.12), (250.0, 0.16) });

    private static void Play((double freq, double seconds)[] tones)
    {
        if (!OperatingSystem.IsWindows()) return;
        Task.Run(() =>
        {
            try
            {
                var parts = tones.Select(t => new SignalGenerator(44100, 1)
                {
                    Frequency = t.freq, Gain = 0.18, Type = SignalGeneratorType.Sin,
                }.Take(TimeSpan.FromSeconds(t.seconds)));
                var chain = new ConcatenatingSampleProvider(parts);
                using var outp = new WaveOutEvent();
                outp.Init(chain);
                outp.Play();
                while (outp.PlaybackState == PlaybackState.Playing) Thread.Sleep(20);
            }
            catch { }
        });
    }
}

/// Andere Wiedergabe (Musik, Videos) während der Aufnahme leiser stellen und
/// danach auf den alten Stand zurück. Nur Sitzungen anderer Prozesse.
public sealed class Ducker
{
    private readonly List<(SimpleAudioVolume vol, float before)> changed = new();

    public static Ducker? Start(int levelPercent)
    {
        if (!OperatingSystem.IsWindows()) return null;
        var d = new Ducker();
        try
        {
            using var en = new MMDeviceEnumerator();
            var dev = en.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
            var sessions = dev.AudioSessionManager.Sessions;
            int self = Environment.ProcessId;
            for (int i = 0; i < sessions.Count; i++)
            {
                var s = sessions[i];
                if ((int)s.GetProcessID == self) continue;
                var v = s.SimpleAudioVolume;
                d.changed.Add((v, v.Volume));
                v.Volume = v.Volume * Math.Clamp(levelPercent, 0, 100) / 100f;
            }
        }
        catch { }
        return d;
    }

    public void Restore()
    {
        foreach (var (vol, before) in changed)
            try { vol.Volume = before; } catch { }
        changed.Clear();
    }
}
