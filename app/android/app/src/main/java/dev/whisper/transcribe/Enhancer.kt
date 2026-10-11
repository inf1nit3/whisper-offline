package dev.whisper.transcribe

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/// Anweisungen aus assets/prompts.json (gemeinsam mit der Windows-App).
object Prompts {
    @Volatile private var json: JSONObject? = null

    private fun get(context: Context): JSONObject = json ?: synchronized(this) {
        json ?: JSONObject(context.assets.open("prompts.json").bufferedReader().use { it.readText() }).also { json = it }
    }

    private fun langName(context: Context, code: String): String? =
        get(context).getJSONObject("language_names").optString(code).ifEmpty { null }

    private fun languageLine(context: Context, code: String, key: String): String =
        langName(context, code)?.let { get(context).getString(key).replace("{{LANG}}", it) } ?: ""

    private const val OUTPUT_RULE = "Output only the rewritten text"

    fun enhanceSystem(context: Context, mode: FormatMode, words: List<String>, language: String): String {
        val p = get(context)
        val modeLine = p.getJSONObject("modes").optString(mode.key, p.getJSONObject("modes").getString("standard"))
        val wordsLine = if (words.isEmpty()) "" else p.getString("words").replace("{{WORDS}}", words.joinToString(", "))
        val lang = languageLine(context, language, "language")
        val custom = Settings.enhancePrompt(context)
        if (custom != null) {
            // Eigene Regeln: Sprache, Modus, Wortliste und die reine Textausgabe
            // hängt die App weiterhin selbst an — die bleiben verlässlich.
            val base = p.getString("enhance_system")
            val tail = base.substring(base.indexOf(OUTPUT_RULE).coerceAtLeast(0))
            return custom.trim() + "\n" + lang + "\n" + modeLine + wordsLine + "\n" + tail
        }
        return p.getString("enhance_system")
            .replace("{{LANGUAGE}}", lang)
            .replace("{{MODE}}", modeLine)
            .replace("{{WORDS}}", wordsLine)
    }

    /// Die mitgelieferten Regeln zum Anzeigen und Bearbeiten — ohne die Teile,
    /// die die App selbst einsetzt.
    fun defaultEnhanceRules(context: Context): String {
        val base = get(context).getString("enhance_system")
        val cut = base.indexOf(OUTPUT_RULE).let { if (it < 0) base.length else it }
        return base.substring(0, cut)
            .replace("{{LANGUAGE}}", "").replace("{{MODE}}", "").replace("{{WORDS}}", "")
            .trim()
    }

    fun selectionSystem(context: Context, language: String, words: List<String> = emptyList()): String =
        get(context).getString("selection_system").replace("{{LANGUAGE}}", languageLine(context, language, "language_inline")) +
            (if (words.isEmpty()) "" else get(context).getString("words").replace("{{WORDS}}", words.joinToString(", ")))

    fun selectionUser(context: Context, instruction: String, text: String): String =
        get(context).getString("selection_user").replace("{{INSTRUCTION}}", instruction).replace("{{TEXT}}", text)

    /// Feste Aktion (improve, shorten …); translate braucht [targetLanguage] als Name.
    fun action(context: Context, key: String, targetLanguage: String = "English"): String =
        get(context).getJSONObject("actions").getString(key).replace("{{LANG}}", targetLanguage)

    fun notesSystem(context: Context, language: String, chunk: Boolean): String {
        val p = get(context)
        val all = p.getJSONObject("notes_headings")
        val h = (all.optJSONArray(language) ?: all.getJSONArray("en")).let { a -> List(a.length()) { a.getString(it) } }
        return p.getString(if (chunk) "notes_chunk_system" else "notes_system")
            .replace("{{LANGUAGE}}", languageLine(context, language, "language_inline"))
            .replace("{{H_SUMMARY}}", h[0]).replace("{{H_DECISIONS}}", h[1])
            .replace("{{H_ACTIONS}}", h[2]).replace("{{H_QUESTIONS}}", h[3])
    }
}

