using System.IO;
using System.Text;
using System.Text.Json;
using NAudio.CoreAudioApi;
using NAudio.Wave;
using SherpaOnnx;

namespace WhisperOffline;

// Meeting-Modus unter Windows: Mikrofon (eigene Stimme) und Systemaudio
// (die anderen im Call) als getrennte Spuren aufnehmen, Sprecher trennen,
// je Redebeitrag transkribieren, Notizen schreiben.
// Ablage: meetings/<id>/{meeting.json, mic.wav, system.wav} neben der EXE.

/// 16-kHz-Mono-WAV direkt auf die Platte (auch stundenlang).
public sealed class WavFileWriter : IDisposable
{
    private readonly FileStream fs;
    private readonly object gate = new();
    public string Path { get; }
    public int Samples { get; private set; }

    public WavFileWriter(string path)
    {
        Path = path;
        bool exists = File.Exists(path) && new FileInfo(path).Length >= 44;
        fs = new FileStream(path, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.Read);
        if (!exists) { fs.Write(WavFile.Header(0)); }
        else Samples = (int)((fs.Length - 44) / 2);
        fs.Seek(0, SeekOrigin.End);
    }

    public void Write(float[] samples)
    {
        var bytes = new byte[samples.Length * 2];
        for (int i = 0; i < samples.Length; i++)
        {
            short v = (short)Math.Clamp(samples[i] * 32767f, short.MinValue, short.MaxValue);
            bytes[2 * i] = (byte)v; bytes[2 * i + 1] = (byte)(v >> 8);
        }
        lock (gate) { fs.Write(bytes); Samples += samples.Length; }
    }

    /// Mit Stille auffüllen (Systemaudio liefert in Pausen keine Daten).
    public void PadTo(int samples)
    {
        int missing = samples - Samples;
        if (missing > 0) Write(new float[missing]);
    }

    public void Flush() { lock (gate) fs.Flush(); }

    public void Dispose()
    {
        lock (gate)
        {
            fs.Flush();
            fs.Seek(0, SeekOrigin.Begin);
            fs.Write(WavFile.Header(fs.Length - 44));
            fs.Dispose();
        }
    }
}

public static class WavFile
{
    public static byte[] Header(long dataBytes)
    {
        using var ms = new MemoryStream();
        using var w = new BinaryWriter(ms);
        w.Write("RIFF"u8); w.Write((int)(36 + dataBytes)); w.Write("WAVE"u8); w.Write("fmt "u8);
        w.Write(16); w.Write((short)1); w.Write((short)1); w.Write(16000); w.Write(32000); w.Write((short)2); w.Write((short)16);
        w.Write("data"u8); w.Write((int)dataBytes);
        return ms.ToArray();
    }

    public static int SampleCount(string path) =>
        File.Exists(path) ? (int)Math.Max(0, (new FileInfo(path).Length - 44) / 2) : 0;

    public static void FixHeader(string path)
    {
        if (!File.Exists(path) || new FileInfo(path).Length < 44) return;
        using var fs = new FileStream(path, FileMode.Open, FileAccess.ReadWrite, FileShare.ReadWrite);
        fs.Write(Header(fs.Length - 44));
    }

    public static float[] Read(string path, int from, int to)
    {
        int n = Math.Max(0, to - from);
        var outp = new float[n];
        if (n == 0 || !File.Exists(path)) return outp;
        using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
        fs.Seek(44 + from * 2L, SeekOrigin.Begin);
        var bytes = new byte[n * 2];
        int got = 0, r;
        while (got < bytes.Length && (r = fs.Read(bytes, got, bytes.Length - got)) > 0) got += r;
        for (int i = 0; i < got / 2; i++) outp[i] = (short)(bytes[2 * i] | (bytes[2 * i + 1] << 8)) / 32768f;
        return outp;
    }
}

public record Utterance(int Speaker, float Start, float End, string Text);
public record SpeakerInfo(string Name, bool Recognized = false);

