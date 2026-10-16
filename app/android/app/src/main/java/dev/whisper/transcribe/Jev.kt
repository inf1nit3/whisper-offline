package dev.whisper.transcribe

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// Smart Routing mit TypeSafe Jev (https://api.typesafe.ai/v1/systemone)
//
// Jev ist kein generatives Modell, sondern ein Entscheidungsmodell: Eine
// Anfrage beantwortet mehrere typisierte Fragen (choice / score / noul) mit
// kalibrierten Wahrscheinlichkeiten. Die App bleibt offline-first — jeder
// Fehler (kein Netz, Timeout, ungültiger Key …) führt zum Rohtranskript.
// ---------------------------------------------------------------------------

/// Eine Frage an Jev. Das Schema ist strikt: `choice` braucht ein Objekt als
/// `criteria`, `score` ein Array (2–10 Einträge), `noul` ein optionales Objekt
/// mit „true“/„false“ — sonst antwortet die API mit HTTP 422.
sealed interface JevQuestion {
    fun toJson(): JSONObject

    data class Choice(val instructions: String, val criteria: Map<String, String>) : JevQuestion {
        override fun toJson(): JSONObject = JSONObject()
            .put("type", "choice")
            .put("instructions", instructions)
            .put("criteria", JSONObject().also { o -> criteria.forEach { (k, v) -> o.put(k, v) } })
    }

    /// Der Score läuft von 0 bis `criteria.size - 1`.
    data class Score(val instructions: String, val criteria: List<String>) : JevQuestion {
        override fun toJson(): JSONObject = JSONObject()
            .put("type", "score")
            .put("instructions", instructions)
            .put("criteria", JSONArray(criteria))
    }

    /// Ja/Nein-Frage; ein hoher Wert (→ 1.0) bedeutet immer „trifft zu“.
    data class Noul(val instructions: String, val whenTrue: String, val whenFalse: String) : JevQuestion {
        override fun toJson(): JSONObject = JSONObject()
            .put("type", "noul")
            .put("instructions", instructions)
            .put("criteria", JSONObject().put("true", whenTrue).put("false", whenFalse))
    }
}

data class JevRequest(val model: String, val state: JSONObject, val questions: Map<String, JevQuestion>) {
    fun toJson(): JSONObject = JSONObject()
        .put("model", model)
        .put("state", state)
        .put("questions", JSONObject().also { o -> questions.forEach { (id, q) -> o.put(id, q.toJson()) } })
}

/// Antwort auf eine einzelne Frage (`answers.<id>`).
sealed interface JevAnswer {
    data class Choice(val choice: String, val probabilities: Map<String, Double>, val confidence: Double) : JevAnswer {
        /// Optionen absteigend nach Wahrscheinlichkeit.
        fun ranked(): List<Pair<String, Double>> = probabilities.toList().sortedByDescending { it.second }

        /// Zweitwahrscheinlichste Option (ungleich der gewählten) für den Quick-Switch.
        fun alternative(): String? = ranked().firstOrNull { it.first != choice }?.first
    }

    data class Score(
        val score: Double,
        val legend: Map<String, String>,
        val probabilities: Map<String, Double>,
        val confidence: Double,
    ) : JevAnswer {
        /// Beschriftung der nächstliegenden Stufe — die `legend` hat String-Schlüssel „0“, „1“ …
        fun legendLabel(): String? = legend[score.roundToInt().toString()]
    }

    /// `noul` ist selbst die Sicherheit: nahe 1.0 = sicher ja, nahe 0.0 = sicher nein, 0.5 = unsicher.
    data class Noul(val noul: Double) : JevAnswer
}

data class JevResponse(val model: String, val answers: Map<String, JevAnswer>) {

    inline fun <reified T : JevAnswer> answer(id: String): T? = answers[id] as? T

