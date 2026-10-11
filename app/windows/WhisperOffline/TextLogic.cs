using System.Text;
using System.Text.RegularExpressions;

namespace WhisperOffline;

// Reine Textlogik ohne Oberfläche — gleiches Verhalten wie die Kotlin-Fassung
// auf Android (Phonetics.kt, Vocabulary.kt, TextRules.kt, FormatMode.kt).
// Getestet in WhisperOffline.Tests mit denselben Fällen.

/// Kölner Phonetik und Editierdistanz für den unscharfen Wortabgleich.
public static class Phonetics
{
    public static string Cologne(string word)
    {
        var s = new string(word.ToLowerInvariant()
            .Replace("ä", "a").Replace("ö", "o").Replace("ü", "u").Replace("ß", "s")
            .Where(c => c is >= 'a' and <= 'z').ToArray());
        if (s.Length == 0) return "";
        var raw = new StringBuilder();
        for (int i = 0; i < s.Length; i++)
        {
            char c = s[i];
            char prev = i > 0 ? s[i - 1] : ' ';
            char next = i + 1 < s.Length ? s[i + 1] : ' ';
            raw.Append(c switch
            {
                'a' or 'e' or 'i' or 'j' or 'o' or 'u' or 'y' => "0",
                'h' => "",
                'b' => "1",
                'p' => next == 'h' ? "3" : "1",
                'd' or 't' => "csz".Contains(next) ? "8" : "2",
                'f' or 'v' or 'w' => "3",
                'g' or 'k' or 'q' => "4",
                'c' => i == 0 ? ("ahkloqrux".Contains(next) ? "4" : "8")
                    : "sz".Contains(prev) ? "8"
                    : "ahkoqux".Contains(next) ? "4" : "8",
                'x' => "ckq".Contains(prev) ? "8" : "48",
                'l' => "5",
                'm' or 'n' => "6",
                'r' => "7",
                's' or 'z' => "8",
                _ => "",
            });
        }
        var collapsed = new StringBuilder();
        foreach (var ch in raw.ToString())
            if (collapsed.Length == 0 || collapsed[^1] != ch) collapsed.Append(ch);
        var outp = new StringBuilder();
        for (int i = 0; i < collapsed.Length; i++)
            if (collapsed[i] != '0' || i == 0) outp.Append(collapsed[i]);
        return outp.ToString();
    }

    public static int Distance(string a, string b)
    {
        var x = a.ToLowerInvariant();
        var y = b.ToLowerInvariant();
        if (x == y) return 0;
        if (x.Length == 0) return y.Length;
        if (y.Length == 0) return x.Length;
        var prev = new int[y.Length + 1];
        var cur = new int[y.Length + 1];
        for (int j = 0; j <= y.Length; j++) prev[j] = j;
        for (int i = 1; i <= x.Length; i++)
        {
            cur[0] = i;
            for (int j = 1; j <= y.Length; j++)
            {
                int cost = x[i - 1] == y[j - 1] ? 0 : 1;
                cur[j] = Math.Min(Math.Min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
            }
            (prev, cur) = (cur, prev);
        }
        return prev[y.Length];
    }

    public static double Similarity(string a, string b)
    {
        int len = Math.Max(a.Length, b.Length);
        return len == 0 ? 1.0 : 1.0 - (double)Distance(a, b) / len;
    }

    public static string Compact(string s) =>
        new(s.ToLowerInvariant().Where(char.IsLetterOrDigit).ToArray());

    public static string NormalizePhrase(string s) =>
        string.Join(' ', new string(s.ToLowerInvariant().Select(c => char.IsLetterOrDigit(c) ? c : ' ').ToArray())
            .Split(' ', StringSplitOptions.RemoveEmptyEntries));
}

/// Ersetzt ähnlich klingende Wortfolgen durch Einträge der Wortliste.
public static class WordFixer
{
    private record Token(int Start, int End, string Lead, string Core, string Trail);

    private static List<Token> Tokenize(string text)
    {
        var list = new List<Token>();
        foreach (Match m in Regex.Matches(text, @"\S+"))
        {
            var s = m.Value;
            int a = Array.FindIndex(s.ToCharArray(), char.IsLetterOrDigit);
            int b = Array.FindLastIndex(s.ToCharArray(), char.IsLetterOrDigit);
            list.Add(a < 0
                ? new Token(m.Index, m.Index + m.Length, s, "", "")
                : new Token(m.Index, m.Index + m.Length, s[..a], s[a..(b + 1)], s[(b + 1)..]));
        }
        return list;
    }

