using System.IO;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace WhisperOffline;

// ---------------------------------------------------------------------------
// Smart Routing mit TypeSafe Jev (https://api.typesafe.ai/v1/systemone)
//
// Portiert von Jev.kt (Android) — Fragen, Schwellenwerte und Fehlertoleranz
// sind bewusst identisch, damit beide Apps gleich entscheiden.
//
// Jev ist kein generatives Modell, sondern ein Entscheidungsmodell: Eine
// Anfrage beantwortet mehrere typisierte Fragen (choice / score / noul) mit
// kalibrierten Wahrscheinlichkeiten. Die App bleibt offline-first — jeder
// Fehler (kein Netz, Timeout, ungültiger Key …) führt zum Rohtranskript.
// ---------------------------------------------------------------------------

/// Eine Frage an Jev. Das Schema ist strikt: `choice` braucht ein Objekt als
/// `criteria`, `score` ein Array (2–10 Einträge), `noul` ein Objekt mit
/// „true“/„false“ — sonst antwortet die API mit HTTP 422.
public abstract record JevQuestion
{
    public sealed record Choice(string Instructions, IReadOnlyDictionary<string, string> Criteria) : JevQuestion
    {
        public override JsonObject ToJson()
        {
            var criteria = new JsonObject();
            foreach (var (k, v) in Criteria) criteria[k] = v;
            return new JsonObject
            {
                ["type"] = "choice",
                ["instructions"] = Instructions,
                ["criteria"] = criteria,
            };
        }
    }

    /// Der Score läuft von 0 bis `criteria.Count - 1`.
    public sealed record Score(string Instructions, IReadOnlyList<string> Criteria) : JevQuestion
    {
        public override JsonObject ToJson()
        {
            var criteria = new JsonArray();
            foreach (var c in Criteria) criteria.Add(c);
            return new JsonObject
            {
                ["type"] = "score",
                ["instructions"] = Instructions,
                ["criteria"] = criteria,
            };
        }
    }

    /// Ja/Nein-Frage; ein hoher Wert (→ 1.0) bedeutet immer „trifft zu“.
    public sealed record Noul(string Instructions, string WhenTrue, string WhenFalse) : JevQuestion
    {
        public override JsonObject ToJson() => new()
        {
            ["type"] = "noul",
            ["instructions"] = Instructions,
            ["criteria"] = new JsonObject { ["true"] = WhenTrue, ["false"] = WhenFalse },
        };
    }

    public abstract JsonObject ToJson();
}

public sealed record JevRequest(string Model, JsonObject State, IReadOnlyDictionary<string, JevQuestion> Questions)
{
    public JsonObject ToJson()
    {
        var questions = new JsonObject();
        foreach (var (id, q) in Questions) questions[id] = q.ToJson();
        return new JsonObject
        {
            ["model"] = Model,
            ["state"] = State,
            ["questions"] = questions,
        };
    }
}

/// Antwort auf eine einzelne Frage (`answers.<id>`).
public abstract record JevAnswer
{
    public sealed record Choice(string Picked, IReadOnlyDictionary<string, double> Probabilities, double Confidence) : JevAnswer
    {
        /// Optionen absteigend nach Wahrscheinlichkeit.
        public IReadOnlyList<(string Key, double P)> Ranked() =>
            Probabilities.Select(kv => (kv.Key, kv.Value)).OrderByDescending(p => p.Value).ToList();

        /// Zweitwahrscheinlichste Option (ungleich der gewählten) für den Quick-Switch.
        public string? Alternative() => Ranked().FirstOrDefault(p => p.Key != Picked).Key;
    }

    public sealed record Score(double Value, IReadOnlyDictionary<string, string> Legend,
        IReadOnlyDictionary<string, double> Probabilities, double Confidence) : JevAnswer
    {
        /// Beschriftung der nächstliegenden Stufe — die `legend` hat String-Schlüssel „0“, „1“ …
        public string? LegendLabel() => Legend.TryGetValue(Math.Round(Value, MidpointRounding.AwayFromZero)
            .ToString(System.Globalization.CultureInfo.InvariantCulture), out var label) ? label : null;
    }

