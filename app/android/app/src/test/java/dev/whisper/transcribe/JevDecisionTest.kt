package dev.whisper.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JevDecisionTest {

    private fun choice(pick: String, confidence: Double, vararg probs: Pair<String, Double>) =
        JevAnswer.Choice(pick, mapOf(*probs), confidence)

    private fun response(
        intent: JevAnswer.Choice,
        project: JevAnswer.Choice = choice("whisper_app", 0.91, "whisper_app" to 0.94, "unassigned" to 0.02),
        urgency: JevAnswer.Score = JevAnswer.Score(2.12, mapOf("0" to "a", "1" to "b", "2" to "c", "3" to "d"), emptyMap(), 0.84),
        cleanup: Double = 0.08,
        actionable: Double = 0.96,
    ) = JevResponse("jev-1.13.0", mapOf(
        JevDecision.Q_INTENT to intent,
        JevDecision.Q_PROJECT to project,
        JevDecision.Q_URGENCY to urgency,
        JevDecision.Q_CLEANUP to JevAnswer.Noul(cleanup),
        JevDecision.Q_ACTIONABLE to JevAnswer.Noul(actionable),
    ))

    private val codeIntent = choice("code_or_bug", 0.78, "todo_task" to 0.24, "code_or_bug" to 0.72, "general_note" to 0.02)

    @Test fun highConfidenceRoutesAutomatically() {
        val r = JevDecision.decide(response(choice("code_or_bug", 0.90, "code_or_bug" to 0.9, "todo_task" to 0.05)), 8.4)!!
        assertEquals("code_or_bug", r.analysis.intent)
        assertEquals("whisper_app", r.analysis.projectTag)
        assertEquals(2.12, r.analysis.urgencyScore!!, 1e-9)
        assertEquals(true, r.analysis.isActionable)
        assertEquals(false, r.analysis.needsCleanup)
        assertFalse(r.analysis.needsDisambiguation)
        assertFalse(r.discard)
    }

    @Test fun mediumConfidenceOffersTopTwoAlternative() {
        val a = JevDecision.decide(response(codeIntent), 8.4)!!.analysis
        assertEquals("code_or_bug", a.intent)
        assertEquals("todo_task", a.topAlternativeIntent)
        assertTrue(a.needsDisambiguation)
        // Projekt (0,91) und Dringlichkeit bleiben erhalten
        assertEquals("whisper_app", a.projectTag)
    }

    @Test fun lowConfidenceFallsBackToGeneralNote() {
        val a = JevDecision.decide(response(choice("code_or_bug", 0.40, "code_or_bug" to 0.4, "todo_task" to 0.3)), 8.4)!!.analysis
        assertEquals("general_note", a.intent)
        assertEquals(0.40, a.intentConfidence, 1e-9)
        assertNull(a.topAlternativeIntent)
        assertNull(a.projectTag)
        assertNull(a.urgencyScore)
        assertFalse(a.needsDisambiguation)
    }

    @Test fun projectNeedsHighConfidenceAndIsNeverUnassigned() {
        val mid = response(codeIntent, project = choice("whisper_app", 0.70, "whisper_app" to 0.7))
        assertNull(JevDecision.decide(mid, 5.0)!!.analysis.projectTag)
        val none = response(codeIntent, project = choice("unassigned", 0.95, "unassigned" to 0.95))
        assertNull(JevDecision.decide(none, 5.0)!!.analysis.projectTag)
    }

    @Test fun cleanupGateAtSeventyPercent() {
        assertEquals(true, JevDecision.decide(response(codeIntent, cleanup = 0.70), 5.0)!!.analysis.needsCleanup)
        assertEquals(false, JevDecision.decide(response(codeIntent, cleanup = 0.69), 5.0)!!.analysis.needsCleanup)
    }

    @Test fun actionableIsUnknownWhenUncertain() {
        assertNull(JevDecision.decide(response(codeIntent, actionable = 0.5), 5.0)!!.analysis.isActionable)
        assertEquals(false, JevDecision.decide(response(codeIntent, actionable = 0.1), 5.0)!!.analysis.isActionable)
    }

    @Test fun noiseGuardNeedsAllThreeConditions() {
        val noise = choice("noise_or_empty", 0.90, "noise_or_empty" to 0.9, "general_note" to 0.05)
        assertTrue(JevDecision.decide(response(noise), 2.0)!!.discard)
        assertFalse("zu lang", JevDecision.decide(response(noise), 3.0)!!.discard)
        assertFalse("zu unsicher", JevDecision.decide(response(choice("noise_or_empty", 0.84, "noise_or_empty" to 0.84)), 2.0)!!.discard)
        assertFalse("anderer Intent", JevDecision.decide(response(codeIntent), 2.0)!!.discard)
    }

    @Test fun missingIntentAnswerYieldsNoDecision() {
        assertNull(JevDecision.decide(JevResponse("m", emptyMap()), 5.0))
    }

    @Test fun rankedAndAlternativeSortByProbability() {
        val c = choice("a", 0.6, "a" to 0.5, "b" to 0.1, "c" to 0.4)
        assertEquals(listOf("a", "c", "b"), c.ranked().map { it.first })
        assertEquals("c", c.alternative())
        assertNull(choice("a", 0.6, "a" to 1.0).alternative())
    }

    @Test fun scoreLegendUsesRoundedStringKey() {
        val s = JevAnswer.Score(2.12, mapOf("0" to "low", "2" to "mid", "3" to "high"), emptyMap(), 0.8)
        assertEquals("mid", s.legendLabel())
        assertEquals("high", JevAnswer.Score(2.5, s.legend, emptyMap(), 0.8).legendLabel())
        assertNull(JevAnswer.Score(1.0, s.legend, emptyMap(), 0.8).legendLabel())
    }

    @Test fun sanitizeTagsDropsBlankReservedAndDuplicates() {
        val tags = JevDecision.sanitizeTags(listOf(
            JevProjectTag(" app ", " d "), JevProjectTag("app", "dup"), JevProjectTag("", "x"),
            JevProjectTag("unassigned", "x"), JevProjectTag("web", ""),
        ))
        assertEquals(listOf(JevProjectTag("app", "d"), JevProjectTag("web", "")), tags)
        assertNotNull(JevQuestions.DEFAULT_TAGS.firstOrNull { it.key == "whisper_app" })
    }

    @Test fun labelsAndKeys() {
        assertEquals("Code/Bug", JevDecision.intentLabel("code_or_bug"))
        assertEquals("Whisper App", JevDecision.prettyKey("whisper_app"))
        assertEquals("Unbekannt Neu", JevDecision.intentLabel("unbekannt_neu"))
    }
}
