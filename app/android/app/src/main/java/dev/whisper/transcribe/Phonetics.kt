package dev.whisper.transcribe

/// Bausteine für den unscharfen Wortabgleich: Kölner Phonetik (für deutsche
/// Aussprache gebaut, taugt aber auch für englische Namen leidlich) und eine
/// normierte Editierdistanz. Reine Funktionen, ohne Android — unit-getestet.
object Phonetics {

    /// Kölner Phonetik: „Meier“, „Maier“ und „Mayr“ ergeben alle „67“.
    /// Nicht-Buchstaben werden ignoriert, Umlaute wie Vokale behandelt.
    fun cologne(word: String): String {
        val s = word.lowercase()
            .replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "s")
            .filter { it in 'a'..'z' }
        if (s.isEmpty()) return ""
        val raw = StringBuilder()
        for (i in s.indices) {
            val c = s[i]
            val prev = if (i > 0) s[i - 1] else ' '
            val next = if (i + 1 < s.length) s[i + 1] else ' '
            val code = when (c) {
                'a', 'e', 'i', 'j', 'o', 'u', 'y' -> "0"
                'h' -> ""
                'b' -> "1"
                'p' -> if (next == 'h') "3" else "1"
                'd', 't' -> if (next in "csz") "8" else "2"
                'f', 'v', 'w' -> "3"
                'g', 'k', 'q' -> "4"
                'c' -> when {
                    i == 0 -> if (next in "ahkloqrux") "4" else "8"
                    prev in "sz" -> "8"
                    next in "ahkoqux" -> "4"
                    else -> "8"
                }
                'x' -> if (prev in "ckq") "8" else "48"
                'l' -> "5"
                'm', 'n' -> "6"
                'r' -> "7"
                's', 'z' -> "8"
                else -> ""
            }
            raw.append(code)
        }
        // Doppelte hintereinander zusammenfassen, dann alle Nullen außer am Anfang weg
        val collapsed = StringBuilder()
        for (ch in raw) if (collapsed.isEmpty() || collapsed.last() != ch) collapsed.append(ch)
        val out = StringBuilder()
        collapsed.forEachIndexed { i, ch -> if (ch != '0' || i == 0) out.append(ch) }
        return out.toString()
    }

    /// Levenshtein-Distanz, Groß-/Kleinschreibung egal.
    fun distance(a: String, b: String): Int {
        val x = a.lowercase()
        val y = b.lowercase()
        if (x == y) return 0
        if (x.isEmpty()) return y.length
        if (y.isEmpty()) return x.length
        var prev = IntArray(y.length + 1) { it }
        var cur = IntArray(y.length + 1)
        for (i in 1..x.length) {
            cur[0] = i
            for (j in 1..y.length) {
                val cost = if (x[i - 1] == y[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[y.length]
    }

    /// 1.0 = gleich, 0.0 = nichts gemeinsam.
    fun similarity(a: String, b: String): Double {
        val len = maxOf(a.length, b.length)
        if (len == 0) return 1.0
        return 1.0 - distance(a, b).toDouble() / len
    }

    /// Nur Buchstaben und Ziffern, klein — „Claude-Code“ und „claude code“ sind gleich.
    fun compact(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /// Klein, ohne Satzzeichen, einfache Leerzeichen — für Sprachbefehle.
    fun normalizePhrase(s: String): String =
        s.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
}
