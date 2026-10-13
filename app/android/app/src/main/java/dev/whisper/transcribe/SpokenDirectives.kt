package dev.whisper.transcribe

/// Gesprochene Anweisungen an Enhance am Anfang oder Ende eines Diktats:
/// „…, bitte als Liste.“ / „Das Ganze bitte auf Englisch.“ / „in English please“.
///
/// Vorbild ist Whisper Bar, das solche Anweisungen dem Sprachmodell überlässt.
/// Die kleinen Modelle hier befolgen das nicht verlässlich — deshalb erkennt
/// eine feste Regel die Anweisung, nimmt sie aus dem Text und gibt Modus bzw.
/// Zielsprache gezielt mit. Nur am Rand des Diktats und als eigener Satzteil
/// (Satzzeichen davor), damit Inhalt wie „Die Präsentation halte ich auf
/// Englisch.“ unangetastet bleibt. Reine Funktionen, unit-getestet; dieselben
/// Regeln stehen in TextLogic.cs (Windows).
object SpokenDirectives {

    data class Parsed(
        val text: String,
        /// BULLETS oder TODO, wenn eine Listen-Anweisung erkannt wurde.
        val mode: FormatMode? = null,
        /// Zielsprache ("en", "de" …), wenn eine Sprach-Anweisung erkannt wurde.
        val language: String? = null,
    )

    private val DE_LANGS = mapOf(
        "englisch" to "en", "deutsch" to "de", "französisch" to "fr", "spanisch" to "es",
        "italienisch" to "it", "niederländisch" to "nl", "holländisch" to "nl", "polnisch" to "pl",
        "portugiesisch" to "pt", "türkisch" to "tr", "russisch" to "ru",
    )
    private val EN_LANGS = mapOf(
        "english" to "en", "german" to "de", "french" to "fr", "spanish" to "es", "italian" to "it",
        "dutch" to "nl", "polish" to "pl", "portuguese" to "pt", "turkish" to "tr", "russian" to "ru",
    )

    private val DE_LANG = "(?:" + DE_LANGS.keys.joinToString("|") + ")(?:e|en)?"
    private val EN_LANG = "(?:" + EN_LANGS.keys.joinToString("|") + ")"

    private val LANG_CORE =
        """(?:übrigens,?\s+)?(?:(?:das\s+ganze|alles)\s+)?(?:bitte\s+)?""" +
            """(?:(?:auf|ins?)\s+$DE_LANG(?:\s+übersetzen)?|übersetze?n?\s+(?:(?:das|es|alles)\s+)?(?:ins?|auf)\s+$DE_LANG)""" +
            """(?:,?\s+bitte)?""" +
            """|(?:please\s+)?(?:(?:translate|write)\s+(?:(?:this|it|everything|the\s+whole\s+thing|all\s+of\s+(?:this|it))\s+)?)?""" +
            """(?:in|into)\s+$EN_LANG(?:,?\s+please)?"""

    /// Ohne eines dieser Wörter ist „auf Englisch“ Inhalt, keine Anweisung.
    private val LANG_MARKER = Regex("""bitte|ganze|alles|übersetz|please|translate|write""", RegexOption.IGNORE_CASE)

    private val LIST_CORE =
        """(?:bitte\s+|please\s+)?(?:als|as|in)\s+(?:a\s+|form\s+einer\s+)?""" +
            """(?:to-?do-?liste|todo\s+liste|aufgabenliste|checkliste|liste|aufzählung|stichpunkten?""" +
            """|(?:(?:to-?do|task|check|bullet(?:ed)?)\s*)?list|bullet\s+points)""" +
            """(?:,?\s+(?:bitte|please))?"""

    private val TODO_WORDS = Regex("""to-?do|aufgabe|task|check""", RegexOption.IGNORE_CASE)

    // Am Ende: als eigener Satzteil (Satzzeichen davor); Listen auch direkt mit „bitte“
    private val LANG_END = Regex("""(^|[.,!?;:]\s*)($LANG_CORE)\s*[.!]?\s*$""", RegexOption.IGNORE_CASE)
    private val LIST_END = Regex("""(^|[.,!?;:]\s*|\s+(?=(?:bitte|please)\s))($LIST_CORE)\s*[.!]?\s*$""", RegexOption.IGNORE_CASE)
    // Am Anfang: mit Satzzeichen dahinter, danach folgt der eigentliche Text
    private val LANG_START = Regex("""^\s*($LANG_CORE)\s*[:.,!;]\s*(?=\S)""", RegexOption.IGNORE_CASE)
    private val LIST_START = Regex("""^\s*($LIST_CORE)\s*[:.,!;]\s*(?=\S)""", RegexOption.IGNORE_CASE)