    /// Wie gut passt eine erkannte Wortfolge zum Zielwort? null = gar nicht.
    public static double? Score(string heard, string target)
    {
        var h = Phonetics.Compact(heard);
        var t = Phonetics.Compact(target);
        if (h.Length == 0 || t.Length == 0) return null;
        if (h == t) return 1.0;
        if (t.Length < 4) return null;
        double ratio = (double)h.Length / t.Length;
        if (ratio < 0.6 || ratio > 1.6) return null;
        double sim = Phonetics.Similarity(h, t);
        var ph = Phonetics.Cologne(t);
        bool sameSound = ph.Length >= 2 && Phonetics.Cologne(h) == ph;
        if (sameSound && sim >= 0.5) return sim + 0.2;
        if (t.Length >= 6 && sim >= 0.8) return sim;
        return null;
    }

    public static string Correct(string text, IEnumerable<string> words)
    {
        var list = words.ToList();
        if (list.Count == 0 || string.IsNullOrWhiteSpace(text)) return text;
        foreach (var w in list.OrderByDescending(w => w.Length)) text = CorrectOne(text, w.Trim());
        return text;
    }

    private static string CorrectOne(string text, string word)
    {
        if (word.Length == 0) return text;
        int n = Regex.Split(word, @"\s+").Length;
        var tokens = Tokenize(text);
        if (tokens.Count == 0) return text;
        var sizes = new[] { n, n + 1, n - 1 }.Where(k => k >= 1).Distinct().ToArray();
        var sb = new StringBuilder();
        int cursor = 0, i = 0;
        while (i < tokens.Count)
        {
            (int k, double s)? best = null;
            foreach (var k in sizes)
            {
                if (i + k > tokens.Count) continue;
                var win = tokens.GetRange(i, k);
                if (win.Any(t => t.Core.Length == 0)) continue;
                if (win.Take(k - 1).Any(t => t.Trail.Length > 0) || win.Skip(1).Any(t => t.Lead.Length > 0)) continue;
                var sc = Score(string.Join(' ', win.Select(t => t.Core)), word);
                if (sc == null) continue;
                if (best == null || sc.Value > best.Value.s) best = (k, sc.Value);
            }
            if (best == null) { i++; continue; }
            var first = tokens[i];
            var last = tokens[i + best.Value.k - 1];
            sb.Append(text, cursor, first.Start - cursor).Append(first.Lead + word + last.Trail);
            cursor = last.End;
            i += best.Value.k;
        }
        sb.Append(text, cursor, text.Length - cursor);
        return sb.ToString();
    }
}

public record Snippet(string Trigger, string Text);

public record ReplacementRule(string From, string To);

public static class TextMatching
{
    /// Ganzes Diktat == Trigger (ohne Satzzeichen/Großschreibung, kleine
    /// Abweichungen erlaubt) → hinterlegter Text.
    public static string? MatchSnippet(string text, IEnumerable<Snippet> snippets)
    {
        var heard = Phonetics.NormalizePhrase(text);
        if (heard.Length == 0) return null;
        (Snippet s, double sim)? best = null;
        foreach (var s in snippets)
        {
            var t = Phonetics.NormalizePhrase(s.Trigger);
            if (t.Length == 0) continue;
            double sim = heard == t ? 1.0 : Phonetics.Similarity(heard, t);
            bool ok = sim == 1.0 || (t.Length >= 6 && sim >= 0.85);
            if (ok && (best == null || sim > best.Value.sim)) best = (s, sim);
        }
        return best?.s.Text;
    }

    /// Ganze Wörter, Groß-/Kleinschreibung egal: „Max“ ersetzt nicht das „max“ in „maximal“.
    public static string ApplyReplacements(string text, IEnumerable<ReplacementRule> rules)
    {
        foreach (var r in rules)
        {
            if (string.IsNullOrWhiteSpace(r.From)) continue;
            text = Regex.Replace(text, @"(?<![\p{L}\p{N}])" + Regex.Escape(r.From.Trim()) + @"(?![\p{L}\p{N}])",
                r.To.Replace("$", "$$"), RegexOptions.IgnoreCase);
        }
        return text;
    }

