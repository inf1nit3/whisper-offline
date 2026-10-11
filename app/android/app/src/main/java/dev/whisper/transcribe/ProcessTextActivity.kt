package dev.whisper.transcribe

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/// „Umformen“ im Textauswahl-Menü jeder App (ACTION_PROCESS_TEXT): Text
/// markieren → ⋮ → „Whisper umformen“ → Aktion wählen. Ist das Feld
/// editierbar, ersetzt das Ergebnis die Auswahl direkt; sonst geht es in die
/// Zwischenablage.
class ProcessTextActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        setContent {
            WhisperTheme {
                ProcessTextUi(
                    original = text,
                    readOnly = readOnly,
                    onReplace = { result ->
                        setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, result))
                        finish()
                    },
                    onClose = { finish() },
                )
            }
        }
    }
}

private enum class PtState { CHOOSE, RECORDING, WORKING, DONE }

@Composable
private fun ProcessTextUi(original: String, readOnly: Boolean, onReplace: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(PtState.CHOOSE) }
    var result by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val recorder = remember { AudioRecorder(context.applicationContext) }
    DisposableEffect(recorder) { onDispose { if (recorder.isRecording) recorder.stop(cue = false) } }
    val configured = remember { LlmManager.isConfigured(context) }
    val lang = remember { TextRules.guessLanguage(original).ifEmpty { Settings.language(context).takeIf { it != "auto" } ?: "" } }

    fun run(instruction: String) {
        if (original.length > 6000) { error = "Höchstens 6000 Zeichen markieren"; return }
        state = PtState.WORKING
        error = null
        scope.launch {
            val o = Enhancer.transform(context, original, instruction, lang)
            when {
                o.cancelled -> state = PtState.CHOOSE
                o.text == null -> { error = o.error ?: "Sprachmodell fehlgeschlagen"; state = PtState.CHOOSE; Cues.error(context) }
                else -> {
                    result = o.text
                    state = PtState.DONE
                    HistoryStore.add(context, HistoryEntry(System.currentTimeMillis(), o.text,
                        LlmManager.modelFile(context)?.name ?: "", lang, 0f, HistoryEntry.SOURCE_SELECTION))
                }
            }
        }
    }

    /// Anweisung sprechen statt tippen: aufnehmen, transkribieren, ausführen.
    fun toggleInstruction() {
        if (state == PtState.RECORDING) {
            val samples = recorder.stop()
            state = PtState.WORKING
            scope.launch {
                val path = Settings.prefs(context).getString("model_file", null)
                    ?.let { File(ModelRegistry.modelsDir(context), it) }?.takeIf { it.exists() }
                val ok = path != null && withContext(Dispatchers.IO) { WhisperBridge.load(context, path.absolutePath, Settings.useGpu(context)) }
                val text = if (ok) withContext(Dispatchers.Default) { WhisperBridge.transcribe(samples, Settings.language(context)) } else null
                if (text.isNullOrBlank()) { error = "Anweisung nicht verstanden"; state = PtState.CHOOSE }
                else run(text.trim())
            }
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            error = "Mikrofon-Zugriff fehlt — einmal in der App erlauben"
            return
        }
        if (recorder.start()) state = PtState.RECORDING else error = "Mikrofon belegt?"
    }

    AlertDialog(
        onDismissRequest = { if (state != PtState.WORKING && state != PtState.RECORDING) onClose() },
        icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
        title = { Text(if (state == PtState.DONE) "Ergebnis" else "Text umformen") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (!configured) {
                    Text("Dafür braucht es ein Sprachmodell: in der App unter Einstellungen → Sprachmodell eines laden.")
                    return@Column
                }
                Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) {
                    Text(if (state == PtState.DONE) result else original, Modifier.padding(10.dp),
                        maxLines = if (state == PtState.DONE) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error); Spacer(Modifier.height(8.dp)) }
                when (state) {
                    PtState.CHOOSE -> TransformPanel(
                        onAction = { a -> run(transformInstruction(context, a.key, original)) },
                        onCustom = { run(it) },
                        extra = { IconButton(onClick = { toggleInstruction() }) { Icon(Icons.Filled.Mic, "Anweisung diktieren") } },
                    )
                    PtState.RECORDING -> Row {
                        Text("Sprich deine Anweisung …", Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                        IconButton(onClick = { toggleInstruction() }) { Icon(Icons.Filled.Stop, "Fertig") }
                    }
                    PtState.WORKING -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Sprachmodell arbeitet …", style = MaterialTheme.typography.bodySmall)
                    }
                    PtState.DONE -> if (readOnly) Text("Das Feld ist nicht bearbeitbar — Ergebnis kopieren und selbst einfügen.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            when {
                state == PtState.DONE && !readOnly -> TextButton(onClick = { onReplace(result) }) { Text("Ersetzen") }
                state == PtState.DONE -> TextButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Whisper", result))
                    onClose()
                }) { Text("Kopieren") }
                state == PtState.WORKING -> TextButton(onClick = { Enhancer.cancel() }) { Text("Abbrechen") }
                else -> {}
            }
        },
        dismissButton = {
            if (state == PtState.DONE && !readOnly) TextButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Whisper", result))
                onClose()
            }) { Text("Kopieren") }
            else if (state == PtState.CHOOSE) TextButton(onClick = onClose) { Text("Schließen") }
        },
    )
}