    /// `noul` ist selbst die Sicherheit: nahe 1.0 = sicher ja, nahe 0.0 = sicher nein, 0.5 = unsicher.
    public sealed record Noul(double Value) : JevAnswer;
}

public sealed record JevResponse(string Model, IReadOnlyDictionary<string, JevAnswer> Answers)
{
    /// Wirft JsonException bei unerwartetem Aufbau.
    public static JevResponse FromJson(JsonNode root)
    {
        var answers = (JsonObject)root["answers"]!;
        var parsed = new Dictionary<string, JevAnswer>();
        foreach (var (id, node) in answers)
        {
            var o = (JsonObject)node!;
            parsed[id] = (string)o["type"]! switch
            {
                "choice" => new JevAnswer.Choice((string)o["choice"]!, Doubles((JsonObject)o["probabilities"]!),
                    (double)o["confidence"]!),
                "score" => new JevAnswer.Score((double)o["score"]!, Strings((JsonObject)o["legend"]!),
                    Doubles((JsonObject)o["probabilities"]!), (double)o["confidence"]!),
                "noul" => new JevAnswer.Noul((double)o["noul"]!),
                _ => throw new JsonException("Unbekannter Antworttyp"),
            };
        }
        return new JevResponse((string?)root["model"] ?? "", parsed);

        static Dictionary<string, double> Doubles(JsonObject o) =>
            o.ToDictionary(kv => kv.Key, kv => (double)kv.Value!);
        static Dictionary<string, string> Strings(JsonObject o) =>
            o.ToDictionary(kv => kv.Key, kv => (string)kv.Value!);
    }
}

// ---------------------------------------------------------------------------
// HTTP-Client
// ---------------------------------------------------------------------------

public static class JevClient
{
    public const string Endpoint = "https://api.typesafe.ai/v1/systemone";
    public const int TimeoutMs = 1500;

    private const int RetryBackoffMs = 250;
    private const int WarmUpIntervalMs = 60_000;
    private const int WarmUpTimeoutMs = 5000;

    // Unbenutzte Verbindungen 5 statt 1 Minute halten: die per WarmUp
    // aufgebaute Verbindung soll auch längere Transkriptionen überdauern.
    private static readonly HttpClient http = new(new SocketsHttpHandler
    {
        PooledConnectionIdleTimeout = TimeSpan.FromMinutes(5),
    }) { Timeout = Timeout.InfiniteTimeSpan };

    private static long lastWarmUp;

    public enum Error { Timeout, Network, Unauthorized, Schema, RateLimited, Server, InvalidResponse }

    public abstract record Result
    {
        public sealed record Success(JevResponse Response) : Result;
        public sealed record Failure(Error Error, string Message) : Result;
    }

    /// Schickt [request] und gibt nach spätestens [timeoutMs] ein Ergebnis zurück.
    /// Seiteneffekt: Netzwerkzugriff. Der API-Key wird nie geloggt.
    public static async Task<Result> SendAsync(JevRequest request, string apiKey, int timeoutMs = TimeoutMs)
    {
        using var cts = new CancellationTokenSource(timeoutMs);
        try
        {
            return await ExecuteAsync(request, apiKey, timeoutMs, cts.Token);
        }
        catch (OperationCanceledException) { return new Result.Failure(Error.Timeout, "Zeitüberschreitung"); }
        catch (HttpRequestException) { return new Result.Failure(Error.Network, "Keine Verbindung"); }
        catch (IOException) { return new Result.Failure(Error.Network, "Keine Verbindung"); }
        catch (JsonException) { return new Result.Failure(Error.InvalidResponse, "Unerwartete Antwort"); }
    }