public sealed class Meeting
{
    public string Id { get; set; } = "";
    public string Title { get; set; } = "";
    public long StartedMs { get; set; }
    /// "recording", "recorded" (wartet auf Auswertung), "done"
    public string State { get; set; } = "recording";
    public int SpeakerCount { get; set; }
    /// "mic", "system", "both"
    public string Source { get; set; } = "both";
    public float DurationSec { get; set; }
    public List<Utterance> Utterances { get; set; } = new();
    public List<SpeakerInfo> Speakers { get; set; } = new();
    public string Notes { get; set; } = "";
    public bool Diarized { get; set; }

    public string SpeakerName(int i) => i < Speakers.Count ? Speakers[i].Name : $"Sprecher {i + 1}";

    public string TranscriptText() => string.Join("\n\n", Utterances.Select(u => Diarized ? $"{SpeakerName(u.Speaker)}: {u.Text}" : u.Text));

    public Dictionary<int, float> TalkTime() => Utterances.GroupBy(u => u.Speaker).ToDictionary(g => g.Key, g => g.Sum(u => u.End - u.Start));
}

public static class MeetingStore
{
    public static string Dir => Path.Combine(WhisperCli.BaseDir, "meetings");
    public static string Folder(string id) => Path.Combine(Dir, id);
    public static string Mic(string id) => Path.Combine(Folder(id), "mic.wav");
    public static string System(string id) => Path.Combine(Folder(id), "system.wav");

    private static readonly JsonSerializerOptions Opts = new() { WriteIndented = false };

    public static List<Meeting> List()
    {
        if (!Directory.Exists(Dir)) return new();
        return Directory.GetDirectories(Dir).Select(d => Load(Path.GetFileName(d))).Where(m => m != null)
            .Select(m => m!).OrderByDescending(m => m.StartedMs).ToList();
    }

    public static Meeting? Load(string id)
    {
        try { return JsonSerializer.Deserialize<Meeting>(File.ReadAllText(Path.Combine(Folder(id), "meeting.json")), Opts); }
        catch { return null; }
    }

    public static void Save(Meeting m)
    {
        Directory.CreateDirectory(Folder(m.Id));
        var f = Path.Combine(Folder(m.Id), "meeting.json");
        File.WriteAllText(f + ".tmp", JsonSerializer.Serialize(m, Opts));
        File.Move(f + ".tmp", f, overwrite: true);
    }

    public static void Delete(string id)
    {
        try { Directory.Delete(Folder(id), recursive: true); } catch { }
    }

    public static void SaveVoices(string id, List<float[]> voices) =>
        File.WriteAllText(Path.Combine(Folder(id), "voices.json"), JsonSerializer.Serialize(voices));

    public static List<float[]> LoadVoices(string id)
    {
        try { return JsonSerializer.Deserialize<List<float[]>>(File.ReadAllText(Path.Combine(Folder(id), "voices.json"))) ?? new(); }
        catch { return new(); }
    }
}

/// Gespeicherte Stimmen: Name → Stimmabdruck.
public static class VoiceProfiles
{
    private static string FilePath => Path.Combine(WhisperCli.BaseDir, "voices.json");

    public static Dictionary<string, float[]> Load()
    {
        try { return JsonSerializer.Deserialize<Dictionary<string, float[]>>(File.ReadAllText(FilePath)) ?? new(); }
        catch { return new(); }
    }

    public static void Save(string name, float[] embedding)
    {
        var all = Load();
        all[name] = embedding;
        File.WriteAllText(FilePath, JsonSerializer.Serialize(all));
    }
}

/// Aufnahme: Mikrofon und/oder Systemaudio, je eine Datei.
public sealed class MeetingRecorder : ISampleSource
{
    private readonly AudioRecorder? mic;
    private WasapiLoopbackCapture? loop;
    private WasapiOut? silence;
    private readonly WavFileWriter? micOut, sysOut;
    private readonly System.Diagnostics.Stopwatch clock = new();
    private long offsetSamples;

    public float Level => Math.Max(mic?.Level ?? 0, sysLevel);
    private float sysLevel;

