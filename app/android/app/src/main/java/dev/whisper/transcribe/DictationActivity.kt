package dev.whisper.transcribe

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/// Overlay-Diktierfenster: nimmt sofort auf, transkribiert nach Stopp und
/// legt den Text in der Zwischenablage ab — im Chat dann einfügen.
class DictationActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        setContent { DictationUi(modelFile!!.absolutePath) }
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
    val recorder = remember { AudioRecorder() }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    fun startRecording() {
        scope.launch {
            withContext(Dispatchers.IO) {
                WhisperBridge.loadModel(modelPath)
            }
            if (!hasPermission) {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return@launch
            }
            if (recorder.start()) {
                state = DictationState.RECORDING
                message = "Sprich jetzt — tippe zum Beenden"
            } else {
                state = DictationState.ERROR
                message = "Mikrofon belegt?"
            }
        }
    }

    fun stopAndTranscribe() {
        val samples = recorder.stop()
        state = DictationState.TRANSCRIBING
        message = "Transkribiere…"
        scope.launch {
            val t0 = System.currentTimeMillis()
            val text = withContext(Dispatchers.Default) {
                WhisperBridge.transcribe(samples, "auto")
            }
            val secs = (System.currentTimeMillis() - t0) / 1000f
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                state = DictationState.ERROR
                message = "Nichts erkannt (${secs.toFixed1(1)} s)"
            } else {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Whisper", trimmed))
                resultText = trimmed
                state = DictationState.DONE
                message = "In Zwischenablage kopiert (${secs.toFixed1(1)} s) — im Chat einfügen"
                Toast.makeText(context, "Text kopiert — jetzt im Chat einfügen", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) { startRecording() }

    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(
                when (state) {
                    DictationState.RECORDING -> "🎙 Aufnahme"
                    DictationState.TRANSCRIBING -> "⏳ Transkription"
                    DictationState.DONE -> "✓ Kopiert"
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
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Laufzeit: tippe auf den Button",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
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
                    Text("⏹ Fertig")
                }
                DictationState.DONE, DictationState.ERROR ->
                    TextButton(onClick = { (context as? ComponentActivity)?.finish() }) {
                        Text("Schließen")
                    }
                else -> {}
            }
        },
        dismissButton = {
            if (state == DictationState.RECORDING) {
                TextButton(onClick = {
                    recorder.stop()
                    (context as? ComponentActivity)?.finish()
                }) { Text("Abbrechen") }
            }
        }
    )
}

private fun Float.toFixed1(digits: Int): String = "%.${digits}f".format(this)