    /// Baut die Verbindung vorab auf (DNS, TLS), damit die Anfrage nach der
    /// Transkription ins Zeitbudget passt — kalt brauchte die erste Anfrage
    /// länger als 1,5 s (gemessen unter Android). Schickt nur einen leeren HEAD
    /// ohne Key und ohne Text (Antwort 405); die Verbindung bleibt im Pool und
    /// der POST verwendet sie wieder. Höchstens einmal pro Minute, Fehler egal.
    public static void WarmUp()
    {
        long now = Environment.TickCount64;
        long last = Interlocked.Read(ref lastWarmUp);
        if (last != 0 && now - last < WarmUpIntervalMs) return;
        Interlocked.Exchange(ref lastWarmUp, now);
        _ = Task.Run(async () =>
        {
            try
            {
                using var cts = new CancellationTokenSource(WarmUpTimeoutMs);
                using var req = new HttpRequestMessage(HttpMethod.Head, Endpoint);
                using var resp = await http.SendAsync(req, cts.Token);
            }
            catch (Exception)
            {
                // Offline o. Ä.: die eigentliche Anfrage fällt dann ohnehin aufs Rohtranskript zurück
            }
        });
    }

    /// 429/529: ein einziger kurzer Retry, solange das Zeitbudget es zulässt.
    private static async Task<Result> ExecuteAsync(JevRequest request, string apiKey, int timeoutMs, CancellationToken ct)
    {
        long deadline = Environment.TickCount64 + timeoutMs;
        var result = await PostAsync(request, apiKey, ct);
        if (result is Result.Failure { Error: Error.RateLimited }
            && deadline - Environment.TickCount64 > RetryBackoffMs + 200)
        {
            await Task.Delay(RetryBackoffMs, ct);
            result = await PostAsync(request, apiKey, ct);
        }
        return result;
    }

    private static async Task<Result> PostAsync(JevRequest request, string apiKey, CancellationToken ct)
    {
        using var req = new HttpRequestMessage(HttpMethod.Post, Endpoint);
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", apiKey);
        req.Content = new StringContent(request.ToJson().ToJsonString(), Encoding.UTF8, "application/json");
        using var resp = await http.SendAsync(req, ct);
        switch ((int)resp.StatusCode)
        {
            case 200:
                return new Result.Success(JevResponse.FromJson(JsonNode.Parse(
                    await resp.Content.ReadAsStringAsync(ct))!));
            case 401:
                return new Result.Failure(Error.Unauthorized, "API-Key ungültig");
            case 422:
                return new Result.Failure(Error.Schema, "Anfrage vom Server abgelehnt (HTTP 422)");
            case 429:
            case 529:
                return new Result.Failure(Error.RateLimited, $"Dienst ausgelastet (HTTP {(int)resp.StatusCode})");
            default:
                return new Result.Failure(Error.Server, $"Serverfehler (HTTP {(int)resp.StatusCode})");
        }
    }
}

// ---------------------------------------------------------------------------
// Fragen, Zustand und Entscheidungslogik
// ---------------------------------------------------------------------------

/// Eigenes Projekt für `project_context`: [Key] kommt als Schlüssel in die
/// Kriterien, [Description] erklärt Jev, was dazugehört.
public sealed record JevProjectTag(string Key, string Description);

/// Ergebnis der Klassifikation — das, was am Transkript hängen bleibt.
public sealed record JevAnalysis(
    string Intent,
    double IntentConfidence,
    string? TopAlternativeIntent,
    string? ProjectTag,
    double? UrgencyScore,
    bool? IsActionable,
    bool? NeedsCleanup)
{
    /// Mittlere Sicherheit: die App bietet die Alternative als Ein-Tipp-Chip an.
    public bool NeedsDisambiguation =>
        TopAlternativeIntent != null &&
        IntentConfidence >= JevDecision.MediumConfidence && IntentConfidence < JevDecision.HighConfidence;
}

/// [Discard]: Rauschen (Mikrofontest, Gemurmel) — nicht als Notiz speichern.
public sealed record JevDecisionResult(JevAnalysis Analysis, bool Discard);

public static class JevDecision
{
    public const double HighConfidence = 0.80;
    public const double MediumConfidence = 0.50;
    public const double CleanupThreshold = 0.70;
    public const double ActionableThreshold = 0.70;
    public const double NoiseConfidence = 0.85;
    public const double NoiseMaxSeconds = 3.0;

    public const string IntentFallback = "general_note";
    public const string IntentNoise = "noise_or_empty";
    public const string ProjectUnassigned = "unassigned";

