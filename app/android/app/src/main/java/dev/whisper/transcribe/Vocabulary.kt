package dev.whisper.transcribe

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/// Eigene Wörter: Namen und Fachbegriffe in der gewünschten Schreibweise.
///
/// Wirkt zweifach: Whisper bekommt die Liste vorab als Prompt und schreibt
/// diese Wörter dann eher richtig. Danach korrigiert [WordFixer], was trotzdem
/// ähnlich klingend danebenliegt („Scheiße Wasser“ → „Scheisssewasser“) —
/// das hilft auch bei Parakeet, das keinen Prompt kennt.
object Vocabulary {

    private const val KEY = "vocabulary"

    @Volatile var words: List<String> = emptyList()
        private set

    /// Mitgelieferte Standardliste häufiger englischer Begriffe (shared/
    /// vocabulary.json) — beim ersten Start als Wörterbuch-Vorbelegung.
    @Volatile var defaults: List<String> = emptyList()
        private set

    private fun defaultsLower(): Set<String> = defaults.mapTo(HashSet()) { it.lowercase() }

    fun init(context: Context) {
        defaults = loadDefaults(context)
        val stored = Settings.prefs(context).getString(KEY, null)
        words = runCatching {
            val arr = JSONArray(stored ?: "[]")
            List(arr.length()) { arr.getString(it) }
        }.getOrDefault(emptyList())
        // Erster Start: Standardliste säen. Wer das Wörterbuch einmal geöffnet
        // und gespeichert hat (auch leer), behält seinen Stand.
        if (stored == null && defaults.isNotEmpty()) save(context, defaults)
    }

    private fun loadDefaults(context: Context): List<String> = runCatching {
        val a = JSONObject(context.assets.open("vocabulary.json").bufferedReader().use { it.readText() })
            .getJSONArray("words")
        List(a.length()) { a.getString(it) }.map { it.trim() }.filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())

    fun save(context: Context, newWords: List<String>) {
        words = newWords.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        Settings.prefs(context).edit().putString(KEY, JSONArray(words).toString()).apply()
    }

    /// Prompt für Whisper. Eine schlichte Aufzählung beeinflusst die Schreibweise,
    /// ohne den Stil oder die Sprache des Transkripts mitzuziehen. Whisper nutzt
    /// höchstens 224 Prompt-Tokens — mehr als ~800 Zeichen bringen nichts.
    /// Eigene Wörter stehen vor den Standard-Begriffen: Das begrenzte Budget
    /// gehört dem, was der Nutzer selbst gepflegt hat.
    fun prompt(): String = buildPrompt(words, defaultsLower())

    internal fun buildPrompt(words: List<String>, defaults: Set<String>): String {
        if (words.isEmpty()) return ""
        val sb = StringBuilder()
        for (w in words.sortedBy { it.lowercase() in defaults }) {
            if (sb.length + w.length + 2 > 800) break
            if (sb.isNotEmpty()) sb.append(", ")
            sb.append(w)
        }
        return sb.append('.').toString()
    }

    fun correct(text: String): String = WordFixer.correct(text, words)
}

/// Ersetzt ähnlich klingende Wortfolgen durch Einträge der Wortliste.
/// Reine Logik ohne Android, unit-getestet.
object WordFixer {

    private class Token(val start: Int, val end: Int, val lead: String, val core: String, val trail: String)

    private fun tokenize(text: String): List<Token> =
        Regex("""\S+""").findAll(text).map { m ->
            val s = m.value
            val a = s.indexOfFirst { it.isLetterOrDigit() }
            val b = s.indexOfLast { it.isLetterOrDigit() }
            if (a < 0) Token(m.range.first, m.range.last + 1, s, "", "")
            else Token(m.range.first, m.range.last + 1, s.substring(0, a), s.substring(a, b + 1), s.substring(b + 1))
        }.toList()

