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

    /// Eine oder mehrere Dateien (etwa mehrere geteilte Sprachnachrichten)
    /// nacheinander transkribieren. Jede landet einzeln im Verlauf; die
    /// Oberfläche bekommt alle Texte zusammen.
    fun startFiles(context: Context, uris: List<Uri>, setup: Setup) {
        if (uris.isEmpty()) return
        val app = context.applicationContext
        begin(app, Running(filesTitle(0, uris.size, "wird gelesen"), 0f, null)) {
            if (uris.size == 1) return@begin decodeAndTranscribe(app, uris[0], setup, 0, 1)
            val texts = mutableListOf<String>()
            var failed = 0
            try {
                uris.forEachIndexed { i, uri ->
                    val o = decodeAndTranscribe(app, uri, setup, i, uris.size)
                    if (o.text.isNullOrBlank()) failed++ else texts += o.text
                }
            } catch (e: CancellationException) {
                // Bereits fertige Dateien nicht wegwerfen
                return@begin Outcome(texts.joinToString("\n\n").ifEmpty { null },
                    "Abgebrochen — ${texts.size} von ${uris.size} Dateien fertig", isError = false)
            }
            Outcome(
                texts.joinToString("\n\n").ifEmpty { null },
                if (failed > 0) "$failed von ${uris.size} Dateien ohne Ergebnis" else null,
                isError = failed == uris.size,
            )
        }
    }

    private fun filesTitle(index: Int, count: Int, what: String) =
        if (count > 1) "Datei ${index + 1} von $count $what" else "Datei $what"

    private suspend fun decodeAndTranscribe(app: Context, uri: Uri, setup: Setup, index: Int, count: Int): Outcome {
        // Nur Audio/Video ist transkribierbar. Manche Apps teilen oder zeigen
        // trotz Filter alles an.
        val mime = app.contentResolver.getType(uri) ?: ""
        if (mime.isNotEmpty() && !mime.startsWith("audio/") && !mime.startsWith("video/")) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: mime
            return Outcome(null, "„$name“ ist keine Audio-/Videodatei — PDFs, Bilder und Dokumente enthalten keine transkribierbare Sprache.", isError = true)
        }
        _running.value = Running(filesTitle(index, count, "wird gelesen"), 0f, null)
        return try {
            val samples = withContext(Dispatchers.IO) {
                AudioDecoder.decode(app, uri) { p ->
                    ensureActive()
                    _running.value = _running.value?.copy(fraction = p)
                }
            }
            if (samples.isEmpty()) Outcome(null, "Die Datei enthält keine Audiodaten", isError = true)
            else transcribe(app, samples, setup, if (count > 1) "Datei ${index + 1} von $count · " else "")
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

    /// Mikrofonaufnahme transkribieren.
    fun startSamples(context: Context, samples: FloatArray, setup: Setup) {
        val app = context.applicationContext
        begin(app, Running(titleFor(samples), null, null)) { transcribe(app, samples, setup, "") }
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
    private suspend fun transcribe(app: Context, samples: FloatArray, setup: Setup, prefix: String): Outcome {
        val seconds = samples.size / AudioRecorder.SAMPLE_RATE.toFloat()
        val expected = TranscriptionProgress.expectedMs(app, setup.model, setup.gpu, seconds, setup.parakeet)
        _running.value = Running(prefix + titleFor(samples), null, null)
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
