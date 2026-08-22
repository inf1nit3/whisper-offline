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
) {
    fun dateText(): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(timeMs))
}

/// Transkriptions-Verlauf als JSON-Datei im App-Verzeichnis.
object HistoryStore {

    private fun file(context: Context) = File(context.filesDir, "history.json")

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
                )
            }
        }.getOrDefault(emptyList())

    fun add(context: Context, entry: HistoryEntry) {
        val all = load(context).toMutableList()
        all.add(entry)
        // Neueste zuerst, maximal 500 Einträge
        val trimmed = all.takeLast(500).reversed()
        JSONArray().apply {
            trimmed.forEach { e ->
                put(JSONObject().apply {
                    put("t", e.timeMs)
                    put("x", e.text)
                    put("m", e.model)
                    put("l", e.language)
                    put("s", e.audioSeconds.toDouble())
                })
            }
        }.let { file(context).writeText(it.toString()) }
    }

    fun clear(context: Context) { file(context).delete() }

    fun delete(context: Context, entry: HistoryEntry) {
        val remaining = load(context).filter { it.timeMs != entry.timeMs }
        JSONArray().apply {
            remaining.forEach { e ->
                put(JSONObject().apply {
                    put("t", e.timeMs); put("x", e.text)
                    put("m", e.model); put("l", e.language)
                    put("s", e.audioSeconds.toDouble())
                })
            }
        }.let { file(context).writeText(it.toString()) }
    }
}
