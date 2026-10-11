using System.Diagnostics;
using System.IO;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace WhisperOffline;

/// Anweisungen für das Sprachmodell (app/shared/prompts.json, eingebettet).
public static class Prompts
{
    private static JsonObject? json;

    private static JsonObject Get()
    {
        if (json != null) return json;
        var asm = typeof(Prompts).Assembly;
        var name = asm.GetManifestResourceNames().First(n => n.EndsWith("prompts.json"));
        using var s = asm.GetManifestResourceStream(name)!;
        json = JsonNode.Parse(s)!.AsObject();
        return json;
    }

    private static string Str(string key) => Get()[key]!.GetValue<string>();

    private static string LanguageLine(string code, string key)
    {
        var name = Get()["language_names"]![code]?.GetValue<string>();
        return name == null ? "" : Str(key).Replace("{{LANG}}", name);
    }

    private const string OutputRule = "Output only the rewritten text";

    public static string EnhanceSystem(AppSettings s, FormatMode mode, string language)
    {
        var modes = Get()["modes"]!;
        var modeLine = (modes[mode.Key()] ?? modes["standard"])!.GetValue<string>();
        var wordsLine = s.Vocabulary.Count == 0 ? "" : Str("words").Replace("{{WORDS}}", string.Join(", ", s.Vocabulary));
        var lang = LanguageLine(language, "language");
        var baseText = Str("enhance_system");
        if (!string.IsNullOrWhiteSpace(s.EnhancePrompt))
        {
            // Eigene Regeln: Sprache, Modus, Wortliste und reine Textausgabe hängt die App an
            var tail = baseText[Math.Max(0, baseText.IndexOf(OutputRule, StringComparison.Ordinal))..];
            return s.EnhancePrompt.Trim() + "\n" + lang + "\n" + modeLine + wordsLine + "\n" + tail;
        }
        return baseText.Replace("{{LANGUAGE}}", lang).Replace("{{MODE}}", modeLine).Replace("{{WORDS}}", wordsLine);
    }

    public static string DefaultEnhanceRules()
    {
        var b = Str("enhance_system");
        int cut = b.IndexOf(OutputRule, StringComparison.Ordinal);
        return (cut < 0 ? b : b[..cut]).Replace("{{LANGUAGE}}", "").Replace("{{MODE}}", "").Replace("{{WORDS}}", "").Trim();
    }

    public static string SelectionSystem(AppSettings s, string language) =>
        Str("selection_system").Replace("{{LANGUAGE}}", LanguageLine(language, "language_inline")) +
        (s.Vocabulary.Count == 0 ? "" : Str("words").Replace("{{WORDS}}", string.Join(", ", s.Vocabulary)));

    public static string SelectionUser(string instruction, string text) =>
        Str("selection_user").Replace("{{INSTRUCTION}}", instruction).Replace("{{TEXT}}", text);

    public static string Action(string key, string targetLanguage = "English") =>
        Get()["actions"]![key]!.GetValue<string>().Replace("{{LANG}}", targetLanguage);

    public static string NotesSystem(string language, bool chunk)
    {
        var all = Get()["notes_headings"]!;
        var h = (all[language] ?? all["en"])!.AsArray().Select(x => x!.GetValue<string>()).ToArray();
        return Str(chunk ? "notes_chunk_system" : "notes_system")
            .Replace("{{LANGUAGE}}", LanguageLine(language, "language_inline"))
            .Replace("{{H_SUMMARY}}", h[0]).Replace("{{H_DECISIONS}}", h[1])
            .Replace("{{H_ACTIONS}}", h[2]).Replace("{{H_QUESTIONS}}", h[3]);
    }
}

/// Das lokale Sprachmodell als eigener Prozess (engine/llm/llm-runner.exe).
///
/// Eigener Prozess, weil llama.cpp eine eigene ggml-Version mitbringt — im
/// App-Prozess liegen schon die ggml-DLLs von whisper.cpp. Abbrechen beendet
/// den Prozess; das Modell lädt beim nächsten Mal neu. Ohne „im Speicher
/// behalten“ endet der Prozess nach zwei Minuten Leerlauf.
public sealed class LlmClient
{
    public static string RunnerPath => Path.Combine(WhisperCli.EngineDir, "llm",
        OperatingSystem.IsWindows() ? "llm-runner.exe" : "llm-runner");

    public const int NCtx = 4096;