    public const string QIntent = "intent";
    public const string QProject = "project_context";
    public const string QUrgency = "urgency";
    public const string QCleanup = "needs_text_cleanup";
    public const string QActionable = "is_actionable";

    /// Übersetzt die Antworten in Entscheidungen:
    /// - Intent ab 0,80 automatisch; 0,50–0,80 mit Alternative zum Umschalten;
    ///   darunter `general_note` ohne weitere Aktion.
    /// - Projekt nur ab 0,80 und nie „unassigned“ — ein falsches Tag ist
    ///   schlimmer als keins.
    /// - Dringlichkeit ab 0,50, sonst unbekannt.
    /// - `NeedsCleanup` ab noul 0,70; `IsActionable` nur bei klarer Antwort
    ///   (≥ 0,70 / ≤ 0,30), dazwischen unbekannt.
    /// Liefert null, wenn die Intent-Antwort fehlt.
    public static JevDecisionResult? Decide(JevResponse response, double durationSeconds)
    {
        if (!response.Answers.TryGetValue(QIntent, out var intentObj) || intentObj is not JevAnswer.Choice intent)
            return null;
        var project = response.Answers.GetValueOrDefault(QProject) as JevAnswer.Choice;
        var urgency = response.Answers.GetValueOrDefault(QUrgency) as JevAnswer.Score;
        var cleanup = response.Answers.GetValueOrDefault(QCleanup) as JevAnswer.Noul;
        var actionable = response.Answers.GetValueOrDefault(QActionable) as JevAnswer.Noul;

        bool confident = intent.Confidence >= MediumConfidence;
        var analysis = new JevAnalysis(
            Intent: confident ? intent.Picked : IntentFallback,
            IntentConfidence: intent.Confidence,
            TopAlternativeIntent: confident ? intent.Alternative() : null,
            ProjectTag: project is { } p && p.Confidence >= HighConfidence &&
                        p.Picked != ProjectUnassigned && confident ? p.Picked : null,
            UrgencyScore: urgency is { } u && u.Confidence >= MediumConfidence && confident ? u.Value : null,
            IsActionable: actionable is { } a
                ? a.Value switch
                {
                    >= ActionableThreshold => true,
                    <= 1 - ActionableThreshold => false,
                    _ => null,
                }
                : null,
            NeedsCleanup: cleanup is { } c && c.Value >= CleanupThreshold);
        bool discard = intent.Picked == IntentNoise && intent.Confidence >= NoiseConfidence &&
                       durationSeconds < NoiseMaxSeconds;
        return new JevDecisionResult(analysis, discard);
    }

    /// Schlüssel säubern: kein Leerraum, keine Dubletten, nie „unassigned“
    /// (wird automatisch ergänzt), höchstens [MaxTags] (API-Limit: 255 Keys).
    public static List<JevProjectTag> SanitizeTags(IEnumerable<JevProjectTag> tags) =>
        tags.Select(t => t with { Key = t.Key.Trim(), Description = t.Description.Trim() })
            .Where(t => t.Key.Length > 0 && t.Key != ProjectUnassigned)
            .DistinctBy(t => t.Key)
            .Take(MaxTags)
            .ToList();

    public const int MaxTags = 100;

    /// Wörter wie im Verlauf gezählt — Jev kann nicht zuverlässig zählen,
    /// deshalb rechnet die App vor.
    public static int WordCount(string text) => HistoryEntry.CountWords(text);

    /// Zeigt „whisper_app“ als „Whisper App“.
    public static string PrettyKey(string key) =>
        string.Join(" ", key.Split('_', ' ')
            .Where(w => w.Length > 0)
            .Select(w => char.ToUpper(w[0]) + w[1..]));

    public static readonly IReadOnlyDictionary<string, string> IntentLabels = new Dictionary<string, string>
    {
        ["todo_task"] = "Aufgabe",
        ["code_or_bug"] = "Code/Bug",
        ["calendar_event"] = "Termin",
        ["message_draft"] = "Nachricht",
        ["general_note"] = "Notiz",
        ["voice_command"] = "Befehl",
        [IntentNoise] = "Rauschen",
    };