    companion object {
        /// @throws JSONException bei unerwartetem Aufbau
        fun fromJson(root: JSONObject): JevResponse {
            val answers = root.getJSONObject("answers")
            val parsed = mutableMapOf<String, JevAnswer>()
            for (id in answers.keys()) {
                val o = answers.getJSONObject(id)
                parsed[id] = when (val type = o.getString("type")) {
                    "choice" -> JevAnswer.Choice(o.getString("choice"), doubles(o.getJSONObject("probabilities")), o.getDouble("confidence"))
                    "score" -> JevAnswer.Score(
                        o.getDouble("score"),
                        strings(o.getJSONObject("legend")),
                        doubles(o.getJSONObject("probabilities")),
                        o.getDouble("confidence"),
                    )
                    "noul" -> JevAnswer.Noul(o.getDouble("noul"))
                    else -> throw JSONException("Unbekannter Antworttyp: $type")
                }
            }
            return JevResponse(root.optString("model"), parsed)
        }

        private fun doubles(o: JSONObject) = o.keys().asSequence().associateWith { o.getDouble(it) }
        private fun strings(o: JSONObject) = o.keys().asSequence().associateWith { o.getString(it) }
    }
}

// ---------------------------------------------------------------------------
// HTTP-Client
// ---------------------------------------------------------------------------

object JevClient {

    const val ENDPOINT = "https://api.typesafe.ai/v1/systemone"
    const val TIMEOUT_MS = 1500L

    private const val RETRY_BACKOFF_MS = 250L
    private const val WARM_UP_INTERVAL_MS = 60_000L
    private const val WARM_UP_TIMEOUT_MS = 5000
    private const val TAG = "Jev"

    @Volatile private var lastWarmUp = 0L

    enum class Error { TIMEOUT, NETWORK, UNAUTHORIZED, SCHEMA, RATE_LIMITED, SERVER, INVALID_RESPONSE }

    sealed interface Result {
        data class Success(val response: JevResponse) : Result
        data class Failure(val error: Error, val message: String) : Result
    }

    /// Die Anfrage läuft in einem eigenen Scope: HttpURLConnection lässt sich
    /// nicht per Coroutine abbrechen, der Aufrufer wartet aber nie länger als
    /// [timeoutMs]. Die Verbindungs-Timeouts begrenzen den Hintergrundlauf.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /// Schickt [request] und gibt nach spätestens [timeoutMs] ein Ergebnis zurück.
    /// Seiteneffekt: Netzwerkzugriff. Der API-Key wird nie geloggt.
    suspend fun send(request: JevRequest, apiKey: String, timeoutMs: Long = TIMEOUT_MS): Result {
        val pending = scope.async { execute(request, apiKey, timeoutMs) }
        return withTimeoutOrNull(timeoutMs) { pending.await() }
            ?: Result.Failure(Error.TIMEOUT, "Zeitüberschreitung").also { pending.cancel() }
    }

    /// Baut die Verbindung vorab auf (DNS, TLS, Klassen laden), damit die
    /// Anfrage nach der Transkription ins Zeitbudget passt — kalt brauchte die
    /// erste Anfrage im Emulator länger als 1,5 s. Schickt nur einen leeren HEAD
    /// ohne Key und ohne Text (Antwort 405); die Verbindung bleibt im Pool und
    /// der POST verwendet sie wieder. Höchstens einmal pro Minute, Fehler egal.
    fun warmUp() {
        val now = SystemClock.elapsedRealtime()
        if (lastWarmUp != 0L && now - lastWarmUp < WARM_UP_INTERVAL_MS) return
        lastWarmUp = now
        scope.launch {
            try {
                val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
                conn.requestMethod = "HEAD"
                conn.connectTimeout = WARM_UP_TIMEOUT_MS
                conn.readTimeout = WARM_UP_TIMEOUT_MS
                val code = conn.responseCode
                // Body schließen statt disconnect() — sonst wird die Verbindung geschlossen statt gepoolt
                (if (code >= 400) conn.errorStream else conn.inputStream)?.close()
                Log.d(TAG, "Verbindung vorbereitet (HTTP $code, ${SystemClock.elapsedRealtime() - now} ms)")
            } catch (_: IOException) {
                // Offline o. Ä.: die eigentliche Anfrage fällt dann ohnehin aufs Rohtranskript zurück
            }
        }
    }