    private Process? proc;
    private Stream? stdin, stdout;
    private string loaded = "";
    private readonly SemaphoreSlim gate = new(1, 1);
    private CancellationTokenSource? idle;

    public static LlmClient Shared { get; } = new();

    public bool IsRunning => proc is { HasExited: false };

    private void EnsureProcess()
    {
        if (IsRunning) return;
        if (!File.Exists(RunnerPath)) throw new InvalidOperationException("llm-runner fehlt im engine/llm-Ordner");
        var psi = new ProcessStartInfo(RunnerPath)
        {
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
            WorkingDirectory = Path.GetDirectoryName(RunnerPath)!,
        };
        proc = Process.Start(psi) ?? throw new InvalidOperationException("llm-runner startet nicht");
        proc.ErrorDataReceived += (_, _) => { }; // stderr leeren, damit der Puffer nicht volläuft
        proc.BeginErrorReadLine();
        stdin = proc.StandardInput.BaseStream;
        stdout = proc.StandardOutput.BaseStream;
        loaded = "";
    }

    private async Task Send(string header, params byte[][] payloads)
    {
        var h = Encoding.ASCII.GetBytes(header + "\n");
        await stdin!.WriteAsync(h);
        foreach (var p in payloads) await stdin.WriteAsync(p);
        await stdin.FlushAsync();
    }

    private async Task<string> ReadLine()
    {
        var sb = new StringBuilder();
        var b = new byte[1];
        while (true)
        {
            int n = await stdout!.ReadAsync(b);
            if (n == 0) throw new IOException("llm-runner beendet");
            if (b[0] == '\n') return sb.ToString();
            sb.Append((char)b[0]);
        }
    }

    private async Task<string> Reply(IProgress<int>? progress)
    {
        while (true)
        {
            var line = await ReadLine();
            var parts = line.Split(' ', 2);
            if (parts[0] == "PRG") { progress?.Report(int.Parse(parts[1])); continue; }
            int len = int.Parse(parts[1]);
            var buf = new byte[len];
            int got = 0;
            while (got < len)
            {
                int n = await stdout!.ReadAsync(buf.AsMemory(got));
                if (n == 0) throw new IOException("llm-runner beendet");
                got += n;
            }
            var text = Encoding.UTF8.GetString(buf);
            if (parts[0] == "ERR") throw new InvalidOperationException(text);
            return text;
        }
    }

    public async Task<string> GenerateAsync(string modelPath, string system, string user, int maxTokens,
        bool keepLoaded, CancellationToken ct, IProgress<int>? progress = null)
    {
        await gate.WaitAsync(ct);
        idle?.Cancel();
        try
        {
            EnsureProcess();
            using var reg = ct.Register(Kill);
            if (loaded != modelPath)
            {
                var p = Encoding.UTF8.GetBytes(modelPath);
                await Send($"LOAD {NCtx} 0 {p.Length}", p);
                await Reply(null);
                loaded = modelPath;
            }
            var s = Encoding.UTF8.GetBytes(system);
            var u = Encoding.UTF8.GetBytes(user);
            await Send($"GEN {maxTokens} {s.Length} {u.Length}", s, u);
            return await Reply(progress);
        }
        catch (Exception) when (ct.IsCancellationRequested)
        {
            throw new OperationCanceledException(ct);
        }
        finally
        {
            gate.Release();
            if (!keepLoaded) ScheduleIdleStop();
        }
    }

    private void ScheduleIdleStop()
    {
        var cts = new CancellationTokenSource();
        idle = cts;
        _ = Task.Delay(TimeSpan.FromMinutes(2), cts.Token).ContinueWith(t =>
        {
            if (!t.IsCanceled) Kill();
        }, TaskScheduler.Default);
    }

    /// Prozess beenden — Abbruch oder Speicher freigeben.
    public void Kill()
    {
        try { if (proc is { HasExited: false }) proc.Kill(entireProcessTree: true); } catch { }
        proc = null;
        loaded = "";
    }
}

/// Enhance über einen eigenen Befehl, z. B. Claude Code. Der Prompt kommt
/// auf stdin und zusätzlich in Umgebungsvariablen; die Antwort ist stdout.
public static class CliEnhancer
{
    /// Vorlagen — in den Einstellungen anpassbar und per „Testen“ prüfbar.
    public static readonly (string Name, string Command)[] Templates =
    {
        ("Claude Code", "claude -p --output-format text"),
        ("Codex CLI", "codex exec --skip-git-repo-check -"),
        ("Gemini CLI", "gemini"),
    };

