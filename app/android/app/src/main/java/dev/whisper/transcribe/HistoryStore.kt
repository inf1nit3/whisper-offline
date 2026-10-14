package dev.whisper.transcribe

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HistoryEntry(
    val timeMs: Long,
    val text: String,
    val model: String,
    val language: String,
    val audioSeconds: Float,
    /// Woher der Eintrag stammt — für Filter und Statistik.
    val source: String = SOURCE_APP,
    /// Smart Routing (Jev): null bei Einträgen ohne Klassifikation (offline, aus, ältere Versionen).
    val intentCategory: String? = null,
    val intentConfidence: Double? = null,
    val topAlternativeIntent: String? = null,
    val projectTag: String? = null,
    val urgencyScore: Double? = null,
    val isActionable: Boolean? = null,
    val needsCleanup: Boolean? = null,
) {
    companion object {
        const val SOURCE_APP = "app"
        const val SOURCE_FILE = "file"
        const val SOURCE_KEYBOARD = "keyboard"
        const val SOURCE_DICTATION = "dictation"
        const val SOURCE_MEETING = "meeting"
        const val SOURCE_SELECTION = "selection"

        fun countWords(text: String): Int =
            text.split(Regex("""\s+""")).count { w -> w.any { it.isLetterOrDigit() } }
    }

    /// Wörter im Transkript (für Statistik).
    val words: Int get() = countWords(text)

    /// Gespeicherte Klassifikation; null, wenn keine vorliegt.
    fun analysis(): JevAnalysis? {
        val intent = intentCategory ?: return null
        return JevAnalysis(intent, intentConfidence ?: 0.0, topAlternativeIntent, projectTag, urgencyScore, isActionable, needsCleanup)
    }

    fun withAnalysis(a: JevAnalysis) = copy(
        intentCategory = a.intent, intentConfidence = a.intentConfidence, topAlternativeIntent = a.topAlternativeIntent,
        projectTag = a.projectTag, urgencyScore = a.urgencyScore, isActionable = a.isActionable, needsCleanup = a.needsCleanup,
    )

    fun dateText(): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(timeMs))
}

/// Transkriptions-Verlauf als JSON-Datei im App-Verzeichnis, neueste zuerst.
object HistoryStore {

    private const val MAX_ENTRIES = 500

    private fun file(context: Context) = File(context.filesDir, "history.json")

    /// Sortiert nach Zeit: Dateien aus Versionen bis 2.0 liegen durcheinander,
    /// weil `add` die Liste bei jedem Eintrag umgedreht hat.
    fun load(context: Context): List<HistoryEntry> =
        runCatching {
            val arr = JSONArray(file(context).readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                HistoryEntry(
                    timeMs = o.getLong("t"),
                    text = o.getString("x"),
                    model = o.optString("m"),
                    language = o.optString("l"),
                    audioSeconds = o.optDouble("s", 0.0).toFloat(),
                    source = o.optString("src").ifEmpty { HistoryEntry.SOURCE_APP },
                    intentCategory = o.optString("ic").ifEmpty { null },
                    intentConfidence = o.optDouble("icf").takeIf { !it.isNaN() },
                    topAlternativeIntent = o.optString("alt").ifEmpty { null },
                    projectTag = o.optString("pt").ifEmpty { null },
                    urgencyScore = o.optDouble("urg").takeIf { !it.isNaN() },
                    isActionable = if (o.has("act")) o.optBoolean("act") else null,
                    needsCleanup = if (o.has("cln")) o.optBoolean("cln") else null,
                )
            }.sortedByDescending { it.timeMs }
        }.getOrDefault(emptyList())

    /// Haupt-App und Diktat-Overlay schreiben aus verschiedenen Coroutinen.
    /// Mit abgeschaltetem Verlauf (Einstellungen) wird nichts gespeichert.
    @Synchronized
    fun add(context: Context, entry: HistoryEntry) {
        if (!Settings.historyEnabled(context)) return
        save(context, (listOf(entry) + load(context)).take(MAX_ENTRIES))
    }

    @Synchronized
    fun clear(context: Context) { file(context).delete() }

    @Synchronized
    fun delete(context: Context, entry: HistoryEntry) {
        save(context, load(context).filter { it.timeMs != entry.timeMs })
    }

    /// Bearbeitetes Transkript: den jüngsten Eintrag mit dem alten Text anpassen.
    @Synchronized
    fun replaceText(context: Context, oldText: String, newText: String) {
        val all = load(context)
        val i = all.indexOfFirst { it.text == oldText }
        if (i < 0) return
        save(context, all.toMutableList().also { it[i] = it[i].copy(text = newText) })
    }

    /// Nutzer übersteuert die Kategorie: Klassifikation des jüngsten Eintrags
    /// mit diesem Text ersetzen.
    @Synchronized
    fun updateAnalysis(context: Context, text: String, analysis: JevAnalysis) {
        val all = load(context)
        val i = all.indexOfFirst { it.text == text }
        if (i < 0) return
        save(context, all.toMutableList().also { it[i] = it[i].withAnalysis(analysis) })
    }

    private fun save(context: Context, entries: List<HistoryEntry>) {
        JSONArray().apply {
            entries.forEach { e ->
                put(JSONObject().apply {
                    put("t", e.timeMs)
                    put("x", e.text)
                    put("m", e.model)
                    put("l", e.language)
                    put("s", e.audioSeconds.toDouble())
                    put("src", e.source)
                    // Jev-Felder nur schreiben, wenn vorhanden: Offline-Einträge bleiben unverändert.
                    e.intentCategory?.let { put("ic", it) }
                    e.intentConfidence?.let { put("icf", it) }
                    e.topAlternativeIntent?.let { put("alt", it) }
                    e.projectTag?.let { put("pt", it) }
                    e.urgencyScore?.let { put("urg", it) }
                    e.isActionable?.let { put("act", it) }
                    e.needsCleanup?.let { put("cln", it) }
                })
            }
        }.let { file(context).writeText(it.toString()) }
    }
}