    /// Whisper-Prompt aus der Wortliste (höchstens ~800 Zeichen).
    public static string Prompt(IEnumerable<string> words)
    {
        var sb = new StringBuilder();
        foreach (var w in words)
        {
            if (sb.Length + w.Length + 2 > 800) break;
            if (sb.Length > 0) sb.Append(", ");
            sb.Append(w);
        }
        return sb.Length == 0 ? "" : sb.Append('.').ToString();
    }
}

/// Regelbasierte Textschritte rund ums Diktat.
public static class TextRules
{
    private const string Sep = @"[\s,.;:!?]*";

    private static readonly (string words, string output)[] Layout =
    {
        ("neuer absatz|neuen absatz|new paragraph", "\n\n"),
        ("neue zeile|zeilenumbruch|new line|newline", "\n"),
    };

    private static readonly (string words, string output)[] Punct =
    {
        ("komma|comma", ","),
        ("punkt|period|full stop", "."),
        ("fragezeichen|question mark", "?"),
        ("ausrufezeichen|exclamation mark|exclamation point", "!"),
        ("doppelpunkt|colon", ":"),
        ("semikolon|semicolon", ";"),
    };

    public static string ApplySpokenCommands(string text)
    {
        var s = text;
        foreach (var (words, output) in Layout)
            s = Regex.Replace(s, Sep + @"(?<![\p{L}\p{N}])(" + words + @")(?![\p{L}\p{N}])" + Sep, _ => output, RegexOptions.IgnoreCase);
        foreach (var (words, output) in Punct)
            s = Regex.Replace(s, @"[ \t]*[,.;:]?[ \t]*(?<![\p{L}\p{N}])(" + words + @")(?![\p{L}\p{N}])[.,;:]?", _ => output, RegexOptions.IgnoreCase);
        return Tidy(s);
    }

    private const string FillersDe = "äh|ähm|öhm|ehm|hm|hmm|mhm";
    private const string FillersEn = "uh|uhm|um|umm|erm|hmm|mhm";

    public static string RemoveFillers(string text, string language)
    {
        var words = language == "en" ? FillersDe + "|" + FillersEn : FillersDe;
        var s = Tidy(Regex.Replace(text, @"(?<![\p{L}\p{N}])(" + words + @")(?![\p{L}\p{N}])[,.]?", "", RegexOptions.IgnoreCase));
        return text.Length > 0 && char.IsUpper(text[0]) && s.Length > 0 ? char.ToUpper(s[0]) + s[1..] : s;
    }

    public static string Tidy(string text)
    {
        var s = Regex.Replace(text, @"[ \t]+([,.;:!?])", "$1");
        s = Regex.Replace(s, @"([,;:])(?=\p{L})", "$1 ");
        s = Regex.Replace(s, @"[ \t]{2,}", " ");
        s = string.Join("\n", s.Replace("\r\n", "\n").Split('\n').Select(l => l.Trim()));
        s = Regex.Replace(s, @"\n{3,}", "\n\n");
        s = Regex.Replace(s, @"^[,.;:]\s*", "");
        return s.Trim();
    }

    public static string CapitalizeSentences(string text)
    {
        var sb = new StringBuilder(text);
        bool start = true;
        for (int i = 0; i < sb.Length; i++)
        {
            char c = sb[i];
            if (start && char.IsLetter(c)) { sb[i] = char.ToUpper(c); start = false; }
            else if (c is '.' or '!' or '?')
            {
                char? next = i + 1 < sb.Length ? sb[i + 1] : null;
                char? prev = i > 0 ? sb[i - 1] : null;
                start = next == null || (char.IsWhiteSpace(next.Value) && !(c == '.' && prev != null && char.IsDigit(prev.Value)));
            }
            else if (c == '\n') start = true;
            else if (start && !char.IsWhiteSpace(c) && c is not ('-' or '*' or '[' or ']' or '"' or '„')) start = false;
        }
        return sb.ToString();
    }