    public static async Task<string> RunAsync(string command, string system, string user, int timeoutSec, CancellationToken ct)
    {
        var full = system + "\n\n" + user;
        var psi = OperatingSystem.IsWindows()
            ? new ProcessStartInfo("cmd.exe", "/d /s /c \"" + command + "\"")
            : new ProcessStartInfo("/bin/sh", new[] { "-c", command });
        psi.RedirectStandardInput = true;
        psi.RedirectStandardOutput = true;
        psi.RedirectStandardError = true;
        psi.UseShellExecute = false;
        psi.CreateNoWindow = true;
        psi.StandardOutputEncoding = Encoding.UTF8;
        psi.StandardErrorEncoding = Encoding.UTF8;
        psi.WorkingDirectory = Path.GetTempPath();
        // Umgebungsvariablen sind unter Windows auf ~32 000 Zeichen begrenzt
        if (full.Length < 30000)
        {
            psi.Environment["WHISPER_FULL_PROMPT"] = full;
            psi.Environment["WHISPER_SYSTEM_PROMPT"] = system;
            psi.Environment["WHISPER_USER_PROMPT"] = user;
        }
        using var p = Process.Start(psi) ?? throw new InvalidOperationException("Befehl startet nicht");
        var outTask = p.StandardOutput.ReadToEndAsync();
        var errTask = p.StandardError.ReadToEndAsync();
        await p.StandardInput.WriteAsync(full);
        p.StandardInput.Close();
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromSeconds(Math.Max(5, timeoutSec)));
        try { await p.WaitForExitAsync(timeout.Token); }
        catch (OperationCanceledException)
        {
            try { p.Kill(entireProcessTree: true); } catch { }
            if (ct.IsCancellationRequested) throw;
            throw new TimeoutException($"Befehl hat nach {timeoutSec} s nicht geantwortet");
        }
        var stdout = await outTask;
        if (p.ExitCode != 0)
        {
            var err = (await errTask).Trim();
            throw new InvalidOperationException($"Befehl endete mit Code {p.ExitCode}" + (err.Length > 0 ? ": " + err[^Math.Min(err.Length, 300)..] : ""));
        }
        return stdout.Trim();
    }
}

/// Nachbearbeitung durch das Sprachmodell (lokal oder per CLI).
public static class Enhancer
{
    public record Outcome(string? Text, string? Error = null, bool Cancelled = false);

    public static string LlmDir => Path.Combine(WhisperCli.BaseDir, "models", "llm");

    public static string? LocalModel(AppSettings s) =>
        string.IsNullOrEmpty(s.LlmModel) ? null : Path.Combine(LlmDir, s.LlmModel) is var p && File.Exists(p) ? p : null;

    public static bool IsConfigured(AppSettings s) => s.EnhanceEngine == "cli"
        ? !string.IsNullOrWhiteSpace(s.CliCommand)
        : LocalModel(s) != null && File.Exists(LlmClient.RunnerPath);

    private static async Task<Outcome> RunAsync(AppSettings s, string system, string user, string input,
        CancellationToken ct, IProgress<int>? progress = null)
    {
        try
        {
            if (s.EnhanceEngine == "cli")
                return new Outcome(await CliEnhancer.RunAsync(s.CliCommand, system, user, s.CliTimeoutSeconds, ct));
            var model = LocalModel(s) ?? throw new InvalidOperationException("Kein Sprachmodell gewählt");
            // Grob 4 Zeichen je Token; Umformen darf länger werden, aber nicht ausufern
            int max = Math.Min(LlmClient.NCtx / 2, input.Length / 2 + 128);
            return new Outcome(await LlmClient.Shared.GenerateAsync(model, system, user, max, s.LlmKeepLoaded, ct, progress));
        }
        catch (OperationCanceledException) { return new Outcome(null, Cancelled: true); }
        catch (Exception e) { return new Outcome(null, e.Message); }
    }

    public static async Task<Outcome> EnhanceAsync(AppSettings s, string text, FormatMode mode, string language, CancellationToken ct)
    {
        var o = await RunAsync(s, Prompts.EnhanceSystem(s, mode, language), "<dictation>\n" + text + "\n</dictation>", text, ct);
        return o.Text == null ? o : o with { Text = Clean(s, o.Text, mode, text) };
    }