/// Lädt das gewählte Sprachmodell bei Bedarf und gibt es nach einer Pause
/// wieder frei — außer „im Speicher behalten“ ist an.
object LlmManager {

    /// Groß genug für Diktate und Auswahl (bis ~6000 Zeichen) samt Anweisung;
    /// mehr kostet auf dem Handy spürbar Speicher (KV-Cache).
    const val N_CTX = 4096
    private const val IDLE_UNLOAD_MS = 120_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var unloadJob: Job? = null

    fun llmDir(context: Context): File = File(context.filesDir, "llm").also { it.mkdirs() }

    fun modelFile(context: Context): File? =
        Settings.llmModel(context)?.let { File(llmDir(context), it) }?.takeIf { it.exists() }

    fun isConfigured(context: Context): Boolean = LlmBridge.available && modelFile(context) != null

    /// Blockiert; im IO-/Default-Dispatcher aufrufen.
    fun ensureLoaded(context: Context): Boolean {
        unloadJob?.cancel()
        val f = modelFile(context) ?: return false
        return LlmBridge.load(f.absolutePath, N_CTX, WhisperBridge.detectThreads())
    }

    fun release(context: Context) {
        if (Settings.llmKeepLoaded(context)) return
        unloadJob?.cancel()
        unloadJob = scope.launch {
            delay(IDLE_UNLOAD_MS)
            LlmBridge.free()
        }
    }
}

/// Nachbearbeitung durch das lokale Sprachmodell.
object Enhancer {

    data class Outcome(val text: String?, val error: String? = null, val cancelled: Boolean = false)

    fun cancel() = LlmBridge.cancel()

    private suspend fun run(context: Context, system: String, user: String, input: String): Outcome =
        withContext(Dispatchers.Default + NonCancellable) {
            if (!LlmManager.ensureLoaded(context)) return@withContext Outcome(null, LlmBridge.lastError().ifEmpty { "Sprachmodell nicht geladen" })
            try {
                val inTokens = LlmBridge.countTokens(input).coerceAtLeast(16)
                // Umformen darf länger werden (ausführlicher, Aufzählung), aber nicht ausufern
                val max = (inTokens * 2 + 128).coerceAtMost(LlmManager.N_CTX / 2)
                val out = LlmBridge.generate(system, user, max)
                when {
                    out == null && LlmBridge.wasCancelled() -> Outcome(null, cancelled = true)
                    out == null -> Outcome(null, LlmBridge.lastError().ifEmpty { "Sprachmodell fehlgeschlagen" })
                    else -> Outcome(out)
                }
            } finally {
                LlmManager.release(context)
            }
        }

    /// Diktat bereinigen und für [mode] formatieren.
    suspend fun enhance(context: Context, text: String, mode: FormatMode, language: String): Outcome {
        val system = Prompts.enhanceSystem(context, mode, Vocabulary.words, language)
        val o = run(context, system, "<dictation>\n$text\n</dictation>", text)
        return o.copy(text = o.text?.let { clean(it, mode, text) })
    }

    /// Markierten Text nach einer Anweisung umformen.
    suspend fun transform(context: Context, text: String, instruction: String, language: String): Outcome {
        val o = run(context, Prompts.selectionSystem(context, language, Vocabulary.words), Prompts.selectionUser(context, instruction, text), text)
        return o.copy(text = o.text?.let { clean(it, FormatMode.STANDARD, text, capitalize = false) })
    }

    /// Antwort aufräumen: mitgeschriebene Tags, umschließende Anführungszeichen,
    /// Leerraum. Leer → Original behalten.
    private fun clean(out: String, mode: FormatMode, original: String, capitalize: Boolean = true): String {
        var s = out
            .replace(Regex("""</?(dictation|text|diktat)>""", RegexOption.IGNORE_CASE), "")
            .trim()
        if (s.length >= 2 && s.first() in "\"„“«" && s.last() in "\"“”»" && (original.firstOrNull() ?: ' ') !in "\"„“«") {
            s = s.substring(1, s.length - 1).trim()
        }
        s = TextRules.tidy(s)
        if (capitalize && mode in setOf(FormatMode.STANDARD, FormatMode.EMAIL, FormatMode.DOCUMENT,
                FormatMode.NOTE, FormatMode.TODO, FormatMode.SUBJECT)) {
            s = TextRules.capitalizeSentences(s)
        }
        if (mode == FormatMode.SUBJECT) s = s.trimEnd('.')
        // Kleine Modelle verschreiben Namen trotz Anweisung — die Wortliste korrigiert nach
        s = Vocabulary.correct(s)
        return s.ifEmpty { original }
    }

