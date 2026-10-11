package dev.whisper.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingStatsTest {

    @Test fun mergesTurnsOfSameSpeakerAndSplitsLongOnes() {
        val turns = listOf(
            SpeakerMath.Turn(0, 0f, 5f),
            SpeakerMath.Turn(0, 5.5f, 9f),    // gleiche Person, kurze Pause → anhängen
            SpeakerMath.Turn(1, 9.2f, 12f),
            SpeakerMath.Turn(1, 15f, 75f),    // 60 s → in 28-s-Stücke
        )
        val merged = SpeakerMath.mergeTurns(turns)
        assertEquals(SpeakerMath.Turn(0, 0f, 9f), merged[0])
        assertEquals(SpeakerMath.Turn(1, 9.2f, 12f), merged[1])
        assertTrue(merged.drop(2).all { it.end - it.start <= 28f })
        assertEquals(75f, merged.last().end, 0.001f)
    }

    @Test fun registryMatchesSimilarVoices() {
        val r = SpeakerMath.Registry(threshold = 0.6f, maxSpeakers = 0)
        val a = floatArrayOf(1f, 0f, 0f)
        val a2 = floatArrayOf(0.9f, 0.1f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        assertEquals(0, r.assign(a))
        assertEquals(1, r.assign(b))
        assertEquals(0, r.assign(a2))
    }

    @Test fun registryRespectsFixedSpeakerCount() {
        val r = SpeakerMath.Registry(threshold = 0.99f, maxSpeakers = 2)
        r.assign(floatArrayOf(1f, 0f)); r.assign(floatArrayOf(0f, 1f))
        assertEquals(0, r.assign(floatArrayOf(0.7f, 0.3f)))
    }

    @Test fun statsSumUpAndSaveTime() {
        val now = 100L * 86_400_000L
        val entries = listOf(
            HistoryEntry(now - 1000, "eins zwei drei vier", "m", "de", 2f, HistoryEntry.SOURCE_KEYBOARD),
            HistoryEntry(now - 2 * 86_400_000L, "fünf sechs", "m", "en", 1f, HistoryEntry.SOURCE_FILE),
            HistoryEntry(now - 40 * 86_400_000L, "alt", "m", "de", 1f, HistoryEntry.SOURCE_APP),
        )
        val week = Stats.compute(entries, 7, null, now)
        assertEquals(2, week.count)
        assertEquals(6, week.words)
        assertEquals(7, week.perDay.size)
        val dictation = Stats.compute(entries, 0, "dictation", now)
        assertEquals(2, dictation.count)
    }
}
