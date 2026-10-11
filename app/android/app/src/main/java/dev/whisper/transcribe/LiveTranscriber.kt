package dev.whisper.transcribe

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/// Live-Transkription während der Aufnahme.
///
/// Erkennt abschnittsweise: Sobald nach mindestens zwei Sekunden Sprache eine
/// Pause kommt, wird der Abschnitt bis dorthin endgültig transkribiert
/// („festgeschrieben“). Der noch offene Rest erscheint als Vorschau. Nach dem
/// Stopp fehlt nur noch das letzte Stück — das Ergebnis steht fast sofort da.
/// Geschnitten wird nur in Pausen, mitten im Wort nie.
class LiveTranscriber(
    private val recorder: SampleSource,
    private val language: String,
    private val translate: Boolean,
    private val scope: CoroutineScope,
    private val onUpdate: (String) -> Unit,
) {
    private var committedSamples = 0
    private val committed = StringBuilder()
    @Volatile private var preview = ""
    private var job: Job? = null

    fun start() {
        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(1200)
                step()
            }
        }
    }

    private fun step() {
        val total = recorder.sampleCount
        val pending = total - committedSamples
        if (pending < SR) return
        val audio = recorder.snapshot(committedSamples, total)
        val cut = Pauses.lastPause(audio, minSpeechSamples = 2 * SR)
        if (cut > 0 || pending > 25 * SR) {
            val end = if (cut > 0) cut else audio.size
            val text = WhisperBridge.transcribe(audio.copyOfRange(0, end), language, translate) ?: return
            if (text.isNotBlank()) committed.append(text.trim()).append(' ')
            committedSamples += end
            preview = ""
        } else if (pending <= 12 * SR) {
            preview = WhisperBridge.transcribe(audio, language, translate)?.trim() ?: preview
        }
        onUpdate((committed.toString() + preview).trim())
    }

    /// Bricht die Vorschau ab, transkribiert den Rest von [all] und liefert den
    /// ganzen Text; null, wenn die Engine scheitert oder abgebrochen wurde.
    suspend fun finish(all: FloatArray): String? {
        job?.cancelAndJoin()
        val from = committedSamples.coerceAtMost(all.size)
        val rest = all.copyOfRange(from, all.size)
        val restText = if (rest.size > SR / 4) WhisperBridge.transcribe(rest, language, translate) ?: return null else ""
        return (committed.toString() + restText).trim()
    }

    /// Verwerfen (Abbrechen).
    fun stop() { job?.cancel() }

    private companion object { const val SR = AudioRecorder.SAMPLE_RATE }
}

/// Woher die Live-Transkription ihre Samples liest: Speicher (Diktat) oder
/// Datei (Meeting).
interface SampleSource {
    val sampleCount: Int
    fun snapshot(from: Int = 0, to: Int = -1): FloatArray
}

/// Pausen im Audio finden — reine Logik, ohne Android.
object Pauses {

    private const val FRAME = 480 // 30 ms bei 16 kHz

    /// Mitte der letzten Pause (≥ [minPauseFrames] leise Rahmen), vor der
    /// mindestens [minSpeechSamples] Audio liegen; -1, wenn es keine gibt.
    /// „Leise“ misst sich am lautesten Rahmen des Ausschnitts — so passt die
    /// Schwelle zu leisen wie lauten Mikrofonen.
    fun lastPause(audio: FloatArray, minSpeechSamples: Int, minPauseFrames: Int = 14): Int {
        val n = audio.size / FRAME
        if (n < minPauseFrames + 2) return -1
        val rms = FloatArray(n) { f ->
            var sum = 0.0
            for (i in f * FRAME until (f + 1) * FRAME) sum += audio[i] * audio[i]
            sqrt(sum / FRAME).toFloat()
        }
        val peak = rms.maxOrNull() ?: return -1
        if (peak < 0.005f) return -1 // nur Stille
        val quiet = maxOf(0.004f, peak * 0.08f)
        var run = 0
        for (f in n - 1 downTo 0) {
            if (rms[f] < quiet) run++ else {
                if (run >= minPauseFrames) {
                    val mid = (f + 1 + run / 2) * FRAME
                    if (mid >= minSpeechSamples) return mid
                }
                run = 0
            }
        }
        return -1
    }
}