    private static readonly HashSet<string> DeHints = new()
    {
        "und", "der", "die", "das", "ist", "nicht", "ich", "wir", "sie", "mit", "auf", "ein", "eine",
        "zu", "es", "du", "dass", "für", "von", "den", "dem", "auch", "noch", "aber", "wie", "bitte", "danke", "schon",
    };
    private static readonly HashSet<string> EnHints = new()
    {
        "the", "and", "is", "not", "you", "we", "they", "with", "on", "a", "an", "to", "it",
        "that", "for", "of", "this", "are", "was", "have", "be", "please", "thanks", "but", "what", "will",
    };

    public static string GuessLanguage(string text)
    {
        var words = Regex.Split(text.ToLowerInvariant(), @"[^\p{L}]+").Where(w => w.Length > 0).ToList();
        int de = words.Count(DeHints.Contains) + words.Count(w => w.Any(ch => "äöüß".Contains(ch)));
        int en = words.Count(EnHints.Contains);
        if (de == 0 && en == 0) return "";
        return de >= en ? "de" : "en";
    }
}

/// Wie Enhance ein Diktat formatiert.
public enum FormatMode { Auto, Standard, Email, Chat, Note, Document, Code, Todo, Subject, Raw }

public static class FormatModes
{
    public static string Key(this FormatMode m) => m.ToString().ToLowerInvariant();

    public static string Label(this FormatMode m) => m switch
    {
        FormatMode.Auto => "Automatisch",
        FormatMode.Standard => "Standard",
        FormatMode.Email => "E-Mail",
        FormatMode.Chat => "Chat",
        FormatMode.Note => "Notiz",
        FormatMode.Document => "Dokument",
        FormatMode.Code => "Code",
        FormatMode.Todo => "Aufgabenliste",
        FormatMode.Subject => "Betreff",
        _ => "Roh",
    };

    public static readonly FormatMode[] Selectable =
    {
        FormatMode.Auto, FormatMode.Standard, FormatMode.Email, FormatMode.Chat, FormatMode.Note,
        FormatMode.Document, FormatMode.Code, FormatMode.Todo, FormatMode.Raw,
    };

    private static readonly HashSet<string> Chat = new(StringComparer.OrdinalIgnoreCase)
        { "whatsapp", "whatsapp.root", "telegram", "signal", "discord", "slack", "ms-teams", "teams", "element", "threema", "zoom" };
    private static readonly HashSet<string> Mail = new(StringComparer.OrdinalIgnoreCase)
        { "outlook", "olk", "thunderbird", "hxoutlook", "mailspring", "em client", "mailclient" };
    private static readonly HashSet<string> Note = new(StringComparer.OrdinalIgnoreCase)
        { "onenote", "obsidian", "notion", "notepad", "evernote", "joplin", "logseq", "stickynot" };
    private static readonly HashSet<string> Doc = new(StringComparer.OrdinalIgnoreCase)
        { "winword", "soffice.bin", "swriter", "wps", "wordpad" };
    private static readonly HashSet<string> Code = new(StringComparer.OrdinalIgnoreCase)
        { "code", "cursor", "devenv", "rider64", "idea64", "pycharm64", "windowsterminal", "powershell", "pwsh", "cmd", "wt", "sublime_text", "notepad++", "nvim-qt" };
    private static readonly HashSet<string> Todo = new(StringComparer.OrdinalIgnoreCase)
        { "todoist", "microsoft.todos", "ticktick" };
    private static readonly HashSet<string> Browsers = new(StringComparer.OrdinalIgnoreCase)
        { "chrome", "msedge", "firefox", "brave", "opera", "vivaldi", "arc", "librewolf" };

