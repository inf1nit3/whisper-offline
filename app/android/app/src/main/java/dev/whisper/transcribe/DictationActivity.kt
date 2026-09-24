package dev.whisper.transcribe

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/// Overlay-Diktierfenster: nimmt sofort auf, transkribiert nach Stopp und
/// legt den Text in der Zwischenablage ab — im Chat dann einfügen.
class DictationActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Settings.migrate(this)
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val fileName = prefs.getString("model_file", null)
        val modelFile = fileName?.let { File(ModelRegistry.modelsDir(this), it) }
        if (modelFile == null || !modelFile.exists()) {
            Toast.makeText(
                this, "Bitte zuerst einmal die Haupt-App öffnen und ein Modell laden", Toast.LENGTH_LONG
            ).show()
            startActivity(android.content.Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContent { WhisperTheme { DictationUi(modelFile!!.absolutePath) } }
    }
}

private enum class DictationState { PREPARING, RECORDING, TRANSCRIBING, DONE, ERROR }

@Composable
fun DictationUi(modelPath: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(DictationState.PREPARING) }
    var message by remember { mutableStateOf("Lade Modell…") }
    var resultText by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Float?>(null) }
    val recorder = remember { AudioRecorder() }
    fun close() = (context as? ComponentActivity)?.finish()

    // Verlässt der Nutzer den Dialog (Home-Taste, anderer Aufruf), bleibt das
    // Mikrofon sonst im Hintergrund offen.
    DisposableEffect(recorder) { onDispose { if (recorder.isRecording) recorder.stop() } }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionAsked by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) {
            state = DictationState.ERROR
            message = "Ohne Mikrofon-Berechtigung ist kein Diktat möglich"
        }
    }

    // Das Modell wird parallel zur Aufnahme geladen. Vorher lief das Laden
    // davor — bei kaltem Prozess sprach man also mehrere Sekunden ins Leere,
    // bevor das Mikrofon überhaupt lief. Für den Aufruf per Taste oder Kachel
    // ist das der entscheidende Unterschied.
    var modelJob by remember { mutableStateOf<Deferred<Boolean>?>(null) }

    fun startRecording() {
        if (!hasPermission) {
            if (!permissionAsked) {
                permissionAsked = true
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            return
        }
        if (recorder.start()) {
            state = DictationState.RECORDING
            message = "Sprich jetzt — tippe zum Beenden"
        } else {
            state = DictationState.ERROR
            message = "Mikrofon belegt?"
            return
        }
        modelJob = scope.async(Dispatchers.IO) {
            WhisperBridge.load(context, modelPath, Settings.useGpu(context))
        }
    }

    fun stopAndTranscribe() {
        val samples = recorder.stop()
        val audioSeconds = samples.size / AudioRecorder.SAMPLE_RATE.toFloat()
        state = DictationState.TRANSCRIBING
        message = "Transkribiere…"
        scope.launch {
            // Falls das Modell noch lädt: hier warten, nicht vor der Aufnahme.
            if (modelJob?.await() == false) {
                state = DictationState.ERROR
                message = "Modell konnte nicht geladen werden"
                return@launch
            }
            val model = modelPath.substringAfterLast('/')
            val gpu = Settings.useGpu(context)
            val parakeet = WhisperBridge.engineKind() == WhisperBridge.ENGINE_PARAKEET
            val expected = TranscriptionProgress.expectedMs(context, model, gpu, audioSeconds, parakeet)
            val t0 = SystemClock.elapsedRealtime()
            val ticker = launch {
                while (isActive) {
                    progress = TranscriptionProgress.fraction(
                        WhisperBridge.progress(), SystemClock.elapsedRealtime() - t0, expected)
                    delay(100)
                }
            }
            val text = withContext(Dispatchers.Default) {
                WhisperBridge.transcribe(samples, Settings.language(context))
            }
            ticker.cancel()
            val elapsedMs = SystemClock.elapsedRealtime() - t0
            val secs = elapsedMs / 1000f
            val trimmed = text?.trim().orEmpty()
            if (text != null) TranscriptionProgress.record(context, model, gpu, audioSeconds, parakeet, elapsedMs)
            if (text == null && WhisperBridge.wasCancelled()) {
                close()
                return@launch
            } else if (text == null) {
                state = DictationState.ERROR
                message = "Transkription fehlgeschlagen"
            } else if (trimmed.isEmpty()) {
                state = DictationState.ERROR
                message = "Nichts erkannt (${secs.toFixed1(1)} s)"
            } else {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Whisper", trimmed))
                resultText = trimmed
                state = DictationState.DONE
                message = "In Zwischenablage kopiert (${secs.toFixed1(1)} s) — im Chat einfügen"
                HistoryStore.add(context, HistoryEntry(
                    timeMs = System.currentTimeMillis(),
                    text = trimmed,
                    model = modelPath.substringAfterLast('/'),
                    language = Settings.language(context),
                    audioSeconds = audioSeconds,
                ))
                Toast.makeText(context, "Text kopiert — jetzt im Chat einfügen", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Startet sofort und nach erteilter Berechtigung erneut.
    LaunchedEffect(hasPermission) {
        if (state == DictationState.PREPARING) startRecording()
    }

    var recordingSeconds by remember { mutableIntStateOf(0) }
    var micLevel by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state) {
        val t0 = SystemClock.elapsedRealtime()
        while (state == DictationState.RECORDING) {
            recordingSeconds = ((SystemClock.elapsedRealtime() - t0) / 1000).toInt()
            micLevel = recorder.level
            delay(60)
        }
    }
    val animatedLevel by animateFloatAsState(micLevel, tween(100), label = "level")

    AlertDialog(
        // Zurück-Taste und Tippen daneben schließen — nur nicht mitten in
        // Aufnahme oder Transkription, dort ginge das Diktat versehentlich
        // verloren (dafür gibt es „Abbrechen“).
        onDismissRequest = {
            if (state != DictationState.RECORDING && state != DictationState.TRANSCRIBING) close()
        },
        icon = {
            Icon(
                when (state) {
                    DictationState.RECORDING -> Icons.Filled.Mic
                    DictationState.TRANSCRIBING -> Icons.Filled.GraphicEq
                    DictationState.DONE -> Icons.Filled.CheckCircle
                    DictationState.ERROR -> Icons.Filled.ErrorOutline
                    DictationState.PREPARING -> Icons.Filled.HourglassTop
                },
                contentDescription = null,
                tint = when (state) {
                    DictationState.RECORDING, DictationState.ERROR -> MaterialTheme.colorScheme.error
                    DictationState.DONE -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                },
            )
        },
        title = {
            Text(
                when (state) {
                    DictationState.RECORDING -> "Aufnahme"
                    DictationState.TRANSCRIBING -> "Transkription"
                    DictationState.DONE -> "Kopiert"
                    DictationState.ERROR -> "Fehler"
                    DictationState.PREPARING -> "Vorbereitung"
                }, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(message, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                if (state == DictationState.RECORDING) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        formatClock(recordingSeconds),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    // Pegel: zeigt, dass das Mikrofon tatsächlich etwas hört
                    LinearProgressIndicator(
                        progress = { animatedLevel },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (state == DictationState.TRANSCRIBING) {
                    Spacer(Modifier.height(12.dp))
                    val p = progress
                    if (p == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else {
                        val animated by animateFloatAsState(p, tween(300), label = "progress")
                        LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (resultText.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) {
                        Text(resultText, Modifier.padding(10.dp))
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                DictationState.RECORDING -> TextButton(onClick = { stopAndTranscribe() }) {
                    Text("Fertig")
                }
                DictationState.DONE, DictationState.ERROR ->
                    TextButton(onClick = { close() }) { Text("Schließen") }
                DictationState.TRANSCRIBING -> TextButton(onClick = {
                    WhisperBridge.cancel()
                    message = "Wird abgebrochen…"
                }) { Text("Abbrechen") }
                else -> {}
            }
        },
        dismissButton = {
            if (state == DictationState.RECORDING || state == DictationState.PREPARING) {
                TextButton(onClick = {
                    if (recorder.isRecording) recorder.stop()
                    close()
                }) { Text("Abbrechen") }
            }
        }
    )
}

private fun Float.toFixed1(digits: Int): String = "%.${digits}f".format(this)
