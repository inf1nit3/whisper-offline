package dev.whisper.transcribe

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/// Eigene Ersetzungsregeln für wiederkehrende Erkennungsfehler, etwa Namen:
/// „Scheißewasser“ → „Scheisssewasser“. Wirkt auf jedes Transkript — App,
/// Tastatur und Diktat —, weil WhisperBridge sie zentral anwendet.
object Replacements {

    data class Rule(val from: String, val to: String)

    private const val KEY = "replacements"

    @Volatile var rules: List<Rule> = emptyList()
        private set

    @Volatile private var compiled: List<Pair<Regex, String>> = emptyList()

    fun init(context: Context) {
        val json = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(KEY, null)
        rules = runCatching {
            val arr = JSONArray(json ?: "[]")
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Rule(o.getString("f"), o.optString("t"))
            }
        }.getOrDefault(emptyList())
        compile()
    }

    fun save(context: Context, newRules: List<Rule>) {
        rules = newRules.filter { it.from.isNotBlank() }
        val arr = JSONArray()
        rules.forEach { arr.put(JSONObject().put("f", it.from).put("t", it.to)) }
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
        compile()
    }

    /// Nur ganze Wörter, Groß-/Kleinschreibung egal: „Max“ ersetzt nicht das
    /// „max“ in „maximal“.
    private fun compile() {
        compiled = rules.map { r ->
            Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(r.from.trim()) + "(?![\\p{L}\\p{N}])",
                RegexOption.IGNORE_CASE) to Regex.escapeReplacement(r.to)
        }
    }

    fun apply(text: String): String =
        compiled.fold(text) { acc, (regex, to) -> regex.replace(acc, to) }
}
