package dev.whisper.transcribe

/// Regelbasierte Textschritte rund ums Diktat — verlässlich und ohne Modell.
///
/// Gesprochene Befehle („Komma“, „neuer Absatz“) setzt ein kleines Sprachmodell
/// nicht zuverlässig um; als feste Regel klappt es immer, mit und ohne Enhance.
/// Reine Funktionen ohne Android, unit-getestet.
object TextRules {

    private const val SEP = """[\s,.;:!?]*"""

    private class Cmd(val words: String, val out: String)

    private val LAYOUT = listOf(
        Cmd("neuer absatz|neuen absatz|new paragraph", "\n\n"),
        Cmd("neue zeile|zeilenumbruch|new line|newline", "\n"),
    )

    private val PUNCT = listOf(
        Cmd("komma|comma", ","),
        Cmd("punkt|period|full stop", "."),
        Cmd("fragezeichen|question mark", "?"),
        Cmd("ausrufezeichen|exclamation mark|exclamation point", "!"),
        Cmd("doppelpunkt|colon", ":"),
        Cmd("semikolon|semicolon", ";"),
    )

    /// „neuer Absatz“ / „neue Zeile“ und gesprochene Satzzeichen umsetzen.
    /// Die Spracherkennung setzt um das Befehlswort oft selbst Satzzeichen
    /// („Müller Komma. Neuer Absatz.“) — die fallen mit weg.
    fun applySpokenCommands(text: String): String {
        var s = text
        for (c in LAYOUT) {
            s = Regex("""$SEP(?<![\p{L}\p{N}])(${c.words})(?![\p{L}\p{N}])$SEP""", RegexOption.IGNORE_CASE)
                .replace(s) { c.out }
        }
        for (c in PUNCT) {
            // Davor nur Leerraum schlucken, danach eine automatisch gesetzte
            // Interpunktion; das Satzzeichen hängt am vorigen Wort.
            s = Regex("""[ \t]*[,.;:]?[ \t]*(?<![\p{L}\p{N}])(${c.words})(?![\p{L}\p{N}])[.,;:]?""", RegexOption.IGNORE_CASE)
                .replace(s) { c.out }
        }
        return tidy(s)
    }

    private val FILLERS_DE = "äh|ähm|öhm|ehm|hm|hmm|mhm"
    private val FILLERS_EN = "uh|uhm|um|umm|erm|hmm|mhm"

    /// Füllwörter entfernen. „um“ ist im Deutschen eine Präposition („um vier“)
    /// und fällt deshalb nur bei englischem Text weg.
    fun removeFillers(text: String, language: String): String {
        val words = if (language == "en") "$FILLERS_DE|$FILLERS_EN" else FILLERS_DE
        val s = tidy(Regex("""(?<![\p{L}\p{N}])($words)(?![\p{L}\p{N}])[,.]?""", RegexOption.IGNORE_CASE).replace(text, ""))
        // Stand das Füllwort am Satzanfang, rückt das nächste Wort nach vorn
        return if (text.firstOrNull()?.isUpperCase() == true) s.replaceFirstChar { it.uppercaseChar() } else s
    }

    /// Leerzeichen vor Satzzeichen, doppelte Leerzeichen, Leerraum am
    /// Zeilenende und mehr als eine Leerzeile entfernen.
    fun tidy(text: String): String =
        text.replace(Regex("""[ \t]+([,.;:!?])"""), "$1")
            .replace(Regex("""([,;:])(?=[\p{L}])"""), "$1 ")
            .replace(Regex("""[ \t]{2,}"""), " ")
            .lines().joinToString("\n") { it.trim() }
            .replace(Regex("""\n{3,}"""), "\n\n")
            .replace(Regex("""^[,.;:]\s*"""), "")
            .trim()

    /// Großbuchstabe am Text-, Zeilen- und Satzanfang. Nicht für Chat und
    /// Code — dort ist Kleinschreibung oft gewollt.
    fun capitalizeSentences(text: String): String {
        val sb = StringBuilder(text)
        var start = true
        for (i in sb.indices) {
            val c = sb[i]
            when {
                start && c.isLetter() -> { sb.setCharAt(i, c.uppercaseChar()); start = false }
                c == '.' || c == '!' || c == '?' -> {
                    // Zahl mit Punkt („3.5“, „1. Mai“) oder Abkürzung („z.B.“): kein Satzende
                    val next = sb.getOrNull(i + 1)
                    val prev = sb.getOrNull(i - 1)
                    start = next == null || next.isWhitespace() && !(c == '.' && prev?.isDigit() == true)
                }
                c == '\n' -> start = true
                start && !c.isWhitespace() && c != '-' && c != '*' && c != '[' && c != ']' && c != '"' && c != '„' -> start = false
            }
        }
        return sb.toString()
    }

    private val DE_HINTS = setOf("und", "der", "die", "das", "ist", "nicht", "ich", "wir", "sie", "mit", "auf", "ein", "eine",
        "zu", "es", "du", "dass", "für", "von", "den", "dem", "auch", "noch", "aber", "wie", "bitte", "danke", "schon")
    private val EN_HINTS = setOf("the", "and", "is", "not", "you", "we", "they", "with", "on", "a", "an", "to", "it",
        "that", "for", "of", "this", "are", "was", "have", "be", "please", "thanks", "but", "what", "will")

    /// Grobe Spracherkennung Deutsch/Englisch über Funktionswörter. "" = unklar.
    fun guessLanguage(text: String): String {
        val words = text.lowercase().split(Regex("""[^\p{L}]+""")).filter { it.isNotEmpty() }
        val de = words.count { it in DE_HINTS } + words.count { it.any { ch -> ch in "äöüß" } }
        val en = words.count { it in EN_HINTS }
        return when {
            de == 0 && en == 0 -> ""
            de >= en -> "de"
            else -> "en"
        }
    }
}
