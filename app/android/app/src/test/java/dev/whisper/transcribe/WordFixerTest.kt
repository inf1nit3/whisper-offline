package dev.whisper.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordFixerTest {

    @Test fun cologneGroupsSpellings() {
        assertEquals(Phonetics.cologne("Meier"), Phonetics.cologne("Maier"))
        assertEquals(Phonetics.cologne("Meier"), Phonetics.cologne("Mayr"))
        assertEquals("65752682", Phonetics.cologne("Müller-Lüdenscheidt"))
    }

    @Test fun fixesSplitAndMisspelledName() {
        val words = listOf("Scheisssewasser")
        assertEquals("Hallo Scheisssewasser, wie geht's?",
            WordFixer.correct("Hallo Scheiße Wasser, wie geht's?", words))
        assertEquals("Grüße von Scheisssewasser.",
            WordFixer.correct("Grüße von Scheißewasser.", words))
    }

    @Test fun fixesCasingAndSpacingOfMultiWordEntry() {
        assertEquals("Ich nutze Claude Code dafür",
            WordFixer.correct("Ich nutze claude code dafür", listOf("Claude Code")))
    }

    @Test fun fixesSimilarSoundingName() {
        assertEquals("Termin mit Herrn Meier", WordFixer.correct("Termin mit Herrn Maier", listOf("Meier")))
    }

    @Test fun leavesUnrelatedWordsAlone() {
        val words = listOf("Claude", "Kasse", "Umsatzsteuervoranmeldung")
        val text = "Klaus stellt den Kasten neben die Kanne."
        assertEquals(text, WordFixer.correct(text, words))
    }

    @Test fun shortWordsOnlyExact() {
        assertNull(WordFixer.score("Mix", "Max"))
        assertEquals(1.0, WordFixer.score("max", "Max")!!, 0.0)
    }

    @Test fun keepsPunctuationAroundReplacement() {
        assertEquals("(Umsatzsteuervoranmeldung)",
            WordFixer.correct("(Umsatzsteuer Voranmeldung)", listOf("Umsatzsteuervoranmeldung")))
    }

    @Test fun snippetMatchesWholeDictationOnly() {
        val snippets = listOf(Snippets.Snippet("YouTube Kanal Link", "https://youtube.com/@example"))
        assertEquals("https://youtube.com/@example", Snippets.match("YouTube-Kanal-Link.", snippets))
        assertEquals("https://youtube.com/@example", Snippets.match("Youtube Kanallink", snippets))
        assertNull(Snippets.match("Schick mir den YouTube Kanal Link bitte", snippets))
    }
}