    /// Modus aus Prozessname (ohne .exe) und Fenstertitel. Im Browser verrät
    /// der Titel Webmail und Web-Chats; der Titel wird nirgends gespeichert.
    public static FormatMode Detect(string process, string title, FormatMode? profile = null)
    {
        if (profile is { } p && p != FormatMode.Auto) return p;
        var proc = process.ToLowerInvariant().Replace(".exe", "");
        if (Chat.Contains(proc)) return FormatMode.Chat;
        if (Mail.Contains(proc)) return FormatMode.Email;
        if (Note.Contains(proc)) return FormatMode.Note;
        if (Doc.Contains(proc)) return FormatMode.Document;
        if (Code.Contains(proc)) return FormatMode.Code;
        if (Todo.Contains(proc)) return FormatMode.Todo;
        if (Browsers.Contains(proc))
        {
            var t = title.ToLowerInvariant();
            if (t.Contains("gmail") || t.Contains("outlook") || t.Contains("posteingang") || t.Contains("inbox") ||
                t.Contains("gmx") || t.Contains("web.de") || t.Contains("proton mail") || t.Contains("mail -")) return FormatMode.Email;
            if (t.Contains("whatsapp") || t.Contains("telegram") || t.Contains("slack") || t.Contains("discord") ||
                t.Contains("teams") || t.Contains("element")) return FormatMode.Chat;
            if (t.Contains("google docs") || t.Contains("word")) return FormatMode.Document;
            if (t.Contains("notion") || t.Contains("keep")) return FormatMode.Note;
        }
        return FormatMode.Standard;
    }
}

/// Pausen im Audio finden (Live-Transkription, Meeting ohne Sprechermodelle).
public static class Pauses
{
    private const int Frame = 480;

    public static int LastPause(float[] audio, int minSpeechSamples, int minPauseFrames = 14)
    {
        int n = audio.Length / Frame;
        if (n < minPauseFrames + 2) return -1;
        var rms = new float[n];
        for (int f = 0; f < n; f++)
        {
            double sum = 0;
            for (int i = f * Frame; i < (f + 1) * Frame; i++) sum += audio[i] * audio[i];
            rms[f] = (float)Math.Sqrt(sum / Frame);
        }
        float peak = rms.Max();
        if (peak < 0.005f) return -1;
        float quiet = Math.Max(0.004f, peak * 0.08f);
        int run = 0;
        for (int f = n - 1; f >= 0; f--)
        {
            if (rms[f] < quiet) run++;
            else
            {
                if (run >= minPauseFrames)
                {
                    int mid = (f + 1 + run / 2) * Frame;
                    if (mid >= minSpeechSamples) return mid;
                }
                run = 0;
            }
        }
        return -1;
    }
}

/// Sprechabschnitte über die Lautstärke finden (eigene Stimme im Meeting:
/// Mikrofonspur ohne Sprechertrennung).
public static class SpeechRegions
{
    private const int Frame = 480; // 30 ms

    /// Abschnitte (Start, Ende) in Sekunden. Lücken unter [mergeGap] werden
    /// überbrückt, Abschnitte unter [minLength] fallen weg.
    public static List<(float Start, float End)> Find(float[] audio, float mergeGap = 0.6f, float minLength = 0.4f)
    {
        int n = audio.Length / Frame;
        var rms = new float[n];
        for (int f = 0; f < n; f++)
        {
            double sum = 0;
            for (int i = f * Frame; i < (f + 1) * Frame; i++) sum += audio[i] * audio[i];
            rms[f] = (float)Math.Sqrt(sum / Frame);
        }
        var outp = new List<(float, float)>();
        if (n == 0) return outp;
        // Schwelle relativ zu einem lauten, aber nicht dem lautesten Rahmen (Knackser)
        var sorted = rms.OrderBy(x => x).ToArray();
        float loud = sorted[(int)(sorted.Length * 0.95)];
        float thr = Math.Max(0.006f, loud * 0.12f);
        int start = -1, lastSpeech = -1;
        int gap = (int)(mergeGap * 16000 / Frame);
        for (int f = 0; f < n; f++)
        {
            if (rms[f] >= thr)
            {
                if (start < 0) start = f;
                else if (f - lastSpeech > gap) { Close(start, lastSpeech); start = f; }
                lastSpeech = f;
            }
        }
        if (start >= 0) Close(start, lastSpeech);
        return outp;

        void Close(int a, int b)
        {
            float s = a * Frame / 16000f, e = (b + 1) * Frame / 16000f;
            if (e - s >= minLength) outp.Add((Math.Max(0, s - 0.15f), e + 0.15f));
        }
    }
}

/// Sprecher über Fenster hinweg zuordnen, Redebeiträge bilden.
public static class SpeakerMath
{
    public static float Cosine(float[] a, float[] b)
    {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < Math.Min(a.Length, b.Length); i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return na == 0 || nb == 0 ? 0f : (float)(dot / (Math.Sqrt(na) * Math.Sqrt(nb)));
    }