    /// 429/529: ein einziger kurzer Retry, solange das Zeitbudget es zulässt.
    private fun execute(request: JevRequest, apiKey: String, timeoutMs: Long): Result {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var result = post(request, apiKey, (deadline - SystemClock.elapsedRealtime()).toInt())
        val retryable = (result as? Result.Failure)?.error == Error.RATE_LIMITED
        if (retryable && deadline - SystemClock.elapsedRealtime() > RETRY_BACKOFF_MS + 200) {
            Thread.sleep(RETRY_BACKOFF_MS)
            result = post(request, apiKey, (deadline - SystemClock.elapsedRealtime()).toInt())
        }
        return result
    }

    private fun post(request: JevRequest, apiKey: String, budgetMs: Int): Result {
        if (budgetMs <= 0) return Result.Failure(Error.TIMEOUT, "Zeitüberschreitung")
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = budgetMs
            conn.readTimeout = budgetMs
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(request.toJson().toString().toByteArray(Charsets.UTF_8)) }
            when (val code = conn.responseCode) {
                200 -> Result.Success(JevResponse.fromJson(JSONObject(conn.inputStream.bufferedReader().use { it.readText() })))
                401 -> Result.Failure(Error.UNAUTHORIZED, "API-Key ungültig")
                422 -> Result.Failure(Error.SCHEMA, "Anfrage vom Server abgelehnt (HTTP 422)")
                429, 529 -> Result.Failure(Error.RATE_LIMITED, "Dienst ausgelastet (HTTP $code)")
                else -> Result.Failure(Error.SERVER, "Serverfehler (HTTP $code)")
            }
        } catch (_: SocketTimeoutException) {
            Result.Failure(Error.TIMEOUT, "Zeitüberschreitung")
        } catch (_: IOException) {
            Result.Failure(Error.NETWORK, "Keine Verbindung")
        } catch (_: JSONException) {
            Result.Failure(Error.INVALID_RESPONSE, "Unerwartete Antwort")
        } finally {
            conn.disconnect()
        }
    }
}

// ---------------------------------------------------------------------------
// Fragen, Zustand und Entscheidungslogik
// ---------------------------------------------------------------------------

/// Eigenes Projekt für `project_context`: [key] kommt als Schlüssel in die
/// Kriterien, [description] erklärt Jev, was dazugehört.
data class JevProjectTag(val key: String, val description: String)

/// Ergebnis der Klassifikation — das, was am Transkript hängen bleibt.
data class JevAnalysis(
    val intent: String,
    val intentConfidence: Double,
    val topAlternativeIntent: String?,
    val projectTag: String?,
    val urgencyScore: Double?,
    val isActionable: Boolean?,
    val needsCleanup: Boolean?,
) {
    /// Mittlere Sicherheit: die App bietet die Alternative als Ein-Tipp-Chip an.
    val needsDisambiguation: Boolean
        get() = topAlternativeIntent != null &&
            intentConfidence >= JevDecision.MEDIUM_CONFIDENCE && intentConfidence < JevDecision.HIGH_CONFIDENCE
}

/// [discard]: Rauschen (Mikrofontest, Gemurmel) — nicht als Notiz speichern.
data class JevDecisionResult(val analysis: JevAnalysis, val discard: Boolean)

object JevDecision {

    const val HIGH_CONFIDENCE = 0.80
    const val MEDIUM_CONFIDENCE = 0.50
    const val CLEANUP_THRESHOLD = 0.70
    const val ACTIONABLE_THRESHOLD = 0.70
    const val NOISE_CONFIDENCE = 0.85
    const val NOISE_MAX_SECONDS = 3.0

