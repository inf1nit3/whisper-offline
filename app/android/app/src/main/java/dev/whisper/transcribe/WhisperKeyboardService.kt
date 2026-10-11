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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
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
///
/// Kennt das Ziel: App und Feldtyp (EditorInfo) bestimmen den Enhance-Modus
/// (Chat, E-Mail …), Passwortfelder sind gesperrt. Gelesen wird dabei nur der
/// Feldtyp, nie der Inhalt — außer bei „Auswahl bearbeiten“ der markierte Text.
class WhisperKeyboardService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner {

    // Compose braucht Lifecycle und SavedState am Fenster; eine Tastatur ist
    // aber keine Aktivität und bringt beides nicht mit.
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recorder by lazy { AudioRecorder(applicationContext) }

    private var phase by mutableStateOf(KeyboardPhase.IDLE)
    private var message by mutableStateOf<String?>(null)
    private var needsApp by mutableStateOf(false)
    private var seconds by mutableIntStateOf(0)
    private var level by mutableFloatStateOf(0f)
    private var progress by mutableStateOf<Float?>(null)
    private var enterIsSend by mutableStateOf(false)
    private var liveText by mutableStateOf("")

    // Ziel des Diktats
    private var targetPackage: String? = null
    private var secure by mutableStateOf(false)
    private var excluded = false
    private var autoSend = false
    private var detectedMode by mutableStateOf(FormatMode.STANDARD)
    /// Für dieses Diktat gewählter Modus; null = erkannter.
    private var modeOverride by mutableStateOf<FormatMode?>(null)
    /// Enhance für dieses Diktat; null = Einstellung „automatisch verbessern“.
    private var enhanceOverride by mutableStateOf<Boolean?>(null)
    private var hasSelection by mutableStateOf(false)
    private var transformOpen by mutableStateOf(false)
    /// Aufnahme ist eine gesprochene Anweisung für „Auswahl bearbeiten“.
    private var recordingInstruction = false

    private var modelJob: Deferred<Boolean>? = null
    private var tickerJob: Job? = null
    private var live: LiveTranscriber? = null

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    private val llmReady: Boolean get() = LlmManager.isConfigured(this)
    private val effectiveMode: FormatMode get() = modeOverride ?: detectedMode
    private val enhanceOn: Boolean
        get() = llmReady && !excluded && effectiveMode != FormatMode.RAW &&
            (enhanceOverride ?: Settings.enhanceAuto(this))