    /// Ab dieser Ähnlichkeit gilt ein Stimmabdruck als dieselbe Person
    /// (gemessen mit WeSpeaker ResNet34: verschiedene ~0,5, dieselbe 0,7–0,97).
    public const float SameSpeaker = 0.62f;

    public sealed class Registry
    {
        private readonly float threshold;
        private readonly int maxSpeakers;
        public readonly List<float[]> Centroids = new();
        private readonly List<int> counts = new();

        public Registry(float threshold, int maxSpeakers) { this.threshold = threshold; this.maxSpeakers = maxSpeakers; }

        public int Assign(float[] e)
        {
            int best = -1; float bestSim = -1;
            for (int i = 0; i < Centroids.Count; i++)
            {
                var s = Cosine(Centroids[i], e);
                if (s > bestSim) { bestSim = s; best = i; }
            }
            bool full = maxSpeakers > 0 && Centroids.Count >= maxSpeakers;
            if (best >= 0 && (bestSim >= threshold || full))
            {
                int n = counts[best];
                var c = Centroids[best];
                Centroids[best] = c.Select((v, i) => (v * n + e[i]) / (n + 1)).ToArray();
                counts[best] = n + 1;
                return best;
            }
            Centroids.Add((float[])e.Clone());
            counts.Add(1);
            return Centroids.Count - 1;
        }
    }

    public record Turn(int Speaker, float Start, float End);

    public static List<Turn> MergeTurns(IEnumerable<Turn> turns, float maxGap = 1.0f, float maxLen = 28f)
    {
        var outp = new List<Turn>();
        foreach (var t in turns.OrderBy(t => t.Start))
        {
            var last = outp.Count > 0 ? outp[^1] : null;
            if (last != null && last.Speaker == t.Speaker && t.Start - last.End <= maxGap && t.End - last.Start <= maxLen)
            {
                outp[^1] = last with { End = Math.Max(last.End, t.End) };
            }
            else
            {
                float s = t.Start;
                while (t.End - s > maxLen) { outp.Add(new Turn(t.Speaker, s, s + maxLen)); s += maxLen; }
                outp.Add(new Turn(t.Speaker, s, t.End));
            }
        }
        return outp;
    }
}

/// Kennzahlen aus dem Verlauf.
public static class Stats
{
    public record Result(int Count, int Words, float AudioSeconds, float AvgSeconds, float SavedSeconds,
        List<(string Label, int Words)> PerDay, List<(string Language, int Count)> Languages);

    /// Tippgeschwindigkeit am PC, Wörter pro Minute.
    public const float TypingWpm = 40f;

    public static int WordCount(string text) =>
        Regex.Split(text, @"\s+").Count(w => w.Any(char.IsLetterOrDigit));

    public static Result Compute(IEnumerable<HistoryEntry> all, int days, string? source, DateTimeOffset now)
    {
        long since = days > 0 ? now.AddDays(-days).ToUnixTimeMilliseconds() : 0;
        var entries = all.Where(e => e.TimeMs >= since && source switch
        {
            null => true,
            "dictation" => e.Source is "app" or "keyboard" or "dictation",
            _ => e.Source == source,
        }).ToList();
        int words = entries.Sum(e => WordCount(e.Text));
        float audio = entries.Sum(e => e.AudioSeconds);
        float typing = words / TypingWpm * 60f;
        int span = days > 0 ? days : 30;
        var local = now.ToLocalTime().Date;
        var perDay = Enumerable.Range(0, span).Select(i => local.AddDays(-(span - 1 - i))).Select(d =>
            (d.ToString("dd.MM."), entries.Where(e => DateTimeOffset.FromUnixTimeMilliseconds(e.TimeMs).ToLocalTime().Date == d)
                .Sum(e => WordCount(e.Text)))).ToList();
        var langs = entries.Where(e => e.Source != "selection").GroupBy(e => e.Language)
            .Select(g => (g.Key, g.Count())).OrderByDescending(x => x.Item2).ToList();
        return new Result(entries.Count, words, audio, entries.Count == 0 ? 0 : audio / entries.Count,
            Math.Max(0, typing - audio), perDay, langs);
    }
}