    const val INTENT_FALLBACK = "general_note"
    const val INTENT_NOISE = "noise_or_empty"
    const val PROJECT_UNASSIGNED = "unassigned"

    const val Q_INTENT = "intent"
    const val Q_PROJECT = "project_context"
    const val Q_URGENCY = "urgency"
    const val Q_CLEANUP = "needs_text_cleanup"
    const val Q_ACTIONABLE = "is_actionable"

    /// Übersetzt die Antworten in Entscheidungen:
    /// - Intent ab 0,80 automatisch; 0,50–0,80 mit Alternative zum Umschalten;
    ///   darunter `general_note` ohne weitere Aktion.
    /// - Projekt nur ab 0,80 und nie „unassigned“ — ein falsches Tag ist
    ///   schlimmer als keins.
    /// - Dringlichkeit ab 0,50, sonst unbekannt.
    /// - `needsCleanup` ab noul 0,70; `isActionable` nur bei klarer Antwort
    ///   (≥ 0,70 / ≤ 0,30), dazwischen unbekannt.
    /// Liefert null, wenn die Intent-Antwort fehlt.
    fun decide(response: JevResponse, durationSeconds: Double): JevDecisionResult? {
        val intent = response.answer<JevAnswer.Choice>(Q_INTENT) ?: return null
        val project = response.answer<JevAnswer.Choice>(Q_PROJECT)
        val urgency = response.answer<JevAnswer.Score>(Q_URGENCY)
        val cleanup = response.answer<JevAnswer.Noul>(Q_CLEANUP)
        val actionable = response.answer<JevAnswer.Noul>(Q_ACTIONABLE)

        val confident = intent.confidence >= MEDIUM_CONFIDENCE
        val analysis = JevAnalysis(
            intent = if (confident) intent.choice else INTENT_FALLBACK,
            intentConfidence = intent.confidence,
            topAlternativeIntent = if (confident) intent.alternative() else null,
            projectTag = project?.takeIf { it.confidence >= HIGH_CONFIDENCE && it.choice != PROJECT_UNASSIGNED }
                ?.takeIf { confident }?.choice,
            urgencyScore = urgency?.takeIf { it.confidence >= MEDIUM_CONFIDENCE && confident }?.score,
            isActionable = actionable?.noul?.let { n ->
                when {
                    n >= ACTIONABLE_THRESHOLD -> true
                    n <= 1 - ACTIONABLE_THRESHOLD -> false
                    else -> null
                }
            },
            needsCleanup = cleanup?.let { it.noul >= CLEANUP_THRESHOLD },
        )
        val discard = intent.choice == INTENT_NOISE && intent.confidence >= NOISE_CONFIDENCE &&
            durationSeconds < NOISE_MAX_SECONDS
        return JevDecisionResult(analysis, discard)
    }

    /// Schlüssel säubern: kein Leerraum, keine Dubletten, nie „unassigned“
    /// (wird automatisch ergänzt), höchstens [MAX_TAGS] (API-Limit: 255 Keys).
    fun sanitizeTags(tags: List<JevProjectTag>): List<JevProjectTag> =
        tags.map { JevProjectTag(it.key.trim(), it.description.trim()) }
            .filter { it.key.isNotEmpty() && it.key != PROJECT_UNASSIGNED }
            .distinctBy { it.key }
            .take(MAX_TAGS)

    const val MAX_TAGS = 100

    /// Wörter wie im Verlauf gezählt — Jev kann nicht zuverlässig zählen,
    /// deshalb rechnet die App vor.
    fun wordCount(text: String): Int = HistoryEntry.countWords(text)

    /// Zeigt „whisper_app“ als „Whisper App“.
    fun prettyKey(key: String): String =
        key.split('_', ' ').filter { it.isNotEmpty() }.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    val INTENT_LABELS = mapOf(
        "todo_task" to "Aufgabe",
        "code_or_bug" to "Code/Bug",
        "calendar_event" to "Termin",
        "message_draft" to "Nachricht",
        "general_note" to "Notiz",
        "voice_command" to "Befehl",
        INTENT_NOISE to "Rauschen",
    )