    public MeetingRecorder(string id, string source, AppSettings s)
    {
        Directory.CreateDirectory(MeetingStore.Folder(id));
        if (source is "mic" or "both")
        {
            micOut = new WavFileWriter(MeetingStore.Mic(id));
            // Ohne Ducking: in einem Call soll die Gegenseite hörbar bleiben
            mic = new AudioRecorder { Settings = s, KeepInMemory = false };
            mic.OnChunk = micOut.Write;
        }
        if (source is "system" or "both") sysOut = new WavFileWriter(MeetingStore.System(id));
        offsetSamples = Math.Max(micOut?.Samples ?? 0, sysOut?.Samples ?? 0);
    }

    public bool Start()
    {
        if (mic != null)
        {
            bool duck = mic.Settings!.Ducking;
            mic.Settings.Ducking = false;
            bool ok = mic.Start();
            mic.Settings.Ducking = duck;
            if (!ok) return false;
        }
        if (sysOut != null && OperatingSystem.IsWindows())
        {
            try
            {
                using var en = new MMDeviceEnumerator();
                var dev = en.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
                // Loopback liefert nur, solange etwas spielt — eine stille
                // Wiedergabe hält den Datenstrom und damit die Zeitachse am Laufen.
                silence = new WasapiOut(dev, AudioClientShareMode.Shared, true, 200);
                silence.Init(new SilenceProvider(new WaveFormat(48000, 16, 2)));
                silence.Play();
                loop = new WasapiLoopbackCapture(dev);
                var fmt = loop.WaveFormat;
                var rs = new StreamResampler(fmt.SampleRate);
                int ch = fmt.Channels;
                bool isFloat = fmt.Encoding == WaveFormatEncoding.IeeeFloat || fmt.BitsPerSample == 32;
                loop.DataAvailable += (_, e) =>
                {
                    int frames = e.BytesRecorded / (fmt.BitsPerSample / 8) / ch;
                    var mono = new float[frames];
                    double sum = 0;
                    for (int f = 0; f < frames; f++)
                    {
                        float acc = 0;
                        for (int c = 0; c < ch; c++)
                        {
                            int idx = f * ch + c;
                            acc += isFloat ? BitConverter.ToSingle(e.Buffer, idx * 4) : BitConverter.ToInt16(e.Buffer, idx * 2) / 32768f;
                        }
                        mono[f] = acc / ch;
                        sum += mono[f] * mono[f];
                    }
                    if (frames > 0) sysLevel = (float)Math.Min(1, Math.Sqrt(sum / frames) * 4);
                    sysOut.Write(rs.Process(mono));
                };
                loop.StartRecording();
            }
            catch
            {
                if (mic == null) return false; // nur Systemaudio gewünscht, und das geht nicht
            }
        }
        clock.Restart();
        return true;
    }

    public void Stop()
    {
        mic?.Stop();
        try { loop?.StopRecording(); } catch { }
        Thread.Sleep(150);
        loop?.Dispose(); loop = null;
        try { silence?.Stop(); } catch { }
        silence?.Dispose(); silence = null;
        // Spuren auf gleiche Länge bringen (Zeitachse)
        int len = Math.Max(micOut?.Samples ?? 0, sysOut?.Samples ?? 0);
        micOut?.PadTo(len); sysOut?.PadTo(len);
        micOut?.Dispose();
        sysOut?.Dispose();
    }

    public int Seconds => (int)(offsetSamples / 16000 + clock.Elapsed.TotalSeconds);

    // Live-Vorschau: beide Spuren gemischt
    public int SampleCount => Math.Max(micOut?.Samples ?? 0, sysOut?.Samples ?? 0);

    public float[] Snapshot(int from, int to = -1)
    {
        micOut?.Flush(); sysOut?.Flush();
        int end = to < 0 ? SampleCount : to;
        var a = micOut != null ? WavFile.Read(micOut.Path, from, end) : null;
        var b = sysOut != null ? WavFile.Read(sysOut.Path, from, end) : null;
        if (a == null) return b ?? Array.Empty<float>();
        if (b == null) return a;
        var m = new float[Math.Max(a.Length, b.Length)];
        for (int i = 0; i < m.Length; i++) m[i] = ((i < a.Length ? a[i] : 0) + (i < b.Length ? b[i] : 0)) * 0.7f;
        return m;
    }
}

