package dev.whisper.transcribe

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/// Transkriptionen laufen hier statt in der Oberfläche. So überleben sie das
/// Verlassen oder Neuerstellen der Aktivität (Zurück-Taste, Dunkelmodus), und
/// der Vordergrunddienst kann denselben Fortschritt in der Statusleiste zeigen.
object TranscriptionJobs {

    /// Laufender Auftrag, so wie ihn Oberfläche und Benachrichtigung zeigen.
    data class Running(
        val title: String,
        /// null = unbestimmt (noch keine Schätzung möglich)
        val fraction: Float?,
        val detail: String?,
        val cancelling: Boolean = false,
    )

    /// Abgeschlossener Auftrag, bis die Oberfläche ihn abholt.
    data class Outcome(val text: String?, val message: String?, val isError: Boolean)

    /// Was für die Zeitschätzung und den Verlauf gebraucht wird.
    data class Setup(val model: String, val gpu: Boolean, val parakeet: Boolean, val language: String)

    private val _running = MutableStateFlow<Running?>(null)
    val running: StateFlow<Running?> = _running

    private val _outcome = MutableStateFlow<Outcome?>(null)
    val outcome: StateFlow<Outcome?> = _outcome

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    val isBusy: Boolean get() = _running.value != null

    fun consumeOutcome() { _outcome.value = null }

    /// Datei dekodieren und transkribieren.
    fun startFile(context: Context, uri: Uri, setup: Setup) {
        val app = context.applicationContext
        begin(app, Running("Datei wird gelesen", 0f, null)) {
            try {
                val samples = withContext(Dispatchers.IO) {
                    AudioDecoder.decode(app, uri) { p ->
                        ensureActive()
                        _running.value = _running.value?.copy(fraction = p)
                    }
                }
                if (samples.isEmpty()) Outcome(null, "Die Datei enthält keine Audiodaten", isError = true)
                else transcribe(app, samples, setup)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome(
                    null,
                    if (e.message?.contains("extractor", ignoreCase = true) == true)
                        "Datei nicht lesbar: kein unterstütztes Audio-/Videoformat (z. B. PDF, Bild oder Textdatei ausgewählt)"
                    else "Fehler: ${e.message}",
                    isError = true,
                )
            }
        }
    }

    /// Mikrofonaufnahme transkribieren.
    fun startSamples(context: Context, samples: FloatArray, setup: Setup) {
        val app = context.applicationContext
        begin(app, Running(titleFor(samples), null, null)) { transcribe(app, samples, setup) }
    }

    fun cancel() {
        val r = _running.value ?: return
        _running.value = r.copy(cancelling = true, detail = "Wird abgebrochen…")
        // Während des Dekodierens greift die Coroutine-Abbruchprüfung, während
        // der Transkription die der Engine.
        WhisperBridge.cancel()
        job?.cancel()
    }

    private fun begin(app: Context, initial: Running, work: suspend () -> Outcome) {
        if (isBusy) return
        _outcome.value = null
        _running.value = initial
        TranscriptionService.start(app)
        job = scope.launch {
            val result = try {
                work()
            } catch (e: CancellationException) {
                Outcome(null, "Abgebrochen", isError = false)
            }
            _running.value = null
            _outcome.value = result
            TranscriptionService.finished(app, result)
        }
    }

    private fun titleFor(samples: FloatArray) =
        "Transkribiere ${formatDuration(samples.size / AudioRecorder.SAMPLE_RATE.toFloat())} Audio"

    /// Der Balken kombiniert die Engine-Meldung (Whisper je 30-s-Fenster) mit
    /// einer Zeitschätzung, die pro Modell auf diesem Gerät gelernt wird.
    private suspend fun transcribe(app: Context, samples: FloatArray, setup: Setup): Outcome {
        val seconds = samples.size / AudioRecorder.SAMPLE_RATE.toFloat()
        val expected = TranscriptionProgress.expectedMs(app, setup.model, setup.gpu, seconds, setup.parakeet)
        _running.value = Running(titleFor(samples), null, null)
        val t0 = SystemClock.elapsedRealtime()

        val text = coroutineScope {
            val ticker = launch {
                while (isActive) {
                    val elapsed = SystemClock.elapsedRealtime() - t0
                    _running.value = _running.value?.let {
                        if (it.cancelling) it
                        else it.copy(
                            fraction = TranscriptionProgress.fraction(WhisperBridge.progress(), elapsed, expected),
                            detail = when {
                                expected == null -> "läuft seit ${elapsed / 1000} s — erste Messung für dieses Modell"
                                expected - elapsed > 1500 -> "noch ca. ${(expected - elapsed + 999) / 1000} s"
                                else -> "gleich fertig …"
                            },
                        )
                    }
                    delay(100)
                }
            }
            try {
                // Die native Rechnung lässt sich nicht per Coroutine abbrechen,
                // nur über WhisperBridge.cancel() — deshalb hier nicht abbrechbar
                // warten und das Ergebnis danach auswerten.
                withContext(Dispatchers.Default + kotlinx.coroutines.NonCancellable) {
                    WhisperBridge.transcribe(samples, setup.language)
                }
            } finally {
                ticker.cancel()
            }
        }
        val elapsed = SystemClock.elapsedRealtime() - t0

        if (text == null) {
            return if (WhisperBridge.wasCancelled()) Outcome(null, "Transkription abgebrochen", isError = false)
            else Outcome(null, "Transkription fehlgeschlagen — anderes Modell versuchen?", isError = true)
        }
        TranscriptionProgress.record(app, setup.model, setup.gpu, seconds, setup.parakeet, elapsed)
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Outcome("", "Keine Sprache erkannt", isError = false)
        HistoryStore.add(app, HistoryEntry(
            timeMs = System.currentTimeMillis(),
            text = trimmed,
            model = setup.model,
            language = if (setup.parakeet) "" else setup.language,
            audioSeconds = seconds,
        ))
        return Outcome(trimmed, null, isError = false)
    }
}
