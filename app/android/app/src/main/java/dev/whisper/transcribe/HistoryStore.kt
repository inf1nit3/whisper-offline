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
) {
    companion object {
        const val SOURCE_APP = "app"
        const val SOURCE_FILE = "file"
        const val SOURCE_KEYBOARD = "keyboard"
        const val SOURCE_DICTATION = "dictation"
        const val SOURCE_MEETING = "meeting"
        const val SOURCE_SELECTION = "selection"
    }

    /// Wörter im Transkript (für Statistik).
    val words: Int get() = text.split(Regex("""\s+""")).count { w -> w.any { it.isLetterOrDigit() } }

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
                })
            }
        }.let { file(context).writeText(it.toString()) }
    }
}