    fun intentLabel(intent: String): String = INTENT_LABELS[intent] ?: prettyKey(intent)
}

/// Baut Anfragen. Die Texte sind wörtlich gemeint: Jev liest `instructions`
/// und `criteria` buchstäblich, sie dürfen sich nie widersprechen.
object JevQuestions {

    const val DEFAULT_MODEL = "jev-1.13.0"

    /// Startliste der Projekt-Tags; im Einstellungsdialog änderbar.
    val DEFAULT_TAGS = listOf(
        JevProjectTag("whisper_app", "Spracherkennung, Audio-App, Whisper, Transkription, Mobile/Desktop-Client"),
        JevProjectTag("personal_website", "Eigene Website, Blog, Frontend, Cloudflare, Hosting, Portfolio"),
        JevProjectTag("infra_devops", "Server, Hetzner, Docker, Netzwerk, Firewall, CI/CD, GitHub Actions"),
        JevProjectTag("private_life", "Privatleben, Einkauf, Familie, Gesundheit, Finanzen, Freizeit"),
    )

    /// `state`: Transkript plus vorab berechnete Metadaten. Lange Transkripte
    /// (Dateien) werden gekürzt, damit die Anfrage klein und schnell bleibt;
    /// `word_count` zählt trotzdem den vollen Text.
    fun state(transcript: String, durationSeconds: Double, language: String): JSONObject = JSONObject()
        .put("transcript", transcript.take(MAX_TRANSCRIPT_CHARS))
        .put("metadata", JSONObject()
            .put("word_count", JevDecision.wordCount(transcript))
            .put("duration_seconds", (durationSeconds * 10).roundToInt() / 10.0)
            .put("language", language))

    private const val MAX_TRANSCRIPT_CHARS = 6000

    fun build(model: String, state: JSONObject, tags: List<JevProjectTag>): JevRequest =
        JevRequest(model, state, mapOf(
            JevDecision.Q_INTENT to JevQuestion.Choice(
                "Welcher primären Kategorie entspricht dieses Sprachnotiz-Transkript (`transcript`)?",
                linkedMapOf(
                    "todo_task" to "Eine konkrete Aufgabe, Erledigung oder Erinnerung zum Abarbeiten",
                    "code_or_bug" to "Ein Software-Bug, Architektur-Gedanke, Feature-Request oder technisches Task-Item für ein Entwicklungsprojekt",
                    "calendar_event" to "Ein Termin mit zeitlichem Bezug, Meeting oder eine Verabredung",
                    "message_draft" to "Ein diktierter Text, der als E-Mail, Chat-Nachricht oder Antwort versendet werden soll",
                    "general_note" to "Ein allgemeiner Gedanke, Brainstorming oder Wissensnotiz ohne direkte Handlungsaufforderung",
                    "voice_command" to "Ein direkter Steuerbefehl an die App oder das Betriebssystem",
                    JevDecision.INTENT_NOISE to "Unverständliches Gemurmel, Mikrofon-Test oder sinnfreies Fragment ohne Informationsgehalt",
                ),
            ),
            JevDecision.Q_PROJECT to JevQuestion.Choice(
                "Welchem Projekt oder Lebensbereich lässt sich `transcript` am besten zuordnen?",
                LinkedHashMap<String, String>().also { m ->
                    JevDecision.sanitizeTags(tags).forEach { m[it.key] = it.description.ifEmpty { it.key } }
                    m[JevDecision.PROJECT_UNASSIGNED] = "Kein eindeutiger Bezug zu einem der genannten Projekte erkennbar"
                },
            ),
            JevDecision.Q_URGENCY to JevQuestion.Score(
                "Wie zeitkritisch oder dringend ist der Inhalt von `transcript`?",
                listOf(
                    "Kein Zeitdruck / Irgendwann / Reine Information",
                    "Niedrige Priorität / Im Laufe der nächsten Wochen",
                    "Wichtig / Zeitnah in den nächsten Tagen oder heute zu erledigen",
                    "Akut kritisch / Sofortiger Handlungsbedarf / Blocker oder Notfall",
                ),
            ),
            JevDecision.Q_CLEANUP to JevQuestion.Noul(
                "Enthält `transcript` viele Versprecher, Wortwiederholungen, abgebrochene Sätze oder starken Grammatik-Bruch, " +
                    "sodass eine sprachliche Glättung vor der Weiterverarbeitung sinnvoll ist?",
                "Deutliche Stotterer, Korrekturen mitten im Satz, Füllwort-Ketten oder schwer lesbare Satzstruktur",
                "Gut verständlicher, flüssig lesbarer Text ohne störende Transkriptions-Artefakte",
            ),
            JevDecision.Q_ACTIONABLE to JevQuestion.Noul(
                "Erfordert `transcript` eine konkrete nachfolgende Handlung durch den Nutzer?",
                "Enthält ein explizites oder implizites To-do, ein Versprechen oder eine Frist",
                "Reine Beobachtung, Dokumentation oder abgeschlossene Information",
            ),
        ))

