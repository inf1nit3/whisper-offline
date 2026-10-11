package dev.whisper.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabularyPromptTest {

    private val defaults = setOf("deployment", "sprint", "pull request")

    @Test fun ownWordsComeBeforeDefaults() {
        val prompt = Vocabulary.buildPrompt(
            listOf("Deployment", "Scheisssewasser", "Sprint"), defaults)
        assertTrue(prompt.indexOf("Scheisssewasser") < prompt.indexOf("Deployment"))
        assertTrue(prompt.endsWith("."))
    }

    @Test fun emptyListGivesEmptyPrompt() {
        assertEquals("", Vocabulary.buildPrompt(emptyList(), defaults))
    }

    @Test fun staysWithinBudget() {
        val long = (1..50).map { "Wort$it mit Länge" }
        assertTrue(Vocabulary.buildPrompt(long, emptySet()).length <= 801)
    }

    @Test fun keepsRelativeOrderInsideGroups() {
        val prompt = Vocabulary.buildPrompt(
            listOf("Backlog", "Deployment", "Meier"), defaults)
        assertEquals("Backlog, Meier, Deployment.", prompt)
    }
}