    override fun onCreateInputView(): View {
        window.window?.decorView?.let {
            it.setViewTreeLifecycleOwner(this)
            it.setViewTreeSavedStateRegistryOwner(this)
        }
        return ComposeView(this).apply {
            setContent {
                WhisperTheme {
                    KeyboardPanel(
                        state = KeyboardState(
                            phase = phase, message = message, needsApp = needsApp, seconds = seconds,
                            level = level, progress = progress, enterIsSend = enterIsSend, liveText = liveText,
                            secure = secure, mode = effectiveMode, llmReady = llmReady && !excluded,
                            enhanceOn = enhanceOn, hasSelection = hasSelection, transformOpen = transformOpen,
                        ),
                        onMic = ::toggle,
                        onCancel = ::cancelWork,
                        onSwitch = ::switchKeyboard,
                        onSpace = { commit(" ", smartSpace = false) },
                        onBackspace = { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) },
                        onEnter = ::enter,
                        onOpenApp = ::openApp,
                        onMode = { modeOverride = it },
                        onToggleEnhance = { enhanceOverride = !enhanceOn },
                        onOpenTransform = { transformOpen = true },
                        onCloseTransform = { transformOpen = false },
                        onTransformAction = { a -> transformSelection(transformInstruction(this@WhisperKeyboardService, a.key, selectedText()), a.label) },
                        onTransformCustom = { transformSelection(it, "Eigene Anweisung") },
                        onDictateInstruction = ::startInstructionRecording,
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

        targetPackage = info?.packageName
        val inputType = info?.inputType ?: 0
        secure = ModeDetector.isSecure(inputType)
        val profile = AppProfiles.get(targetPackage)
        excluded = profile?.excluded == true
        autoSend = profile?.autoSend == true
        detectedMode = when {
            excluded -> FormatMode.RAW
            Settings.appContext(this) -> ModeDetector.detect(targetPackage, inputType, profile?.mode)
            else -> profile?.mode?.takeIf { it != FormatMode.AUTO } ?: FormatMode.STANDARD
        }
        hasSelection = (info?.initialSelEnd ?: 0) > (info?.initialSelStart ?: 0)
        if (!restarting) {
            modeOverride = null
            enhanceOverride = null
            transformOpen = false
        }
        if (phase == KeyboardPhase.IDLE) {
            message = if (secure) "Passwortfeld — hier wird nicht diktiert" else null
            needsApp = false
        }
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
                                   candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        hasSelection = newSelEnd > newSelStart
        if (!hasSelection && phase == KeyboardPhase.IDLE) transformOpen = false
    }

    /// Tastatur verschwindet: laufende Aufnahme verwerfen — sonst bliebe das
    /// Mikrofon offen, ohne dass man es sieht.
    override fun onFinishInputView(finishingInput: Boolean) {
        if (phase == KeyboardPhase.RECORDING) {
            live?.stop(); live = null
            recorder.stop(cue = false)
            phase = KeyboardPhase.IDLE
        }
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        live?.stop()
        if (recorder.isRecording) recorder.stop(cue = false)
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
            KeyboardPhase.IDLE -> { recordingInstruction = false; startRecording() }
            KeyboardPhase.RECORDING -> stopAndTranscribe()
            else -> {}
        }
    }

    private fun startInstructionRecording() {
        if (phase != KeyboardPhase.IDLE) return
        recordingInstruction = true
        startRecording()
    }

    private fun startRecording() {
        if (secure) {
            message = "Passwortfeld — hier wird nicht diktiert"
            Cues.error(this)
            return
        }
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
            Cues.error(this)
            return
        }
        message = null
        needsApp = false
        liveText = ""
        phase = KeyboardPhase.RECORDING
        // Modell parallel zur Aufnahme laden; ist es schon geladen, kostet das nichts.
        modelJob = scope.async(Dispatchers.IO) {
            WhisperBridge.load(this@WhisperKeyboardService, path, Settings.useGpu(this@WhisperKeyboardService))
        }
        if (Settings.liveTranscription(this) && !recordingInstruction) {
            scope.launch {
                if (modelJob?.await() == true && phase == KeyboardPhase.RECORDING) {
                    live = LiveTranscriber(recorder, Settings.language(this@WhisperKeyboardService), translate(), scope) { t ->
                        scope.launch { liveText = t }
                    }.also { it.start() }
                }
            }
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

    private fun translate(): Boolean =
        Settings.translate(this) && WhisperBridge.engineKind() != WhisperBridge.ENGINE_PARAKEET

    private fun stopAndTranscribe() {
        val samples = recorder.stop()
        level = 0f
        val l = live
        live = null
        if (samples.size < AudioRecorder.SAMPLE_RATE / 10) {
            l?.stop()
            phase = KeyboardPhase.IDLE
            message = "Zu kurz — nochmal versuchen"
            return
        }
        phase = KeyboardPhase.TRANSCRIBING
        progress = null
        scope.launch {
            if (modelJob?.await() == false) {
                l?.stop()
                phase = KeyboardPhase.IDLE
                message = "Modell konnte nicht geladen werden"
                needsApp = true
                Cues.error(this@WhisperKeyboardService)
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
            val instruction = recordingInstruction
            val text = withContext(Dispatchers.Default) {
                if (l != null) l.finish(samples)
                else WhisperBridge.transcribe(samples, language, if (instruction) false else translate())
            }
            ticker.cancel()
            liveText = ""

            val trimmed = text?.trim().orEmpty()
            when {
                text == null && WhisperBridge.wasCancelled() -> { phase = KeyboardPhase.IDLE; message = null }
                text == null -> { phase = KeyboardPhase.IDLE; message = "Transkription fehlgeschlagen"; Cues.error(this@WhisperKeyboardService) }
                trimmed.isEmpty() -> { phase = KeyboardPhase.IDLE; message = "Nichts erkannt" }
                instruction -> {
                    phase = KeyboardPhase.IDLE
                    transformSelection(trimmed, "„${trimmed.take(40)}“")
                }
                else -> {
                    if (l == null) TranscriptionProgress.record(this@WhisperKeyboardService, model, gpu, audioSeconds, parakeet,
                        SystemClock.elapsedRealtime() - t0)
                    val lang = if (parakeet) "" else TextPipeline.dictationLanguage(this@WhisperKeyboardService)
                    val enhance = enhanceOn
                    if (enhance) { phase = KeyboardPhase.ENHANCING; progress = null }
                    val result = TextPipeline.finishDictation(this@WhisperKeyboardService, trimmed, lang, effectiveMode, enhance)
                    phase = KeyboardPhase.IDLE
                    commit(result.text, smartSpace = true)
                    message = result.note
                    modeOverride = null
                    enhanceOverride = null
                    HistoryStore.add(this@WhisperKeyboardService, HistoryEntry(
                        timeMs = System.currentTimeMillis(),
                        text = result.text,
                        model = model,
                        language = lang,
                        audioSeconds = audioSeconds,
                        source = HistoryEntry.SOURCE_KEYBOARD,
                    ))
                    if (autoSend && !result.snippet) send()
                }
            }
        }
    }

    private fun cancelWork() {
        when (phase) {
            KeyboardPhase.TRANSCRIBING -> WhisperBridge.cancel()
            KeyboardPhase.ENHANCING -> Enhancer.cancel()
            else -> {}
        }
    }

    private fun selectedText(): String =
        currentInputConnection?.getSelectedText(0)?.toString().orEmpty()

    /// Markierten Text per Sprachmodell umformen und ersetzen.
    private fun transformSelection(instruction: String, label: String) {
        val original = selectedText()
        if (original.isBlank()) {
            message = "Keine lesbare Auswahl — Text im Feld markieren"
            transformOpen = false
            return
        }
        if (original.length > 6000) {
            message = "Höchstens 6000 Zeichen markieren"
            return
        }
        transformOpen = false
        phase = KeyboardPhase.ENHANCING
        progress = null
        message = null
        scope.launch {
            val lang = TextRules.guessLanguage(original).ifEmpty { TextPipeline.dictationLanguage(this@WhisperKeyboardService) }
            val o = Enhancer.transform(this@WhisperKeyboardService, original, instruction, lang)
            phase = KeyboardPhase.IDLE
            when {
                o.cancelled -> message = "Abgebrochen — Auswahl unverändert"
                o.text == null -> { message = "Auswahl unverändert: ${o.error}"; Cues.error(this@WhisperKeyboardService) }
                // Hat sich die Markierung inzwischen geändert, nicht blind ersetzen
                selectedText() != original -> {
                    (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Whisper", o.text))
                    message = "Auswahl hat sich geändert — Ergebnis liegt in der Zwischenablage"
                }
                else -> {
                    currentInputConnection?.commitText(o.text, 1)
                    message = "$label angewendet"
                    HistoryStore.add(this@WhisperKeyboardService, HistoryEntry(
                        System.currentTimeMillis(), o.text, LlmManager.modelFile(this@WhisperKeyboardService)?.name ?: "",
                        lang, 0f, HistoryEntry.SOURCE_SELECTION))
                }
            }
        }
    }

    /// Schreibt ins Textfeld. Mit [smartSpace] kommt ein Leerzeichen davor,
    /// wenn direkt davor schon Text steht — Diktate reihen sich so sauber an.
    private fun commit(text: String, smartSpace: Boolean) {
        val ic = currentInputConnection ?: return
        val before = if (smartSpace) ic.getTextBeforeCursor(1, 0) else null
        val space = if (!before.isNullOrEmpty() && !before.last().isWhitespace() && !text.startsWith("\n")) " " else ""
        ic.commitText(space + text, 1)
    }

    private fun enter() {
        val ic = currentInputConnection ?: return
        if (enterIsSend) ic.performEditorAction(EditorInfo.IME_ACTION_SEND)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }

    /// „Nach dem Diktat senden“ (App-Profil): Senden-Aktion des Felds, sonst Enter.
    private fun send() {
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

private enum class KeyboardPhase { IDLE, RECORDING, TRANSCRIBING, ENHANCING }

private data class KeyboardState(
    val phase: KeyboardPhase,
    val message: String?,
    val needsApp: Boolean,
    val seconds: Int,
    val level: Float,
    val progress: Float?,
    val enterIsSend: Boolean,
    val liveText: String,
    val secure: Boolean,
    val mode: FormatMode,
    val llmReady: Boolean,
    val enhanceOn: Boolean,
    val hasSelection: Boolean,
    val transformOpen: Boolean,
)

@Composable
private fun KeyboardPanel(
    state: KeyboardState,
    onMic: () -> Unit,
    onCancel: () -> Unit,
    onSwitch: () -> Unit,
    onSpace: () -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onOpenApp: () -> Unit,
    onMode: (FormatMode) -> Unit,
    onToggleEnhance: () -> Unit,
    onOpenTransform: () -> Unit,
    onCloseTransform: () -> Unit,
    onTransformAction: (TransformAction) -> Unit,
    onTransformCustom: (String) -> Unit,
    onDictateInstruction: () -> Unit,
) {
    val recording = state.phase == KeyboardPhase.RECORDING
    val working = state.phase == KeyboardPhase.TRANSCRIBING || state.phase == KeyboardPhase.ENHANCING
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Kontextzeile: Modus, Enhance, Auswahl bearbeiten
            if (!state.secure && state.phase == KeyboardPhase.IDLE && !state.transformOpen) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.llmReady) {
                        var open by remember { mutableStateOf(false) }
                        Box {
                            AssistChip(onClick = { open = true }, label = { Text(state.mode.label) },
                                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp)) })
                            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                FormatMode.selectable.filter { it != FormatMode.AUTO }.forEach { m ->
                                    DropdownMenuItem(text = { Text(m.label) }, onClick = { onMode(m); open = false })
                                }
                            }
                        }
                        FilterChip(selected = state.enhanceOn, onClick = onToggleEnhance,
                            label = { Text("Enhance") }, leadingIcon = { Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp)) })
                        if (state.hasSelection) AssistChip(onClick = onOpenTransform, label = { Text("Auswahl bearbeiten") },
                            leadingIcon = { Icon(Icons.Filled.EditNote, null, Modifier.size(18.dp)) })
                    }
                }
            }

            if (state.transformOpen) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Auswahl bearbeiten", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = onCloseTransform) { Icon(Icons.Filled.Close, contentDescription = "Schließen") }
                }
                TransformPanel(
                    onAction = onTransformAction,
                    onCustom = onTransformCustom,
                    extra = {
                        IconButton(onClick = onDictateInstruction) { Icon(Icons.Filled.Mic, contentDescription = "Anweisung diktieren") }
                    },
                )
                return@Column
            }

            // Statuszeile: feste Höhe, damit die Tastatur nicht springt
            Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                when {
                    working -> Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.phase == KeyboardPhase.ENHANCING) {
                            Text("Verbessere … ", style = MaterialTheme.typography.labelLarge)
                        }
                        val p = state.progress
                        if (p == null) LinearProgressIndicator(Modifier.weight(1f))
                        else {
                            val animated by animateFloatAsState(p, tween(300), label = "kbProgress")
                            LinearProgressIndicator(progress = { animated }, modifier = Modifier.weight(1f))
                        }
                        TextButton(onClick = onCancel) { Text("Abbrechen") }
                    }
                    recording && state.liveText.isNotEmpty() -> Text(
                        state.liveText.takeLast(120),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    recording -> Text(
                        formatClock(state.seconds) + "  ·  zum Einfügen nochmal tippen",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    state.message != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.secure) Icon(Icons.Filled.Lock, null, Modifier.size(16.dp).padding(end = 4.dp))
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (state.needsApp) TextButton(onClick = onOpenApp) { Text("App öffnen") }
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
                    level = state.level,
                    enabled = !working && !state.secure,
                    onClick = onMic,
                    size = 150.dp,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalIconButton(onClick = onBackspace, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Löschen")
                    }
                    FilledTonalIconButton(onClick = onEnter, modifier = Modifier.size(56.dp)) {
                        Icon(
                            if (state.enterIsSend) Icons.AutoMirrored.Filled.Send else Icons.AutoMirrored.Filled.KeyboardReturn,
                            contentDescription = if (state.enterIsSend) "Senden" else "Zeilenumbruch",
                        )
                    }
                }
            }
        }
    }
}
