package dev.whisper.transcribe

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.*
import java.io.File

/// Diktat-Tastatur: Mikrofon antippen, sprechen, nochmal antippen — der Text
/// landet direkt im gerade aktiven Textfeld, ohne Zwischenablage.
///
/// Nimmt nie von selbst auf: Die Tastatur bleibt ausgewählt, bis man zurück-
/// wechselt, und würde sonst bei jedem Textfeld das Mikrofon öffnen.
class WhisperKeyboardService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner {

    // Compose braucht Lifecycle und SavedState am Fenster; eine Tastatur ist
    // aber keine Aktivität und bringt beides nicht mit.
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recorder = AudioRecorder()

    private var phase by mutableStateOf(KeyboardPhase.IDLE)
    private var message by mutableStateOf<String?>(null)
    private var needsApp by mutableStateOf(false)
    private var seconds by mutableIntStateOf(0)
    private var level by mutableFloatStateOf(0f)
    private var progress by mutableStateOf<Float?>(null)
    private var enterIsSend by mutableStateOf(false)

    private var modelJob: Deferred<Boolean>? = null
    private var tickerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onCreateInputView(): View {
        window.window?.decorView?.let {
            it.setViewTreeLifecycleOwner(this)
            it.setViewTreeSavedStateRegistryOwner(this)
        }
        return ComposeView(this).apply {
            setContent {
                WhisperTheme {
                    KeyboardPanel(
                        phase = phase,
                        message = message,
                        needsApp = needsApp,
                        seconds = seconds,
                        level = level,
                        progress = progress,
                        enterIsSend = enterIsSend,
                        onMic = ::toggle,
                        onCancel = ::cancelTranscription,
                        onSwitch = ::switchKeyboard,
                        onSpace = { commit(" ", smartSpace = false) },
                        onBackspace = { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) },
                        onEnter = ::enter,
                        onOpenApp = ::openApp,
                    )
                }
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        enterIsSend = action == EditorInfo.IME_ACTION_SEND &&
            (info!!.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0
        if (phase == KeyboardPhase.IDLE) {
            message = null
            needsApp = false
        }
    }

    /// Tastatur verschwindet: laufende Aufnahme verwerfen — sonst bliebe das
    /// Mikrofon offen, ohne dass man es sieht.
    override fun onFinishInputView(finishingInput: Boolean) {
        if (phase == KeyboardPhase.RECORDING) {
            recorder.stop()
            phase = KeyboardPhase.IDLE
        }
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        if (recorder.isRecording) recorder.stop()
        scope.cancel()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    private fun modelPath(): String? {
        val name = getSharedPreferences("settings", Context.MODE_PRIVATE).getString("model_file", null)
            ?: return null
        return File(ModelRegistry.modelsDir(this), name).takeIf { it.exists() }?.absolutePath
    }

    private fun toggle() {
        when (phase) {
            KeyboardPhase.IDLE -> startRecording()
            KeyboardPhase.RECORDING -> stopAndTranscribe()
            KeyboardPhase.TRANSCRIBING -> {}
        }
    }

    private fun startRecording() {
        // Eine Tastatur kann keine Berechtigung erfragen — das geht nur in der App.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            message = "Mikrofon-Zugriff fehlt — einmal in der App erlauben"
            needsApp = true
            return
        }
        val path = modelPath() ?: run {
            message = "Noch kein Modell — bitte in der App eines laden"
            needsApp = true
            return
        }
        if (!recorder.start()) {
            message = "Mikrofon belegt?"
            return
        }
        message = null
        needsApp = false
        phase = KeyboardPhase.RECORDING
        // Modell parallel zur Aufnahme laden; ist es schon geladen, kostet das nichts.
        modelJob = scope.async(Dispatchers.IO) {
            WhisperBridge.load(path, Settings.useGpu(this@WhisperKeyboardService))
        }
        tickerJob = scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            while (phase == KeyboardPhase.RECORDING) {
                seconds = ((SystemClock.elapsedRealtime() - t0) / 1000).toInt()
                level = recorder.level
                delay(60)
            }
        }
    }

    private fun stopAndTranscribe() {
        val samples = recorder.stop()
        level = 0f
        if (samples.size < AudioRecorder.SAMPLE_RATE / 10) {
            phase = KeyboardPhase.IDLE
            message = "Zu kurz — nochmal versuchen"
            return
        }
        phase = KeyboardPhase.TRANSCRIBING
        progress = null
        scope.launch {
            if (modelJob?.await() == false) {
                phase = KeyboardPhase.IDLE
                message = "Modell konnte nicht geladen werden"
                needsApp = true
                return@launch
            }
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            val model = prefs.getString("model_file", null) ?: ""
            val gpu = Settings.useGpu(this@WhisperKeyboardService)
            val parakeet = WhisperBridge.engineKind() == WhisperBridge.ENGINE_PARAKEET
            val language = Settings.language(this@WhisperKeyboardService)
            val audioSeconds = samples.size / AudioRecorder.SAMPLE_RATE.toFloat()
            val expected = TranscriptionProgress.expectedMs(this@WhisperKeyboardService, model, gpu, audioSeconds, parakeet)
            val t0 = SystemClock.elapsedRealtime()
            val ticker = launch {
                while (isActive) {
                    progress = TranscriptionProgress.fraction(
                        WhisperBridge.progress(), SystemClock.elapsedRealtime() - t0, expected)
                    delay(100)
                }
            }
            val text = withContext(Dispatchers.Default) { WhisperBridge.transcribe(samples, language) }
            ticker.cancel()
            phase = KeyboardPhase.IDLE

            val trimmed = text?.trim().orEmpty()
            when {
                text == null && WhisperBridge.wasCancelled() -> message = null
                text == null -> message = "Transkription fehlgeschlagen"
                trimmed.isEmpty() -> message = "Nichts erkannt"
                else -> {
                    TranscriptionProgress.record(this@WhisperKeyboardService, model, gpu, audioSeconds, parakeet,
                        SystemClock.elapsedRealtime() - t0)
                    commit(trimmed, smartSpace = true)
                    HistoryStore.add(this@WhisperKeyboardService, HistoryEntry(
                        timeMs = System.currentTimeMillis(),
                        text = trimmed,
                        model = model,
                        language = if (parakeet) "" else language,
                        audioSeconds = audioSeconds,
                    ))
                }
            }
        }
    }

    private fun cancelTranscription() {
        if (phase == KeyboardPhase.TRANSCRIBING) WhisperBridge.cancel()
    }

    /// Schreibt ins Textfeld. Mit [smartSpace] kommt ein Leerzeichen davor,
    /// wenn direkt davor schon Text steht — Diktate reihen sich so sauber an.
    private fun commit(text: String, smartSpace: Boolean) {
        val ic = currentInputConnection ?: return
        val before = if (smartSpace) ic.getTextBeforeCursor(1, 0) else null
        val space = if (!before.isNullOrEmpty() && !before.last().isWhitespace()) " " else ""
        ic.commitText(space + text, 1)
    }

    private fun enter() {
        val ic = currentInputConnection ?: return
        if (enterIsSend) ic.performEditorAction(EditorInfo.IME_ACTION_SEND)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }

    /// Zurück zur gewohnten Tastatur; wo das nicht geht, die Systemauswahl.
    private fun switchKeyboard() {
        val switched = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && switchToPreviousInputMethod()
        if (!switched) getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private enum class KeyboardPhase { IDLE, RECORDING, TRANSCRIBING }

@Composable
private fun KeyboardPanel(
    phase: KeyboardPhase,
    message: String?,
    needsApp: Boolean,
    seconds: Int,
    level: Float,
    progress: Float?,
    enterIsSend: Boolean,
    onMic: () -> Unit,
    onCancel: () -> Unit,
    onSwitch: () -> Unit,
    onSpace: () -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val recording = phase == KeyboardPhase.RECORDING
    val transcribing = phase == KeyboardPhase.TRANSCRIBING
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Statuszeile: feste Höhe, damit die Tastatur nicht springt
            Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                when {
                    transcribing -> Row(verticalAlignment = Alignment.CenterVertically) {
                        val p = progress
                        if (p == null) LinearProgressIndicator(Modifier.weight(1f))
                        else {
                            val animated by animateFloatAsState(p, tween(300), label = "kbProgress")
                            LinearProgressIndicator(progress = { animated }, modifier = Modifier.weight(1f))
                        }
                        TextButton(onClick = onCancel) { Text("Abbrechen") }
                    }
                    recording -> Text(
                        formatClock(seconds) + "  ·  zum Einfügen nochmal tippen",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    message != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (needsApp) TextButton(onClick = onOpenApp) { Text("App öffnen") }
                    }
                    else -> Text(
                        "Tippen und sprechen — der Text landet direkt im Textfeld",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalIconButton(onClick = onSwitch, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Filled.Keyboard, contentDescription = "Andere Tastatur")
                    }
                    FilledTonalIconButton(onClick = onSpace, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Filled.SpaceBar, contentDescription = "Leerzeichen")
                    }
                }
                RecordButton(
                    recording = recording,
                    level = level,
                    enabled = !transcribing,
                    onClick = onMic,
                    size = 150.dp,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalIconButton(onClick = onBackspace, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Löschen")
                    }
                    FilledTonalIconButton(onClick = onEnter, modifier = Modifier.size(56.dp)) {
                        Icon(
                            if (enterIsSend) Icons.AutoMirrored.Filled.Send else Icons.AutoMirrored.Filled.KeyboardReturn,
                            contentDescription = if (enterIsSend) "Senden" else "Zeilenumbruch",
                        )
                    }
                }
            }
        }
    }
}