    /// Meeting-Notizen. Lange Transkripte werden erst abschnittsweise
    /// verdichtet und dann zusammengefasst — der Kontext reicht sonst nicht.
    suspend fun meetingNotes(context: Context, transcript: String, language: String,
                             onProgress: (String) -> Unit = {}): Outcome {
        if (!LlmManager.isConfigured(context)) return Outcome(null, "Kein Sprachmodell gewählt")
        val budgetChars = 7000 // ~2000 Tokens Text je Durchgang, Rest für Anweisung und Antwort
        var material = transcript
        var round = 0
        while (material.length > budgetChars && round < 4) {
            round++
            val parts = chunk(material, budgetChars)
            val condensed = StringBuilder()
            for ((i, part) in parts.withIndex()) {
                onProgress("Werte Abschnitt ${i + 1} von ${parts.size} aus …")
                val o = run(context, Prompts.notesSystem(context, language, chunk = true), part, part)
                if (o.text == null) return o
                condensed.append(o.text.trim()).append("\n")
            }
            material = condensed.toString()
        }
        onProgress("Schreibe Notizen …")
        val o = run(context, Prompts.notesSystem(context, language, chunk = false), material, material)
        return o.copy(text = o.text?.trim())
    }

    /// An Zeilengrenzen in Stücke von höchstens [max] Zeichen teilen.
    private fun chunk(text: String, max: Int): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (line in text.lines()) {
            if (sb.length + line.length + 1 > max && sb.isNotEmpty()) {
                out += sb.toString(); sb.clear()
            }
            sb.append(line.take(max)).append('\n')
        }
        if (sb.isNotBlank()) out += sb.toString()
        return out
    }
}

/// Was nach der Spracherkennung mit einem Diktat passiert — für Tastatur,
/// Diktat-Overlay und die Aufnahme in der App gleich.
object TextPipeline {

    data class Result(
        val text: String,
        val snippet: Boolean = false,
        val enhanced: Boolean = false,
        /// Hinweis für die Oberfläche, etwa warum Enhance nicht lief.
        val note: String? = null,
        val cancelled: Boolean = false,
    )

    /// [language]: Sprache des Diktats ("de", "en"; "" = unbekannt, wird geraten).
    suspend fun finishDictation(context: Context, raw: String, language: String,
                                mode: FormatMode, enhance: Boolean): Result {
        Snippets.match(raw)?.let { return Result(it, snippet = true) }
        var text = if (Settings.spokenCommands(context)) TextRules.applySpokenCommands(raw) else raw
        if (!enhance || mode == FormatMode.RAW || text.isBlank()) return Result(text)
        if (!LlmManager.isConfigured(context)) return Result(text, note = "Enhance: kein Sprachmodell gewählt")
        val lang = language.ifEmpty { TextRules.guessLanguage(text) }
        text = TextRules.removeFillers(text, lang)
        val effective = if (mode == FormatMode.AUTO) FormatMode.STANDARD else mode
        val o = Enhancer.enhance(context, text, effective, lang)
        return when {
            o.cancelled -> Result(text, cancelled = true, note = "Enhance abgebrochen — Rohtext übernommen")
            o.text != null -> Result(o.text, enhanced = true)
            else -> Result(text, note = "Enhance fehlgeschlagen: ${o.error}")
        }
    }

    /// Sprache für Enhance: gewählte Sprache, bei „Automatisch“ die erkannte.
    fun dictationLanguage(context: Context): String {
        val set = Settings.language(context)
        if (set != "auto") return set
        return runCatching { WhisperBridge.lastLanguage() }.getOrDefault("")
    }
}