    public static string IntentLabel(string intent) =>
        IntentLabels.GetValueOrDefault(intent) ?? PrettyKey(intent);
}

/// Baut Anfragen. Die Texte sind wörtlich gemeint: Jev liest `instructions`
/// und `criteria` buchstäblich, sie dürfen sich nie widersprechen.
public static class JevQuestions
{
    public const string DefaultModel = "jev-1.13.0";

    /// Startliste der Projekt-Tags; im Einstellungsdialog änderbar.
    public static readonly List<JevProjectTag> DefaultTags = new()
    {
        new("whisper_app", "Spracherkennung, Audio-App, Whisper, Transkription, Mobile/Desktop-Client"),
        new("personal_website", "Eigene Website, Blog, Frontend, Cloudflare, Hosting, Portfolio"),
        new("infra_devops", "Server, Hetzner, Docker, Netzwerk, Firewall, CI/CD, GitHub Actions"),
        new("private_life", "Privatleben, Einkauf, Familie, Gesundheit, Finanzen, Freizeit"),
    };

    private const int MaxTranscriptChars = 6000;

    /// `state`: Transkript plus vorab berechnete Metadaten. Lange Transkripte
    /// (Dateien) werden gekürzt, damit die Anfrage klein und schnell bleibt;
    /// `word_count` zählt trotzdem den vollen Text.
    public static JsonObject State(string transcript, double durationSeconds, string language) => new()
    {
        ["transcript"] = transcript.Length > MaxTranscriptChars ? transcript[..MaxTranscriptChars] : transcript,
        ["metadata"] = new JsonObject
        {
            ["word_count"] = JevDecision.WordCount(transcript),
            // wie Jev.kt: (dauer * 10).roundToInt() / 10.0
            ["duration_seconds"] = Math.Round(durationSeconds * 10, MidpointRounding.AwayFromZero) / 10.0,
            ["language"] = language,
        },
    };

    public static JevRequest Build(string model, JsonObject state, IReadOnlyList<JevProjectTag> tags)
    {
        var project = new Dictionary<string, string>();
        foreach (var t in JevDecision.SanitizeTags(tags))
            project[t.Key] = t.Description.Length > 0 ? t.Description : t.Key;
        project[JevDecision.ProjectUnassigned] = "Kein eindeutiger Bezug zu einem der genannten Projekte erkennbar";

        return new JevRequest(model, state, new Dictionary<string, JevQuestion>
        {
            [JevDecision.QIntent] = new JevQuestion.Choice(
                "Welcher primären Kategorie entspricht dieses Sprachnotiz-Transkript (`transcript`)?",
                new Dictionary<string, string>
                {
                    ["todo_task"] = "Eine konkrete Aufgabe, Erledigung oder Erinnerung zum Abarbeiten",
                    ["code_or_bug"] = "Ein Software-Bug, Architektur-Gedanke, Feature-Request oder technisches Task-Item für ein Entwicklungsprojekt",
                    ["calendar_event"] = "Ein Termin mit zeitlichem Bezug, Meeting oder eine Verabredung",
                    ["message_draft"] = "Ein diktierter Text, der als E-Mail, Chat-Nachricht oder Antwort versendet werden soll",
                    ["general_note"] = "Ein allgemeiner Gedanke, Brainstorming oder Wissensnotiz ohne direkte Handlungsaufforderung",
                    ["voice_command"] = "Ein direkter Steuerbefehl an die App oder das Betriebssystem",
                    [JevDecision.IntentNoise] = "Unverständliches Gemurmel, Mikrofon-Test oder sinnfreies Fragment ohne Informationsgehalt",
                }),
            [JevDecision.QProject] = new JevQuestion.Choice(
                "Welchem Projekt oder Lebensbereich lässt sich `transcript` am besten zuordnen?",
                project),
            [JevDecision.QUrgency] = new JevQuestion.Score(
                "Wie zeitkritisch oder dringend ist der Inhalt von `transcript`?",
                new[]
                {
                    "Kein Zeitdruck / Irgendwann / Reine Information",
                    "Niedrige Priorität / Im Laufe der nächsten Wochen",
                    "Wichtig / Zeitnah in den nächsten Tagen oder heute zu erledigen",
                    "Akut kritisch / Sofortiger Handlungsbedarf / Blocker oder Notfall",
                }),
            [JevDecision.QCleanup] = new JevQuestion.Noul(
                "Enthält `transcript` viele Versprecher, Wortwiederholungen, abgebrochene Sätze oder starken Grammatik-Bruch, " +
                "sodass eine sprachliche Glättung vor der Weiterverarbeitung sinnvoll ist?",
                "Deutliche Stotterer, Korrekturen mitten im Satz, Füllwort-Ketten oder schwer lesbare Satzstruktur",
                "Gut verständlicher, flüssig lesbarer Text ohne störende Transkriptions-Artefakte"),
            [JevDecision.QActionable] = new JevQuestion.Noul(
                "Erfordert `transcript` eine konkrete nachfolgende Handlung durch den Nutzer?",
                "Enthält ein explizites oder implizites To-do, ein Versprechen oder eine Frist",
                "Reine Beobachtung, Dokumentation oder abgeschlossene Information"),
        });
    }