    /// Wie gut passt eine erkannte Wortfolge zum Zielwort? null = gar nicht.
    /// Bewusst vorsichtig: lieber ein falsch erkanntes Wort stehen lassen als
    /// ein richtiges „korrigieren“.
    internal fun score(heard: String, target: String): Double? {
        val h = Phonetics.compact(heard)
        val t = Phonetics.compact(target)
        if (h.isEmpty() || t.isEmpty()) return null
        if (h == t) return 1.0
        if (t.length < 4) return null // kurze Wörter nur exakt
        val ratio = h.length.toDouble() / t.length
        if (ratio < 0.6 || ratio > 1.6) return null
        val sim = Phonetics.similarity(h, t)
        val ph = Phonetics.cologne(t)
        val sameSound = ph.length >= 2 && Phonetics.cologne(h) == ph
        return when {
            sameSound && sim >= 0.5 -> sim + 0.2
            t.length >= 6 && sim >= 0.8 -> sim
            else -> null
        }
    }

    fun correct(text: String, words: List<String>): String {
        if (words.isEmpty() || text.isBlank()) return text
        var out = text
        // Längere Einträge zuerst: „Claude Code“ vor „Claude“
        for (word in words.sortedByDescending { it.length }) {
            out = correctOne(out, word.trim())
        }
        return out
    }

    private fun correctOne(text: String, word: String): String {
        if (word.isEmpty()) return text
        val n = word.split(Regex("""\s+""")).size
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return text
        val sizes = listOf(n, n + 1, n - 1).filter { it >= 1 }.distinct()

        val sb = StringBuilder()
        var cursor = 0
        var i = 0
        while (i < tokens.size) {
            var best: Pair<Int, Double>? = null
            for (k in sizes) {
                if (i + k > tokens.size) continue
                val window = tokens.subList(i, i + k)
                if (window.any { it.core.isEmpty() }) continue
                // Satzzeichen mitten in der Folge: keine zusammengehörende Wortfolge
                if (window.dropLast(1).any { it.trail.isNotEmpty() } || window.drop(1).any { it.lead.isNotEmpty() }) continue
                val s = score(window.joinToString(" ") { it.core }, word) ?: continue
                if (best == null || s > best.second) best = k to s
            }
            if (best == null) { i++; continue }
            val first = tokens[i]
            val last = tokens[i + best.first - 1]
            val replacement = first.lead + word + last.trail
            sb.append(text, cursor, first.start).append(replacement)
            cursor = last.end
            i += best.first
        }
        sb.append(text, cursor, text.length)
        return sb.toString()
    }
}

/// Snippets: Sagt man nur die Trigger-Phrase, wird stattdessen der hinterlegte
/// Text eingefügt (Signatur, Adresse, Link …).
object Snippets {

    data class Snippet(val trigger: String, val text: String)

    private const val KEY = "snippets"

    @Volatile var all: List<Snippet> = emptyList()
        private set

    fun init(context: Context) {
        val json = Settings.prefs(context).getString(KEY, null)
        all = runCatching {
            val arr = JSONArray(json ?: "[]")
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Snippet(o.getString("t"), o.getString("x"))
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, snippets: List<Snippet>) {
        all = snippets.filter { it.trigger.isNotBlank() && it.text.isNotEmpty() }
        val arr = JSONArray()
        all.forEach { arr.put(JSONObject().put("t", it.trigger).put("x", it.text)) }
        Settings.prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    fun match(text: String): String? = match(text, all)

    /// Ganzes Diktat == Trigger (ohne Satzzeichen und Großschreibung, kleine
    /// Abweichungen erlaubt) → hinterlegter Text.
    fun match(text: String, snippets: List<Snippet>): String? {
        val heard = Phonetics.normalizePhrase(text)
        if (heard.isEmpty()) return null
        var best: Pair<Snippet, Double>? = null
        for (s in snippets) {
            val t = Phonetics.normalizePhrase(s.trigger)
            if (t.isEmpty()) continue
            val sim = if (heard == t) 1.0 else Phonetics.similarity(heard, t)
            val ok = sim == 1.0 || (t.length >= 6 && sim >= 0.85)
            if (ok && (best == null || sim > best.second)) best = s to sim
        }
        return best?.first?.text
    }
}