    public static async Task<Outcome> TransformAsync(AppSettings s, string text, string instruction, string language, CancellationToken ct)
    {
        var o = await RunAsync(s, Prompts.SelectionSystem(s, language), Prompts.SelectionUser(instruction, text), text, ct);
        return o.Text == null ? o : o with { Text = Clean(s, o.Text, FormatMode.Standard, text, capitalize: false) };
    }

    private static string Clean(AppSettings s, string output, FormatMode mode, string original, bool capitalize = true)
    {
        var t = Regex.Replace(output, "</?(dictation|text|diktat)>", "", RegexOptions.IgnoreCase).Trim();
        if (t.Length >= 2 && "\"„“«".Contains(t[0]) && "\"“”»".Contains(t[^1]) && (original.Length == 0 || !"\"„“«".Contains(original[0])))
            t = t[1..^1].Trim();
        t = TextRules.Tidy(t);
        if (capitalize && mode is FormatMode.Standard or FormatMode.Email or FormatMode.Document or FormatMode.Note or FormatMode.Todo or FormatMode.Subject)
            t = TextRules.CapitalizeSentences(t);
        if (mode == FormatMode.Subject) t = t.TrimEnd('.');
        t = WordFixer.Correct(t, s.Vocabulary);
        return t.Length == 0 ? original : t;
    }

    /// Meeting-Notizen; lange Transkripte erst abschnittsweise verdichten.
    public static async Task<Outcome> MeetingNotesAsync(AppSettings s, string transcript, string language,
        CancellationToken ct, Action<string>? onProgress = null)
    {
        if (!IsConfigured(s)) return new Outcome(null, "Kein Sprachmodell eingerichtet");
        // Ein CLI-Modell (Claude …) kennt große Kontexte — dort nicht stückeln
        int budget = s.EnhanceEngine == "cli" ? 400_000 : 7000;
        var material = transcript;
        for (int round = 0; material.Length > budget && round < 4; round++)
        {
            var parts = Chunk(material, budget);
            var sb = new StringBuilder();
            for (int i = 0; i < parts.Count; i++)
            {
                onProgress?.Invoke($"Werte Abschnitt {i + 1} von {parts.Count} aus …");
                var o = await RunAsync(s, Prompts.NotesSystem(language, chunk: true), parts[i], parts[i], ct);
                if (o.Text == null) return o;
                sb.AppendLine(o.Text.Trim());
            }
            material = sb.ToString();
        }
        onProgress?.Invoke("Schreibe Notizen …");
        var r = await RunAsync(s, Prompts.NotesSystem(language, chunk: false), material, material, ct);
        return r with { Text = r.Text?.Trim() };
    }

    private static List<string> Chunk(string text, int max)
    {
        var outp = new List<string>();
        var sb = new StringBuilder();
        foreach (var line in text.Split('\n'))
        {
            if (sb.Length + line.Length + 1 > max && sb.Length > 0) { outp.Add(sb.ToString()); sb.Clear(); }
            sb.Append(line.Length > max ? line[..max] : line).Append('\n');
        }
        if (sb.Length > 0) outp.Add(sb.ToString());
        return outp;
    }
}

/// Was nach der Spracherkennung mit einem Diktat passiert.
public static class TextPipeline
{
    public record Result(string Text, bool Snippet = false, bool Enhanced = false, string? Note = null);

    public static async Task<Result> FinishDictationAsync(AppSettings s, string raw, string language,
        FormatMode mode, bool enhance, CancellationToken ct)
    {
        var snippet = TextMatching.MatchSnippet(raw, s.Snippets);
        if (snippet != null) return new Result(snippet, Snippet: true);
        var text = s.SpokenCommands ? TextRules.ApplySpokenCommands(raw) : raw;
        if (!enhance || mode == FormatMode.Raw || string.IsNullOrWhiteSpace(text)) return new Result(text);
        if (!Enhancer.IsConfigured(s)) return new Result(text, Note: "Enhance: kein Sprachmodell eingerichtet");
        var lang = language.Length > 0 ? language : TextRules.GuessLanguage(text);
        text = TextRules.RemoveFillers(text, lang);
        var o = await Enhancer.EnhanceAsync(s, text, mode == FormatMode.Auto ? FormatMode.Standard : mode, lang, ct);
        if (o.Cancelled) return new Result(text, Note: "Enhance abgebrochen — Rohtext übernommen");
        return o.Text != null ? new Result(o.Text, Enhanced: true) : new Result(text, Note: "Enhance fehlgeschlagen: " + o.Error);
    }
}

