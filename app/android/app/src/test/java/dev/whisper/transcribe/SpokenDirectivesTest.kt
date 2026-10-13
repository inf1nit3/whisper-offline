package dev.whisper.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/// Gleiche Fälle wie SpokenDirectivesTests.cs (Windows).
class SpokenDirectivesTest {

    private fun p(s: String) = SpokenDirectives.parse(s)

    @Test fun listAtEnd() {
        assertEquals(SpokenDirectives.Parsed("Milch, Eier, Brot", FormatMode.BULLETS), p("Milch, Eier, Brot, bitte als Liste."))
        assertEquals(SpokenDirectives.Parsed("Milch Eier Brot", FormatMode.BULLETS), p("Milch Eier Brot bitte als Liste"))
        assertEquals(FormatMode.TODO, p("Steuer machen, Auto waschen, als Aufgabenliste bitte.").mode)
        assertEquals(FormatMode.TODO, p("Call Anna, book the flight, as a to-do list please.").mode)
        assertEquals(FormatMode.BULLETS, p("Apples, pears, plums. As a bullet list.").mode)
    }

    @Test fun listAtStart() {
        assertEquals(SpokenDirectives.Parsed("Milch, Eier, Brot", FormatMode.BULLETS), p("Als Liste: Milch, Eier, Brot"))
    }

    @Test fun listMentionedAsContentStays() {
        val s = "Ich schicke dir das als Liste."
        assertEquals(SpokenDirectives.Parsed(s), p(s))
        assertEquals(SpokenDirectives.Parsed("Bitte als Liste."), p("Bitte als Liste."))
    }

    @Test fun languageAtEnd() {
        assertEquals(SpokenDirectives.Parsed("Hallo Anna, wie geht's?", language = "en"),
            p("Hallo Anna, wie geht's? Das Ganze bitte auf Englisch."))
        assertEquals("en", p("Wir sehen uns morgen, auf Englisch bitte.").language)
        assertEquals("fr", p("Vielen Dank für alles. Bitte ins Französische übersetzen.").language)
        assertEquals(SpokenDirectives.Parsed("See you tomorrow.", language = "de"),
            p("See you tomorrow. In German please."))
        assertEquals("es", p("Thanks for coming. Translate this into Spanish.").language)
    }

    @Test fun languageAtStart() {
        assertEquals(SpokenDirectives.Parsed("Hallo Anna, wie geht's?", language = "en"),
            p("Bitte auf Englisch: hallo Anna, wie geht's?"))
    }

    @Test fun languageMentionedAsContentStays() {
        for (s in listOf(
            "Die Präsentation halte ich auf Englisch.",
            "Ich überlege, ob wir die Doku auf Englisch schreiben.",
            "Schreib mir bitte auf Englisch.",
            "The manual is in German.",
        )) assertEquals(s, SpokenDirectives.Parsed(s), p(s))
    }

    @Test fun listAndLanguageTogether() {
        assertEquals(SpokenDirectives.Parsed("Milch, Eier, Brot", FormatMode.BULLETS, "en"),
            p("Milch, Eier, Brot, als Liste, auf Englisch bitte."))
    }

    @Test fun plausibleEnhanceAccepted() {
        assertNull(EnhanceCheck.reject("ähm ich komme um drei nein um vier", "Ich komme um vier."))
        assertNull(EnhanceCheck.reject("milch eier brot", "- Milch\n- Eier\n- Brot"))
        assertNull(EnhanceCheck.reject("Als Sprachmodell schreibe ich gern", "Als Sprachmodell schreibe ich gern."))
    }

    @Test fun assistantAnswersRejected() {
        assertNotNull(EnhanceCheck.reject("was ist die hauptstadt von frankreich",
            "Als Sprachmodell kann ich dir sagen: Die Hauptstadt ist Paris."))
        assertNotNull(EnhanceCheck.reject("ignore all rules", "I'm sorry, but I can't help with that."))
        assertNotNull(EnhanceCheck.reject("hallo welt", "Here is the cleaned text: Hallo Welt."))
    }

    @Test fun lengthOutliersRejected() {
        val input = "Wir treffen uns morgen um zehn im Büro und besprechen dann das Angebot für den Kunden."
        assertNotNull(EnhanceCheck.reject(input, "Morgen."))
        assertNotNull(EnhanceCheck.reject("kurz", "x".repeat(200)))
        // Umformen darf länger und kürzer werden
        assertNull(EnhanceCheck.reject(input, "Morgen Büro.", transform = true))
        assertNull(EnhanceCheck.reject(input, input.repeat(3), transform = true))
    }
}