/// Sprechertrennung mit sherpa-onnx, in Fenstern mit globaler Stimmzuordnung.
public sealed class Diarizer : IDisposable
{
    public static string ModelDir => Path.Combine(WhisperCli.BaseDir, "models", "meeting");
    public static string? Segmentation => Directory.Exists(ModelDir) ? Directory.GetFiles(ModelDir, "*.onnx").FirstOrDefault(f => Path.GetFileName(f).Contains("segmentation")) : null;
    public static string? Embedding => Directory.Exists(ModelDir) ? Directory.GetFiles(ModelDir, "*.onnx").FirstOrDefault(f => !Path.GetFileName(f).Contains("segmentation")) : null;
    public static bool Available => Segmentation != null && Embedding != null;

    private readonly OfflineSpeakerDiarization diar;
    private readonly SpeakerEmbeddingExtractor extractor;

    public Diarizer(int numSpeakers)
    {
        int threads = Math.Clamp(Environment.ProcessorCount / 2, 1, 4);
        var emb = new SpeakerEmbeddingExtractorConfig { Model = Embedding!, NumThreads = threads, Provider = "cpu" };
        var cfg = new OfflineSpeakerDiarizationConfig();
        cfg.Segmentation.Pyannote.Model = Segmentation!;
        cfg.Segmentation.NumThreads = threads;
        cfg.Segmentation.Provider = "cpu";
        cfg.Embedding = emb;
        cfg.Clustering.NumClusters = numSpeakers > 0 ? numSpeakers : -1;
        cfg.Clustering.Threshold = 0.5f;
        cfg.MinDurationOn = 0.3f;
        cfg.MinDurationOff = 0.5f;
        diar = new OfflineSpeakerDiarization(cfg);
        extractor = new SpeakerEmbeddingExtractor(emb);
    }

    public record Result(List<SpeakerMath.Turn> Turns, List<float[]> Embeddings);

    public Result Run(string wav, int numSpeakers, IProgress<float>? progress, CancellationToken ct, int windowSec = 600)
    {
        int total = WavFile.SampleCount(wav);
        int win = windowSec * 16000;
        var registry = new SpeakerMath.Registry(SpeakerMath.SameSpeaker, numSpeakers);
        var turns = new List<SpeakerMath.Turn>();
        for (int from = 0; from < total; from += win)
        {
            ct.ThrowIfCancellationRequested();
            int to = Math.Min(total, from + win);
            var samples = WavFile.Read(wav, from, to);
            var segs = diar.Process(samples);
            float offset = from / 16000f;
            var local = new Dictionary<int, int>();
            foreach (var g in segs.GroupBy(s => s.Speaker))
            {
                var buf = new List<float>();
                foreach (var s in g.OrderByDescending(s => s.End - s.Start))
                {
                    int a = Math.Clamp((int)(s.Start * 16000), 0, samples.Length);
                    int b = Math.Clamp((int)(s.End * 16000), a, samples.Length);
                    buf.AddRange(samples.AsSpan(a, Math.Min(b - a, 20 * 16000 - buf.Count)).ToArray());
                    if (buf.Count >= 20 * 16000) break;
                }
                local[g.Key] = registry.Assign(Embed(buf.ToArray()));
            }
            foreach (var s in segs) turns.Add(new SpeakerMath.Turn(local[s.Speaker], s.Start + offset, s.End + offset));
            progress?.Report((float)to / total);
        }
        return new Result(turns, registry.Centroids);
    }

    public float[] Embed(float[] samples)
    {
        using var stream = extractor.CreateStream();
        stream.AcceptWaveform(16000, samples);
        stream.InputFinished();
        return extractor.IsReady(stream) ? extractor.Compute(stream) : new float[extractor.Dim];
    }

    public void Dispose() { diar.Dispose(); extractor.Dispose(); }
}