/// Spracherkennung mit eigenen Wörtern und Ersetzungen — für alle Wege gleich.
public static class Transcriber
{
    private static readonly object gate = new();

    /// Leerer String bei Abbruch oder Fehler (WhisperNative.WasCancelled unterscheidet).
    public static string Transcribe(float[] samples, AppSettings s, string lang, bool translate)
    {
        string raw;
        lock (gate)
        {
            var language = string.IsNullOrEmpty(lang) ? "auto" : lang;
            if (WhisperNative.EnsureLoaded(WhisperCli.SelectedModel))
            {
                WhisperNative.SetPrompt(TextMatching.Prompt(s.Vocabulary));
                WhisperNative.SetTranslate(translate && !WhisperNative.IsParakeet);
                var native = WhisperNative.Transcribe(samples, language, s.ShortCtx);
                if (native == null) return "";
                raw = native;
            }
            else
            {
                var wav = Path.Combine(Path.GetTempPath(), "whisper_offline_rec.wav");
                WavWriter.Write16kMono(wav, samples);
                raw = WhisperCli.Transcribe(wav, lang, out _, TextMatching.Prompt(s.Vocabulary), translate);
            }
        }
        return Post(raw, s);
    }

    /// Zeilen säubern, Ersetzungen, dann eigene Wörter.
    public static string Post(string raw, AppSettings s)
    {
        var t = string.Join("\n", raw.Replace("\r\n", "\n").Split('\n').Select(l => l.Trim())).Trim();
        t = TextMatching.ApplyReplacements(t, s.Replacements);
        return WordFixer.Correct(t, s.Vocabulary);
    }

    /// Sprache für Enhance: gewählte, bei „Automatisch“ die erkannte.
    public static string DictationLanguage(AppSettings s) =>
        s.Language is "de" or "en" ? s.Language : WhisperNative.LastLanguage;
}

/// Live-Transkription während der Aufnahme: an Pausen festschreiben, Rest als Vorschau.
public sealed class LiveTranscriber
{
    private readonly ISampleSource source;
    private readonly AppSettings settings;
    private readonly string lang;
    private readonly bool translate;
    private readonly Action<string> onUpdate;
    private readonly CancellationTokenSource cts = new();
    private Task? loop;
    private int committedSamples;
    private readonly StringBuilder committed = new();
    private string preview = "";
    private const int SR = 16000;

    public LiveTranscriber(ISampleSource source, AppSettings settings, string lang, bool translate, Action<string> onUpdate)
    {
        this.source = source; this.settings = settings; this.lang = lang; this.translate = translate; this.onUpdate = onUpdate;
    }

    public void Start() => loop = Task.Run(async () =>
    {
        while (!cts.IsCancellationRequested)
        {
            try { await Task.Delay(1200, cts.Token); } catch { break; }
            Step();
        }
    });

    private void Step()
    {
        int total = source.SampleCount;
        int pending = total - committedSamples;
        if (pending < SR) return;
        var audio = source.Snapshot(committedSamples, total);
        int cut = Pauses.LastPause(audio, 2 * SR);
        if (cut > 0 || pending > 25 * SR)
        {
            int end = cut > 0 ? cut : audio.Length;
            var text = Transcriber.Transcribe(audio[..end], settings, lang, translate);
            if (text.Length > 0) committed.Append(text).Append(' ');
            committedSamples += end;
            preview = "";
        }
        else if (pending <= 12 * SR)
        {
            preview = Transcriber.Transcribe(audio, settings, lang, translate);
        }
        onUpdate((committed + preview).Trim());
    }

    /// Vorschau beenden, den Rest transkribieren, Gesamttext liefern.
    public async Task<string> FinishAsync(float[] all)
    {
        cts.Cancel();
        if (loop != null) await loop;
        int from = Math.Min(committedSamples, all.Length);
        var rest = all[from..];
        var restText = rest.Length > SR / 4 ? Transcriber.Transcribe(rest, settings, lang, translate) : "";
        return (committed + restText).Trim();
    }

    public void Stop() => cts.Cancel();
}