    /// Kleinste gültige Anfrage für „Verbindung testen“.
    fun ping(model: String): JevRequest = JevRequest(
        model,
        JSONObject().put("transcript", "Verbindungstest"),
        mapOf("ping" to JevQuestion.Noul(
            "Ist `transcript` ein Text?",
            "Der Text besteht aus lesbaren Wörtern",
            "Der Text besteht nicht aus lesbaren Wörtern",
        )),
    )
}

// ---------------------------------------------------------------------------
// Einstieg für den Rest der App
// ---------------------------------------------------------------------------

object Jev {

    private const val TAG = "Jev"

    /// Aktiv nur, wenn der Nutzer es eingeschaltet und einen Key hinterlegt hat.
    fun isActive(context: Context): Boolean =
        Settings.jevEnabled(context) && Settings.jevApiKey(context).isNotBlank()

    /// Aufrufen, sobald gleich ein Transkript kommt (Aufnahmestart, Datei).
    /// Nur wenn eingeschaltet — sonst kein Netzwerkzugriff.
    fun warmUp(context: Context) {
        if (isActive(context)) JevClient.warmUp()
    }

    /// Klassifiziert ein fertiges Transkript. Gibt bei jedem Problem null
    /// zurück (Offline, Timeout, Key, Schema) — der Aufrufer behandelt das
    /// Transkript dann wie ohne Jev.
    suspend fun analyze(context: Context, text: String, durationSeconds: Double, language: String): JevDecisionResult? {
        if (!isActive(context) || text.isBlank()) return null
        val lang = language.takeIf { it.isNotEmpty() && it != "auto" }
            ?: TextRules.guessLanguage(text).ifEmpty { "de" }
        val request = JevQuestions.build(
            Settings.jevModel(context),
            JevQuestions.state(text, durationSeconds, lang),
            Settings.jevProjectTags(context),
        )
        val t0 = SystemClock.elapsedRealtime()
        return when (val r = JevClient.send(request, Settings.jevApiKey(context))) {
            is JevClient.Result.Success -> {
                Log.d(TAG, "Klassifiziert in ${SystemClock.elapsedRealtime() - t0} ms")
                JevDecision.decide(r.response, durationSeconds)
            }
            is JevClient.Result.Failure -> {
                Log.w(TAG, "Klassifikation übersprungen: ${r.error} (${SystemClock.elapsedRealtime() - t0} ms)")
                null
            }
        }
    }

    /// „Verbindung testen“ in den Einstellungen; mit großzügigerem Timeout.
    suspend fun testConnection(apiKey: String, model: String): JevClient.Result =
        JevClient.send(JevQuestions.ping(model), apiKey.trim(), timeoutMs = 6000)
}
