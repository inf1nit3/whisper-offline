package dev.whisper.transcribe

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

private val LANGUAGES = listOf("auto" to "Automatisch", "de" to "Deutsch", "en" to "English")

// Gebündelte Modelle in assets/models; Anzeige-Name → Dateiname
private val BUNDLED_MODELS = linkedMapOf(
    "Whisper small (empfohlen)" to "ggml-small-q5_1.bin",
    "Whisper base (schnell)" to "ggml-base.bin",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var modelState by remember { mutableStateOf("Modell wird geladen…") }
    var modelReady by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf("") }
    var durationS by remember { mutableStateOf(0f) }
    var elapsedS by remember { mutableStateOf(0f) }
    var language by remember { mutableStateOf("auto") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var selectedModel by remember { mutableStateOf(BUNDLED_MODELS.keys.first()) }
    var extraModels by remember { mutableStateOf(listOf<String>()) }
    var loadingModel by remember { mutableStateOf(true) }

    val recorder = remember { AudioRecorder() }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasAudioPermission = granted }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            busy = true
            statusMessage = "Dekodiere Datei…"
            scope.launch {
                try {
                    val samples = withContext(Dispatchers.IO) {
                        AudioDecoder.decode(context, uri)
                    }
                    durationS = samples.size / AudioRecorder.SAMPLE_RATE.toFloat()
                    statusMessage = "Transkribiere…"
                    val t0 = System.currentTimeMillis()
                    val text = withContext(Dispatchers.Default) {
                        WhisperBridge.transcribe(samples, language)
                    }
                    elapsedS = (System.currentTimeMillis() - t0) / 1000f
                    transcript = text.trim()
                    statusMessage = null
                } catch (e: Exception) {
                    statusMessage = "Fehler: ${e.message}"
                } finally {
                    busy = false
                }
            }
        }
    }

    // Modell laden; gebündelte Modelle werden aus den Assets entpackt,
    // zusätzlich vorhandene Dateien in filesDir/models (z. B. per adb push
    // nachgeladenes Turbo-Modell) werden im Menü mit angeboten.
    fun loadModelFile(displayName: String) {
        val fileName = BUNDLED_MODELS[displayName] ?: displayName
        loadingModel = true
        modelState = "Lade $fileName…"
        scope.launch {
            withContext(Dispatchers.IO) {
                val modelFile = File(context.filesDir, "models/$fileName")
                if (!modelFile.exists() || modelFile.length() == 0L) {
                    val bundled = BUNDLED_MODELS.containsValue(fileName)
                    if (bundled) {
                        modelFile.parentFile?.mkdirs()
                        context.assets.open("models/$fileName").use { input ->
                            modelFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
                val ok = modelFile.exists() && WhisperBridge.loadModel(modelFile.absolutePath)
                modelReady = ok
                modelState = if (ok) "$fileName bereit (offline)" else "Modellfehler"
            }
            loadingModel = false
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            File(context.filesDir, "models").listFiles()
                ?.map { it.name }
                ?.filter { it.endsWith(".bin") && !BUNDLED_MODELS.containsValue(it) }
                ?.let { extraModels = it }
        }
        loadModelFile(selectedModel)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Whisper Offline") }) }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(modelState, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))

            var langExpanded by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { langExpanded = true }) {
                        Icon(Icons.Filled.Language, null)
                        Spacer(Modifier.width(6.dp))
                        Text(LANGUAGES.first { it.first == language }.second)
                    }
                    DropdownMenu(expanded = langExpanded, onDismissRequest = { langExpanded = false }) {
                        LANGUAGES.forEach { (code, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                language = code; langExpanded = false
                            })
                        }
                    }
                }
                var modelExpanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { modelExpanded = true }, enabled = !loadingModel) {
                        Icon(Icons.Filled.Tune, null)
                        Spacer(Modifier.width(6.dp))
                        Text(selectedModel)
                    }
                    DropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                        (BUNDLED_MODELS.keys + extraModels).forEach { name ->
                            DropdownMenuItem(text = { Text(name) }, onClick = {
                                selectedModel = name; modelExpanded = false
                                loadModelFile(name)
                            })
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    if (!modelReady || busy) return@Button
                    if (!hasAudioPermission) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        return@Button
                    }
                    if (!recording) {
                        if (recorder.start()) recording = true
                        else statusMessage = "Mikrofon konnte nicht geöffnet werden"
                    } else {
                        val samples = recorder.stop()
                        recording = false
                        durationS = samples.size / AudioRecorder.SAMPLE_RATE.toFloat()
                        if (samples.isEmpty()) return@Button
                        busy = true
                        statusMessage = "Transkribiere…"
                        scope.launch {
                            val t0 = System.currentTimeMillis()
                            val text = withContext(Dispatchers.Default) {
                                WhisperBridge.transcribe(samples, language)
                            }
                            elapsedS = (System.currentTimeMillis() - t0) / 1000f
                            transcript = text.trim()
                            statusMessage = null
                            busy = false
                        }
                    }
                },
                enabled = modelReady && !busy && !loadingModel,
                colors = if (recording) ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ) else ButtonDefaults.buttonColors(),
                modifier = Modifier.size(120.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
                        null, Modifier.size(42.dp)
                    )
                    Text(if (recording) "Stop" else "Aufnehmen", textAlign = TextAlign.Center)
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = { filePicker.launch(arrayOf("audio/*", "video/*")) },
                enabled = modelReady && !busy && !recording && !loadingModel
            ) {
                Icon(Icons.Filled.Description, null)
                Spacer(Modifier.width(6.dp))
                Text("Datei transkribieren")
            }

            statusMessage?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }

            if (durationS > 0 && !recording) {
                Text(
                    "Audio: %.1f s  ·  Dauer: %.1f s".format(durationS, elapsedS),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Transkript", style = MaterialTheme.typography.titleMedium)
                if (transcript.isNotEmpty()) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(transcript)) }) {
                        Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Kopieren")
                    }
                }
            }

            Surface(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    transcript.ifEmpty { "Noch keine Transkription." },
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                )
            }
        }
    }
}
