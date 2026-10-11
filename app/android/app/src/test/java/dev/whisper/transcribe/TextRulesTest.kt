package dev.whisper.transcribe

import org.junit.Assert.assertEquals
import org.junit.Test

class TextRulesTest {

    @Test fun spokenLayoutAndPunctuation() {
        assertEquals("Hallo Herr Müller,\n\nvielen Dank für Ihre Nachricht.\nViele Grüße",
            TextRules.applySpokenCommands(
                "Hallo Herr Müller Komma. Neuer Absatz. vielen Dank für Ihre Nachricht Punkt neue Zeile Viele Grüße"))
    }

    @Test fun englishCommands() {
        assertEquals("Hi Anna,\nsee you tomorrow?",
            TextRules.applySpokenCommands("Hi Anna comma new line see you tomorrow question mark"))
    }

    @Test fun commandWordsInsideOtherWordsStay() {
        val text = "Der Kommandant fragt nach dem Punktestand."
        assertEquals(text, TextRules.applySpokenCommands(text))
    }

    @Test fun fillersGermanKeepsPreposition() {
        assertEquals("Ich komme um vier.", TextRules.removeFillers("Äh, ich komme ähm um vier.", "de"))
        assertEquals("I think, we should go.", TextRules.removeFillers("Um, I think, uh, we should go.", "en"))
    }

    @Test fun capitalizes() {
        assertEquals("Hallo. Wie geht's?\nGut! Am 1. Mai um 3.5 Uhr",
            TextRules.capitalizeSentences("hallo. wie geht's?\ngut! am 1. Mai um 3.5 Uhr"))
        assertEquals("- [ ] Milch kaufen", TextRules.capitalizeSentences("- [ ] milch kaufen"))
    }

    @Test fun tidyRemovesStraySpaces() {
        assertEquals("Hallo, Welt!\n\nZweiter Absatz", TextRules.tidy("Hallo ,Welt !  \n\n\n\nZweiter   Absatz  "))
    }

    @Test fun guessesLanguage() {
        assertEquals("de", TextRules.guessLanguage("Ich schicke dir die Unterlagen bis Freitag, danke."))
        assertEquals("en", TextRules.guessLanguage("I will send you the documents by Friday, thanks."))
        assertEquals("", TextRules.guessLanguage("Okay"))
    }
}