    fun parse(text: String): Parsed {
        var t = text
        var mode: FormatMode? = null
        var language: String? = null
        // Mehrere Anweisungen hintereinander („…, als Liste, auf Englisch bitte.“)
        repeat(3) {
            if (language == null) strip(t, LANG_START, LANG_END, needsMarker = true)?.let { (rest, match) ->
                language = languageOf(match); t = rest; return@repeat
            }
            if (mode == null) strip(t, LIST_START, LIST_END, needsMarker = false)?.let { (rest, match) ->
                mode = if (TODO_WORDS.containsMatchIn(match)) FormatMode.TODO else FormatMode.BULLETS
                t = rest; return@repeat
            }
            return Parsed(t, mode, language)
        }
        return Parsed(t, mode, language)
    }

    /// (Rest, Anweisung) oder null. Bleibt nichts übrig, war es keine Anweisung.
    private fun strip(text: String, start: Regex, end: Regex, needsMarker: Boolean): Pair<String, String>? {
        end.find(text)?.let { m ->
            val directive = m.groupValues[2]
            if (!needsMarker || LANG_MARKER.containsMatchIn(directive)) {
                // Satzende vor der Anweisung bleibt stehen („…, wie geht's? Das Ganze …“)
                val keep = m.groupValues[1].trim().takeIf { it in setOf(".", "!", "?") }.orEmpty()
                val rest = (text.substring(0, m.range.first).trimEnd() + keep).trimEnd(',', ';', ':', ' ', '\n')
                if (rest.isNotBlank()) return rest to directive
            }
        }
        start.find(text)?.let { m ->
            val directive = m.groupValues[1]
            if (!needsMarker || LANG_MARKER.containsMatchIn(directive)) {
                val rest = text.substring(m.range.last + 1).trim()
                if (rest.isNotBlank()) return rest.replaceFirstChar { it.uppercaseChar() } to directive
            }
        }
        return null
    }

    private fun languageOf(directive: String): String? {
        val lower = directive.lowercase()
        return DE_LANGS.entries.firstOrNull { it.key in lower }?.value
            ?: EN_LANGS.entries.firstOrNull { Regex("""\b${it.key}\b""").containsMatchIn(lower) }?.value
    }
}

/// Plausibilitätsprüfung für Antworten des Sprachmodells — wie bei Whisper Bar:
/// Ein unbrauchbares Ergebnis wird verworfen und das Diktat ohne Enhance
/// eingefügt, statt eine Chatbot-Antwort oder einen halben Text einzusetzen.
object EnhanceCheck {

    /// Typische Antworten eines Assistenten statt bearbeiteten Textes. Die
    /// ersten stammen aus Whisper Bar, die übrigen sind Einleitungen, mit
    /// denen kleine Modelle gern beginnen.
    private val ASSISTANT_PHRASES = listOf(
        "as an ai", "i'm sorry, but i", "i am sorry, but i", "i'm here to help", "prompt injection",
        "als ki-assistent", "als sprachmodell", "ich kann nicht helfen", "ich kann dabei nicht helfen",
        "ich bin ein sprachmodell", "ich bin ein assistent", "ich bin ein chatbot",
        "here is the cleaned", "here is the rewritten", "here is the edited", "here's the cleaned",
        "here's the rewritten", "hier ist der bereinigte", "hier ist der überarbeitete",
        "hier ist die überarbeitete", "hier ist die bereinigte",
    )

    /// Grund der Ablehnung oder null, wenn das Ergebnis brauchbar ist.
    /// [transform]: Umformen darf deutlich länger werden („ausführlicher“)
    /// und kürzer („kürzen“) als ein bereinigtes Diktat.
    fun reject(input: String, output: String, transform: Boolean = false): String? {
        val out = output.trim()
        if (out.isEmpty()) return "leeres Ergebnis"
        val lo = out.lowercase()
        val li = input.lowercase()
        if (ASSISTANT_PHRASES.any { it in lo && it !in li }) return "Antwort statt bearbeitetem Text"
        val n = input.trim().length
        if (transform) {
            if (out.length > n * 5 + 400) return "Ergebnis viel zu lang"
        } else {
            if (out.length > n * 3 + 120) return "Ergebnis viel länger als das Diktat"
            if (n >= 60 && out.length < n / 4) return "Ergebnis viel kürzer als das Diktat"
        }
        return null
    }
}