/// Auswertung: Sprecher → Redebeiträge transkribieren → Notizen.
public static class MeetingProcessor
{
    public static async Task RunAsync(AppSettings s, string id, Action<float?, string> progress, CancellationToken ct)
    {
        var m = MeetingStore.Load(id) ?? throw new InvalidOperationException("Meeting nicht gefunden");
        var micPath = MeetingStore.Mic(id);
        var sysPath = MeetingStore.System(id);
        WavFile.FixHeader(micPath); WavFile.FixHeader(sysPath);
        bool hasMic = WavFile.SampleCount(micPath) > 16000;
        bool hasSys = WavFile.SampleCount(sysPath) > 16000 && HasSignal(sysPath);
        if (!hasMic && !hasSys) throw new InvalidOperationException("Aufnahme zu kurz oder leer");
        progress(null, "Lade Modell …");
        if (!await Task.Run(() => WhisperNative.EnsureLoaded(WhisperCli.SelectedModel), ct) && !WhisperCli.EnginePathExists)
            throw new InvalidOperationException("Kein Transkriptionsmodell geladen");

        // Pro Spur: (Datei, Turns, Sprecher-Offset). Beide Spuren: Mikrofon = „Ich“,
        // die anderen kommen aus dem Systemaudio und werden dort getrennt.
        var tracks = new List<(string Path, List<SpeakerMath.Turn> Turns)>();
        var speakers = new List<SpeakerInfo>();
        var voices = new List<float[]>();
        bool diarized = false;
        var known = VoiceProfiles.Load();

        async Task<List<SpeakerMath.Turn>> Diarize(string path, int count, int speakerOffset)
        {
            using var d = new Diarizer(count);
            var r = await Task.Run(() => d.Run(path, count, new Progress<float>(p => progress(p, "Erkenne Sprecher …")), ct), ct);
            for (int i = 0; i < r.Embeddings.Count; i++)
            {
                var e = r.Embeddings[i];
                var best = known.OrderByDescending(k => SpeakerMath.Cosine(k.Value, e)).FirstOrDefault();
                speakers.Add(best.Value != null && SpeakerMath.Cosine(best.Value, e) >= SpeakerMath.SameSpeaker
                    ? new SpeakerInfo(best.Key, true) : new SpeakerInfo($"Sprecher {speakerOffset + i + 1}"));
                voices.Add(e);
            }
            diarized = true;
            return r.Turns.Select(t => t with { Speaker = t.Speaker + speakerOffset }).ToList();
        }

        if (hasMic && hasSys)
        {
            var mic = await Task.Run(() => SpeechRegions.Find(WavFile.Read(micPath, 0, WavFile.SampleCount(micPath))), ct);
            tracks.Add((micPath, mic.Select(r => new SpeakerMath.Turn(0, r.Start, r.End)).ToList()));
            speakers.Add(new SpeakerInfo("Ich"));
            voices.Add(Array.Empty<float>());
            var others = Diarizer.Available ? await Diarize(sysPath, m.SpeakerCount > 1 ? m.SpeakerCount - 1 : 0, 1)
                : SystemSingle(sysPath, speakers, voices);
            tracks.Add((sysPath, others));
            diarized = true;
        }
        else
        {
            var path = hasMic ? micPath : sysPath;
            if (Diarizer.Available) tracks.Add((path, await Diarize(path, m.SpeakerCount, 0)));
            else
            {
                var r = await Task.Run(() => SpeechRegions.Find(WavFile.Read(path, 0, WavFile.SampleCount(path))), ct);
                tracks.Add((path, r.Select(x => new SpeakerMath.Turn(0, x.Start, x.End)).ToList()));
                speakers.Add(new SpeakerInfo("Sprecher 1"));
            }
        }

        // Transkription je Redebeitrag
        var pieces = tracks.SelectMany(t => SpeakerMath.MergeTurns(t.Turns).Select(p => (t.Path, p))).OrderBy(x => x.p.Start).ToList();
        var utt = new List<Utterance>();
        for (int i = 0; i < pieces.Count; i++)
        {
            ct.ThrowIfCancellationRequested();
            progress((float)i / Math.Max(1, pieces.Count), $"Transkribiere {i + 1} von {pieces.Count} …");
            var (path, t) = pieces[i];
            var samples = WavFile.Read(path, (int)(t.Start * 16000), (int)(t.End * 16000));
            if (samples.Length < 8000) continue;
            var text = await Task.Run(() => Transcriber.Transcribe(samples, s, s.Language == "auto" ? "" : s.Language,
                false, shortCtx: true), ct);
            if (WhisperNative.WasCancelled) throw new OperationCanceledException();
            text = text.Replace('\n', ' ').Trim();
            if (text.Length > 0) utt.Add(new Utterance(t.Speaker, t.Start, t.End, text));
        }
        // Benachbarte Beiträge derselben Person verbinden
        var joined = new List<Utterance>();
        foreach (var u in utt.OrderBy(u => u.Start))
        {
            if (joined.Count > 0 && joined[^1].Speaker == u.Speaker && u.Start - joined[^1].End < 2f)
                joined[^1] = joined[^1] with { End = u.End, Text = joined[^1].Text + " " + u.Text };
            else joined.Add(u);
        }
        var used = joined.Select(u => u.Speaker).Distinct().OrderBy(x => x).ToList();
        var remap = used.Select((sp, i) => (sp, i)).ToDictionary(x => x.sp, x => x.i);
        m.Utterances = joined.Select(u => u with { Speaker = remap[u.Speaker] }).ToList();
        m.Speakers = used.Select(sp => sp < speakers.Count ? speakers[sp] : new SpeakerInfo($"Sprecher {sp + 1}")).ToList();
        int n = 1;
        m.Speakers = m.Speakers.Select(sp => sp.Recognized || sp.Name == "Ich" ? sp : new SpeakerInfo($"Sprecher {n++}")).ToList();
        m.Diarized = diarized && m.Speakers.Count > 1;
        m.DurationSec = Math.Max(WavFile.SampleCount(micPath), WavFile.SampleCount(sysPath)) / 16000f;
        m.State = "done";
        MeetingStore.Save(m);
        MeetingStore.SaveVoices(id, used.Select(sp => sp < voices.Count ? voices[sp] : Array.Empty<float>()).ToList());
        HistoryStore.Add(new HistoryEntry(m.StartedMs, m.TranscriptText(), Path.GetFileName(WhisperCli.SelectedModel),
            WhisperNative.IsParakeet ? "" : Transcriber.DictationLanguage(s), m.DurationSec, "meeting"));

        if (s.MeetingNotesAuto && Enhancer.IsConfigured(s) && m.Utterances.Count >= 2)
        {
            progress(null, "Schreibe Notizen …");
            var lang = TextRules.GuessLanguage(m.TranscriptText());
            var o = await Enhancer.MeetingNotesAsync(s, m.TranscriptText(), lang.Length > 0 ? lang : "de", ct, d => progress(null, d));
            if (o.Text != null) { m.Notes = o.Text; MeetingStore.Save(m); }
        }
    }