    /// Kleinste gültige Anfrage für „Verbindung testen“.
    public static JevRequest Ping(string model) => new(
        model,
        new JsonObject { ["transcript"] = "Verbindungstest" },
        new Dictionary<string, JevQuestion>
        {
            ["ping"] = new JevQuestion.Noul(
                "Ist `transcript` ein Text?",
                "Der Text besteht aus lesbaren Wörtern",
                "Der Text besteht nicht aus lesbaren Wörtern"),
        });
}

// ---------------------------------------------------------------------------
// Einstieg für den Rest der App
// ---------------------------------------------------------------------------

public static class Jev
{
    /// Aktiv nur, wenn der Nutzer es eingeschaltet und einen Key hinterlegt hat.
    public static bool IsActive(AppSettings s) => s.JevEnabled && !string.IsNullOrWhiteSpace(s.JevApiKey);

    /// Aufrufen, sobald gleich ein Transkript kommt (Aufnahmestart, Datei).
    /// Nur wenn eingeschaltet — sonst kein Netzwerkzugriff.
    public static void WarmUp(AppSettings s)
    {
        if (IsActive(s)) JevClient.WarmUp();
    }

    public static string Model(AppSettings s) =>
        string.IsNullOrWhiteSpace(s.JevModel) ? JevQuestions.DefaultModel : s.JevModel.Trim();

    public static List<JevProjectTag> ProjectTags(AppSettings s) =>
        s.JevProjectTags.Count > 0 ? s.JevProjectTags : JevQuestions.DefaultTags;

    /// Klassifiziert ein fertiges Transkript. Gibt bei jedem Problem null
    /// zurück (Offline, Timeout, Key, Schema) — der Aufrufer behandelt das
    /// Transkript dann wie ohne Jev.
    public static async Task<JevDecisionResult?> AnalyzeAsync(
        AppSettings s, string text, double durationSeconds, string language)
    {
        if (!IsActive(s) || string.IsNullOrWhiteSpace(text)) return null;
        var lang = language.Length > 0 && language != "auto" ? language
            : TextRules.GuessLanguage(text) is { Length: > 0 } g ? g : "de";
        var request = JevQuestions.Build(
            Model(s),
            JevQuestions.State(text, durationSeconds, lang),
            ProjectTags(s));
        var r = await JevClient.SendAsync(request, s.JevApiKey);
        return r switch
        {
            JevClient.Result.Success ok => JevDecision.Decide(ok.Response, durationSeconds),
            JevClient.Result.Failure fail => null, // Klassifikation übersprungen
            _ => null,
        };
    }

    /// „Verbindung testen“ in den Einstellungen; mit großzügigerem Timeout.
    public static Task<JevClient.Result> TestConnectionAsync(string apiKey, string model) =>
        JevClient.SendAsync(JevQuestions.Ping(model), apiKey.Trim(), timeoutMs: 6000);
}
