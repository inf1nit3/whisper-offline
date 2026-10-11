package dev.whisper.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class ModeAndPauseTest {

    private val text = 1
    private val shortMessage = 1 or 0x40
    private val emailSubject = 1 or 0x30
    private val password = 1 or 0x80
    private val pin = 2 or 0x10
    private val uri = 1 or 0x10

    @Test fun detectsModesFromAppAndField() {
        assertEquals(FormatMode.CHAT, ModeDetector.detect("com.whatsapp", text))
        assertEquals(FormatMode.EMAIL, ModeDetector.detect("com.google.android.gm", text))
        assertEquals(FormatMode.SUBJECT, ModeDetector.detect("com.google.android.gm", emailSubject))
        assertEquals(FormatMode.CHAT, ModeDetector.detect("unknown.app", shortMessage))
        assertEquals(FormatMode.STANDARD, ModeDetector.detect("unknown.app", text))
        assertEquals(FormatMode.RAW, ModeDetector.detect("com.android.chrome", uri))
        assertEquals(FormatMode.NOTE, ModeDetector.detect("com.whatsapp", text, profile = FormatMode.NOTE))
    }

    @Test fun secureFields() {
        assertTrue(ModeDetector.isSecure(password))
        assertTrue(ModeDetector.isSecure(pin))
        assertFalse(ModeDetector.isSecure(shortMessage))
        assertEquals(FormatMode.RAW, ModeDetector.detect("com.whatsapp", password))
    }

    private fun tone(seconds: Double) = FloatArray((seconds * 16000).toInt()) { (0.3 * sin(it * 0.1)).toFloat() }
    private fun silence(seconds: Double) = FloatArray((seconds * 16000).toInt())

    @Test fun findsPauseAfterEnoughSpeech() {
        val audio = tone(2.5) + silence(0.6) + tone(1.0)
        val cut = Pauses.lastPause(audio, minSpeechSamples = 2 * 16000)
        assertTrue("Schnitt in der Pause erwartet, war $cut", cut in 40000..(2.5 * 16000 + 0.6 * 16000).toInt())
    }

    @Test fun noPauseInContinuousSpeechOrTooEarly() {
        assertEquals(-1, Pauses.lastPause(tone(5.0), minSpeechSamples = 32000))
        assertEquals(-1, Pauses.lastPause(tone(1.0) + silence(0.6) + tone(1.0), minSpeechSamples = 32000))
    }
}