    /// Systemaudio ohne Sprechermodelle: ein Sprecher „Gegenseite“.
    private static List<SpeakerMath.Turn> SystemSingle(string path, List<SpeakerInfo> speakers, List<float[]> voices)
    {
        speakers.Add(new SpeakerInfo("Gegenseite"));
        voices.Add(Array.Empty<float>());
        return SpeechRegions.Find(WavFile.Read(path, 0, WavFile.SampleCount(path))).Select(r => new SpeakerMath.Turn(1, r.Start, r.End)).ToList();
    }

    /// Ist auf der Spur überhaupt etwas zu hören (nicht nur die stille Wiedergabe)?
    private static bool HasSignal(string path)
    {
        int total = WavFile.SampleCount(path);
        for (int from = 0; from < total; from += 16000 * 30)
        {
            var a = WavFile.Read(path, from, Math.Min(total, from + 16000 * 30));
            if (a.Any(x => Math.Abs(x) > 0.01f)) return true;
        }
        return false;
    }
}

/// Minimaler PDF-Schreiber für Text (Helvetica, WinAnsi) — reicht für
/// Meeting-Notizen und Transkripte, ohne weitere Abhängigkeit.
public static class PdfWriter
{
    public static void Write(string path, string title, string body)
    {
        const int width = 595, height = 842, margin = 50, size = 10, leading = 13, maxChars = 95;
        var lines = new List<(string text, bool bold)> { (title, true), ("", false) };
        foreach (var raw in body.Replace("\r\n", "\n").Split('\n'))
        {
            bool bold = raw.StartsWith("#");
            var line = bold ? raw.TrimStart('#', ' ') : raw;
            if (line.Length == 0) { lines.Add(("", false)); continue; }
            var cur = new StringBuilder();
            foreach (var word in line.Split(' '))
            {
                if (cur.Length + word.Length + 1 > maxChars && cur.Length > 0) { lines.Add((cur.ToString(), bold)); cur.Clear(); }
                if (cur.Length > 0) cur.Append(' ');
                cur.Append(word);
            }
            lines.Add((cur.ToString(), bold));
        }
        int perPage = (height - 2 * margin) / leading;
        var pages = lines.Chunk(perPage).ToList();

        var objects = new List<byte[]>();
        int pagesObj = 2, fontObj = 3, boldObj = 4, firstPage = 5;
        var kids = string.Join(" ", pages.Select((_, i) => $"{firstPage + 2 * i} 0 R"));
        objects.Add(Ascii("<< /Type /Catalog /Pages 2 0 R >>"));
        objects.Add(Ascii($"<< /Type /Pages /Kids [{kids}] /Count {pages.Count} >>"));
        objects.Add(Ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"));
        objects.Add(Ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>"));
        foreach (var page in pages)
        {
            int contentObj = objects.Count + 2;
            objects.Add(Ascii($"<< /Type /Page /Parent {pagesObj} 0 R /MediaBox [0 0 {width} {height}] " +
                              $"/Resources << /Font << /F1 {fontObj} 0 R /F2 {boldObj} 0 R >> >> /Contents {contentObj} 0 R >>"));
            var cs = new MemoryStream();
            void W(string s) => cs.Write(Ascii(s));
            W($"BT {leading} TL {margin} {height - margin} Td\n");
            foreach (var (text, bold) in page)
            {
                W(bold ? $"/F2 {size + 1} Tf " : $"/F1 {size} Tf ");
                W("(");
                cs.Write(WinAnsi(text));
                W(") Tj T*\n");
            }
            W("ET\n");
            var content = cs.ToArray();
            objects.Add(Concat(Ascii($"<< /Length {content.Length} >>\nstream\n"), content, Ascii("\nendstream")));
        }

        using var fs = File.Create(path);
        var offsets = new List<long>();
        fs.Write(Ascii("%PDF-1.4\n"));
        for (int i = 0; i < objects.Count; i++)
        {
            offsets.Add(fs.Position);
            fs.Write(Ascii($"{i + 1} 0 obj\n"));
            fs.Write(objects[i]);
            fs.Write(Ascii("\nendobj\n"));
        }
        long xref = fs.Position;
        fs.Write(Ascii($"xref\n0 {objects.Count + 1}\n0000000000 65535 f \n"));
        foreach (var o in offsets) fs.Write(Ascii($"{o:D10} 00000 n \n"));
        fs.Write(Ascii($"trailer\n<< /Size {objects.Count + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n"));
    }

    private static byte[] Ascii(string s) => Encoding.ASCII.GetBytes(s);

    private static byte[] Concat(params byte[][] parts) => parts.SelectMany(p => p).ToArray();

    /// Text als WinAnsi (CP-1252) mit PDF-Escapes; Unbekanntes wird zu '?'.
    private static byte[] WinAnsi(string text)
    {
        var outp = new List<byte>();
        foreach (var c in text)
        {
            byte b = c switch
            {
                '€' => 0x80, '‚' => 0x82, '„' => 0x84, '…' => 0x85, '‘' => 0x91, '’' => 0x92,
                '“' => 0x93, '”' => 0x94, '•' => 0x95, '–' => 0x96, '—' => 0x97,
                _ => c < 256 && !(c >= 0x80 && c < 0xA0) ? (byte)c : (byte)'?',
            };
            if (b is (byte)'(' or (byte)')' or (byte)'\\') outp.Add((byte)'\\');
            outp.Add(b);
        }
        return outp.ToArray();
    }
}
