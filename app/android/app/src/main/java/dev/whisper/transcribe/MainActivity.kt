package dev.whisper.transcribe

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    /// Über „Teilen“ empfangene Audio-/Videodatei, z. B. eine Sprachnachricht
    /// aus WhatsApp. Die App transkribiert sie, sobald ein Modell bereitsteht.
    private val sharedUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.migrate(this)
        // Nach einer Neuerstellung (etwa Dunkelmodus) nicht erneut transkribieren.
        if (savedInstanceState == null) sharedUri.value = incomingUri(intent)
        enableEdgeToEdge()
        setContent {
            WhisperTheme {
                App(sharedUri = sharedUri.value, onSharedConsumed = { sharedUri.value = null })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.onStart()
        TranscriptionService.clearFinished(this)
    }

    override fun onStop() {
        AppVisibility.onStop()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incomingUri(intent)?.let { sharedUri.value = it }
    }

    private fun incomingUri(intent: Intent?): Uri? =
        if (intent?.action == Intent.ACTION_SEND)
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else null
}

// "Automatisch" kostet einen kompletten zusätzlichen Encoder-Durchlauf zur
// Spracherkennung — bei über 90 % Encoder-Anteil also fast die doppelte Zeit.
private val LANGUAGES = listOf(
    "de" to "Deutsch",
    "en" to "English",
    "auto" to "Automatisch (2× so lang)",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(sharedUri: Uri? = null, onSharedConsumed: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    var modelLoading by remember { mutableStateOf(false) }
    var modelError by remember { mutableStateOf<String?>(null) }
    var modelFile by remember { mutableStateOf<String?>(null) }
    var modelReady by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    // Transkriptionen laufen in TranscriptionJobs weiter, auch wenn diese
    // Aktivität neu erstellt wird — die Oberfläche zeigt nur ihren Zustand.
    val running by TranscriptionJobs.running.collectAsState()
    val outcome by TranscriptionJobs.outcome.collectAsState()
    val busy = running != null
    val work = running?.let { r ->
        Work(r.title, r.fraction, r.detail, onCancel = if (r.cancelling) null else ({ TranscriptionJobs.cancel() }))
    }
    var transcript by rememberSaveable { mutableStateOf("") }
    var language by remember { mutableStateOf(Settings.language(context)) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(listOf<HistoryEntry>()) }
    var showChangelog by remember { mutableStateOf(false) }
    var changelogText by remember { mutableStateOf("") }
    var useGpu by remember { mutableStateOf(Settings.useGpu(context)) }
    val gpuAvailable = remember { WhisperBridge.hasGpuBackend() }
    // Parakeet ist mehrsprachig — die Sprachauswahl wäre dort wirkungslos.
    var isParakeet by remember { mutableStateOf(false) }

    fun showStatus(message: String?, isError: Boolean = false) {
        statusMessage = message
        statusIsError = isError
    }

    // Modell-Auswahl / Download
    var pickerVisible by remember { mutableStateOf(false) }
    var manifest by remember { mutableStateOf<List<ModelInfo>?>(null) }
    var manifestError by remember { mutableStateOf<String?>(null) }
    var downloadingFile by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    val recorder = remember { AudioRecorder() }
    // Wird die Aktivität verlassen oder neu erstellt, darf das Mikrofon nicht
    // im Hintergrund weiterlaufen.
    DisposableEffect(recorder) { onDispose { if (recorder.isRecording) recorder.stop() } }

    // Aufnahmezeit und Mikrofonpegel für den Aufnahmeknopf
    var recordingSeconds by remember { mutableIntStateOf(0) }
    var micLevel by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(recording) {
        recordingSeconds = 0
        micLevel = 0f
        val t0 = SystemClock.elapsedRealtime()
        while (recording) {
            recordingSeconds = ((SystemClock.elapsedRealtime() - t0) / 1000).toInt()
            micLevel = recorder.level
            delay(60)
        }
    }

    /// Lädt ein Modell. Wird nur bei Erfolg als aktives Modell gespeichert —
    /// ein Fehlschlag vergiftet die Startauswahl sonst dauerhaft.
    fun loadModel(fileName: String, isStartupLoad: Boolean = false) {
        modelLoading = true
        modelError = null
        modelReady = false
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                WhisperBridge.load(File(ModelRegistry.modelsDir(context), fileName).absolutePath, useGpu)
            }
            modelLoading = false
            modelReady = ok
            if (ok) {
                modelFile = fileName
                prefs.edit().putString("model_file", fileName).apply()
                isParakeet = WhisperBridge.engineKind() == WhisperBridge.ENGINE_PARAKEET
            } else {
                // Beim Start: tote Referenz entfernen, damit der nächste Start
                // sauber ist; manuell gewählte Fehlschläge lassen alles unverändert.
                if (isStartupLoad) prefs.edit().remove("model_file").apply()
                val detail = runCatching { WhisperBridge.lastError() }.getOrDefault("")
                modelError = "„${modelDisplayName(fileName, manifest)}“ konnte nicht geladen werden" +
                    (if (detail.isNotBlank()) " ($detail)" else "") + " — bitte anderes Modell wählen"
            }
        }
    }

    fun startDownload(info: ModelInfo) {
        downloadingFile = info.file
        downloadError = null
        downloadProgress = 0f
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ModelRegistry.downloadModel(info, context) { done, total ->
                        if (total > 0) downloadProgress = done.toFloat() / total
                    }
                }
                loadModel(info.file)
                pickerVisible = false
            } catch (e: Exception) {
                downloadError = e.message ?: "Download fehlgeschlagen"
            } finally {
                downloadingFile = null
            }
        }
    }

    fun refreshManifest() {
        manifestError = null
        downloadError = null
        manifest = null
        scope.launch {
            try {
                manifest = withContext(Dispatchers.IO) { ModelRegistry.fetchManifest() }
            } catch (e: Exception) {
                manifestError = e.message ?: "Manifest nicht erreichbar"
            }
        }
    }

    // Beim Start: Einführung beim allerersten Lauf, danach Modell laden/auswählen
    var showOnboarding by remember { mutableStateOf(false) }

    fun proceedAfterOnboarding() {
        val stored = prefs.getString("model_file", null)
        val storedFile = stored?.let { File(ModelRegistry.modelsDir(context), it) }
        when {
            storedFile != null && storedFile.exists() -> loadModel(stored!!, isStartupLoad = true)
            stored != null -> {
                // Referenz ohne Datei: einmal aufräumen und Auswahl zeigen
                prefs.edit().remove("model_file").apply()
                pickerVisible = true
            }
            else -> pickerVisible = true
        }
        refreshManifest()
    }

    LaunchedEffect(Unit) {
        if (Settings.onboardingDone(context)) proceedAfterOnboarding()
        else showOnboarding = true
    }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
        )
    }

    // ---- In-App-Update (GitHub Releases) ----
    var updateRelease by remember { mutableStateOf<UpdateChecker.Release?>(null) }
    var updateChecked by remember { mutableStateOf(false) }
    var updateBusy by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableStateOf(0f) }
    var updateMessage by remember { mutableStateOf<String?>(null) }

    fun checkForUpdate(silent: Boolean) {
        scope.launch {
            val release = withContext(Dispatchers.IO) { UpdateChecker.fetchLatest() }
            updateChecked = true
            if (release == null) {
                if (!silent) updateMessage = "GitHub nicht erreichbar"
                return@launch
            }
            val current = UpdateChecker.currentVersion(context)
            if (UpdateChecker.isNewer(current, release.tag)) updateRelease = release
            else if (!silent) updateMessage = "Version $current ist aktuell"
        }
    }

    fun startUpdateDownload(release: UpdateChecker.Release) {
        val url = release.apkUrl ?: run {
            updateMessage = "Release enthält keine APK"
            return
        }
        updateBusy = true
        updateProgress = 0f
        updateMessage = null
        scope.launch {
            try {
                val apk = withContext(Dispatchers.IO) {
                    ApkInstaller.downloadApk(context, url) { done, total ->
                        if (total > 0) updateProgress = done.toFloat() / total
                    }
                }
                if (!ApkInstaller.startInstall(context, apk)) {
                    updateMessage =
                        "Installation blockiert: erlaube „Unbekannte Apps“ in den Einstellungen — Button öffnet sie"
                    ApkInstaller.openInstallPermissionSettings(context)
                }
            } catch (e: Exception) {
                updateMessage = "Download fehlgeschlagen: ${e.message}"
            } finally {
                updateBusy = false
            }
        }
    }

    // Update-Check beim Start (still); Dialog erscheint nur bei neuerer Version
    LaunchedEffect(Unit) { checkForUpdate(silent = true) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasAudioPermission = granted }

    // Ergebnis eines Auftrags übernehmen, sobald es vorliegt — auch wenn es
    // fertig wurde, während die App im Hintergrund war.
    LaunchedEffect(outcome) {
        val o = outcome ?: return@LaunchedEffect
        o.text?.let { transcript = it }
        showStatus(o.message, o.isError)
        TranscriptionJobs.consumeOutcome()
    }

    // Benachrichtigungen (Fortschritt in der Statusleiste) einmalig erfragen,
    // beim ersten Auftrag — dann ist klar, wofür.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) return
        if (prefs.getBoolean("notifications_asked", false)) return
        prefs.edit().putBoolean("notifications_asked", true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun setup() = TranscriptionJobs.Setup(modelFile ?: "", useGpu, isParakeet, language)

    fun transcribeUri(uri: Uri) {
        // Vorab-Prüfung: nur Audio/Video ist transkribierbar. Manche
        // Dateiauswahl-Apps zeigen trotz MIME-Filter alles an.
        val mime = context.contentResolver.getType(uri) ?: ""
        if (mime.isNotEmpty() && !mime.startsWith("audio/") && !mime.startsWith("video/")) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: mime
            showStatus("„$name“ ist keine Audio-/Videodatei — PDFs, Bilder und Dokumente enthalten keine transkribierbare Sprache.", isError = true)
            return
        }
        showStatus(null)
        askForNotificationsOnce()
        TranscriptionJobs.startFile(context, uri, setup())
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) transcribeUri(uri) }

    // Geteilte Datei übernehmen, sobald ein Modell bereitsteht
    LaunchedEffect(sharedUri, modelReady, busy, recording) {
        if (sharedUri != null && modelReady && !busy && !recording) {
            onSharedConsumed()
            transcribeUri(sharedUri)
        }
    }

    fun toggleRecording() {
        if (!modelReady || busy) return
        if (!hasAudioPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        if (!recording) {
            if (recorder.start()) {
                recording = true
                showStatus(null)
            } else showStatus("Mikrofon konnte nicht geöffnet werden", isError = true)
            return
        }
        val samples = recorder.stop()
        recording = false
        if (samples.isEmpty()) return
        askForNotificationsOnce()
        TranscriptionJobs.startSamples(context, samples, setup())
    }

    fun copyTranscript(text: String) {
        clipboard.setText(AnnotatedString(text))
        // Ab Android 13 zeigt das System selbst eine Bestätigung an.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            scope.launch { snackbar.showSnackbar("In die Zwischenablage kopiert") }
        }
    }

    fun shareTranscript() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "🎙️ (transkribiert mit Scheisssewasser's Whisper)\n\n$transcript")
        }
        context.startActivity(Intent.createChooser(send, "Transkript teilen"))
    }

    // Offline-Changelog aus den Assets (CHANGELOG.md wird beim Build synchronisiert)
    if (showChangelog) {
        AlertDialog(
            onDismissRequest = { showChangelog = false },
            icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
            title = { Text("Changelog") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    changelogText.lines().forEach { line ->
                        when {
                            line.startsWith("## ") -> Text(
                                line.removePrefix("## "),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                            )
                            line.startsWith("# ") || line.isBlank() -> {}
                            else -> Text(line, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showChangelog = false }) { Text("Schließen") } },
        )
    }

    // Update-Dialog: neue Version mit Notes + Download, oder Statusmeldung
    val shownRelease = updateRelease
    if (shownRelease != null) {
        AlertDialog(
            onDismissRequest = { if (!updateBusy) updateRelease = null },
            icon = { Icon(Icons.Filled.SystemUpdate, contentDescription = null) },
            title = { Text("Update auf ${shownRelease.tag}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(shownRelease.body.ifBlank { "Neue Version ${shownRelease.tag}." },
                        style = MaterialTheme.typography.bodySmall)
                    if (updateBusy) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { updateProgress }, modifier = Modifier.fillMaxWidth())
                        Text("%.0f %%".format(updateProgress * 100), style = MaterialTheme.typography.bodySmall)
                    }
                    updateMessage?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                if (!updateBusy) Button(onClick = { startUpdateDownload(shownRelease) }) { Text("Installieren") }
            },
            dismissButton = {
                if (!updateBusy) TextButton(onClick = { updateRelease = null }) { Text("Später") }
            },
        )
    } else if (updateMessage != null && updateChecked) {
        AlertDialog(
            onDismissRequest = { updateMessage = null },
            icon = { Icon(Icons.Filled.SystemUpdate, contentDescription = null) },
            title = { Text("Updates") },
            text = { Text(updateMessage ?: "") },
            confirmButton = { TextButton(onClick = { updateMessage = null }) { Text("OK") } },
        )
    }

    // Löschen-Dialog für heruntergeladene Modelle
    var confirmDeleteModel by remember { mutableStateOf<String?>(null) }
    var localFiles by remember { mutableStateOf(ModelRegistry.localModelFiles(context).map { it.name }) }
    fun refreshLocalFiles() { localFiles = ModelRegistry.localModelFiles(context).map { it.name } }
    LaunchedEffect(pickerVisible, downloadingFile, modelFile) { refreshLocalFiles() }

    confirmDeleteModel?.let { fileName ->
        AlertDialog(
            onDismissRequest = { confirmDeleteModel = null },
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = { Text("Modell löschen?") },
            text = { Text("„${modelDisplayName(fileName, manifest)}“ wird vom Gerät entfernt und kann jederzeit neu geladen werden.") },
            confirmButton = {
                TextButton(onClick = {
                    File(ModelRegistry.modelsDir(context), fileName).delete()
                    confirmDeleteModel = null
                    refreshLocalFiles()
                }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteModel = null }) { Text("Abbrechen") } },
        )
    }

    // Zurück-Taste schließt Ansichten statt die App zu beenden
    BackHandler(enabled = showHistory || (pickerVisible && localFiles.isNotEmpty())) {
        when {
            showHistory -> showHistory = false
            pickerVisible -> pickerVisible = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Scheisssewasser's", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                            Text("Whisper", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            history = HistoryStore.load(context)
                            showHistory = true
                        }) { Icon(Icons.Filled.History, contentDescription = "Verlauf") }
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Mehr")
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Changelog") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) },
                                    onClick = {
                                        menu = false
                                        if (changelogText.isEmpty()) scope.launch {
                                            changelogText = withContext(Dispatchers.IO) {
                                                runCatching {
                                                    context.assets.open("CHANGELOG.md").bufferedReader().use { it.readText() }
                                                }.getOrDefault("Changelog nicht gefunden.")
                                            }
                                        }
                                        showChangelog = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Nach Updates suchen") },
                                    leadingIcon = { Icon(Icons.Filled.SystemUpdate, null) },
                                    onClick = { menu = false; checkForUpdate(silent = false) },
                                )
                                // Ab Android 13 kann die App die Kachel selbst anbieten —
                                // statt Schnelleinstellungen von Hand bearbeiten.
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) DropdownMenuItem(
                                    text = { Text("Diktat-Kachel hinzufügen") },
                                    leadingIcon = { Icon(Icons.Filled.AddBox, null) },
                                    onClick = {
                                        menu = false
                                        requestDictationTile(context) { msg ->
                                            scope.launch { snackbar.showSnackbar(msg) }
                                        }
                                    },
                                )
                                // Nur sichtbar, wenn die Engine mit GPU-Backend gebaut wurde.
                                if (gpuAvailable) DropdownMenuItem(
                                    text = { Text("GPU (Vulkan)") },
                                    leadingIcon = { Icon(Icons.Filled.Memory, null) },
                                    trailingIcon = { Checkbox(checked = useGpu, onCheckedChange = null) },
                                    enabled = !busy && !recording && downloadingFile == null,
                                    onClick = {
                                        menu = false
                                        useGpu = !useGpu
                                        Settings.setUseGpu(context, useGpu)
                                        modelFile?.let { loadModel(it) }
                                    },
                                )
                            }
                        }
                    },
                )
            },
        ) { padding ->
            // Ab 600 dp Breite (Tablet, Foldable, Handy quer) zweispaltig:
            // links die Bedienung, rechts das Transkript. Android 17 erzwingt
            // auf großen Bildschirmen freie Größe und Ausrichtung.
            BoxWithConstraints(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                val wide = maxWidth >= 600.dp
                // Wenig Höhe (Handy quer): kleinerer Knopf, Bedienung scrollbar
                val compact = maxHeight < 480.dp

                @Composable
                fun ColumnScope.FlexSpace(weight: Float) {
                    if (wide && compact) Spacer(Modifier.height(12.dp)) else Spacer(Modifier.weight(weight))
                }

                val controls: @Composable ColumnScope.() -> Unit = {
                        // Modell und Sprache als Chips
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AssistChip(
                                onClick = { pickerVisible = true },
                                enabled = downloadingFile == null && !recording,
                                label = {
                                    Text(
                                        if (modelLoading) "Lade Modell…" else modelDisplayName(modelFile, manifest),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                leadingIcon = {
                                    if (modelLoading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    else Icon(Icons.Filled.Tune, null, Modifier.size(18.dp))
                                },
                                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp)) },
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            var langExpanded by remember { mutableStateOf(false) }
                            Box {
                                AssistChip(
                                    onClick = { langExpanded = true },
                                    enabled = !isParakeet,
                                    label = {
                                        Text(if (isParakeet) "Mehrsprachig"
                                             else LANGUAGES.first { it.first == language }.second.substringBefore(" ("))
                                    },
                                    leadingIcon = { Icon(Icons.Filled.Translate, null, Modifier.size(18.dp)) },
                                    trailingIcon = if (isParakeet) null else {
                                        { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp)) }
                                    },
                                )
                                DropdownMenu(expanded = langExpanded, onDismissRequest = { langExpanded = false }) {
                                    LANGUAGES.forEach { (code, label) ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            trailingIcon = if (code == language) { { Icon(Icons.Filled.Check, null) } } else null,
                                            onClick = {
                                                language = code
                                                Settings.setLanguage(context, code)
                                                langExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        modelError?.let {
                            Spacer(Modifier.height(8.dp))
                            StatusCard(it, isError = true, onDismiss = { modelError = null })
                        }

                        FlexSpace(0.3f)

                        RecordButton(
                            size = if (compact) 150.dp else 200.dp,
                            recording = recording,
                            level = micLevel,
                            enabled = modelReady && !busy && downloadingFile == null,
                            onClick = { toggleRecording() },
                        )
                        Text(
                            when {
                                recording -> formatClock(recordingSeconds)
                                !modelReady && !modelLoading -> "Kein Modell geladen"
                                modelLoading -> "Modell wird geladen…"
                                else -> "Tippen zum Aufnehmen"
                            },
                            style = if (recording) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
                            color = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (recording) {
                            Text("Tippen zum Beenden", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        Spacer(Modifier.height(16.dp))
                        FilledTonalButton(
                            onClick = { filePicker.launch(arrayOf("audio/*", "video/*")) },
                            enabled = modelReady && !busy && !recording && downloadingFile == null,
                        ) {
                            Icon(Icons.Filled.AudioFile, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Audiodatei transkribieren")
                        }

                        FlexSpace(0.2f)

                        AnimatedVisibility(visible = work != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                            work?.let { WorkCard(it, Modifier.padding(vertical = 8.dp)) }
                        }
                        AnimatedVisibility(visible = work == null && statusMessage != null) {
                            statusMessage?.let {
                                StatusCard(it, statusIsError, onDismiss = { showStatus(null) }, Modifier.padding(vertical = 8.dp))
                            }
                        }
                }

                @Composable
                fun Transcript(modifier: Modifier) = TranscriptCard(
                    text = transcript,
                    onCopy = { copyTranscript(transcript) },
                    onShare = { shareTranscript() },
                    modifier = modifier,
                )

                if (wide) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                                .padding(bottom = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            controls()
                            FlexSpace(0.3f)
                        }
                        Transcript(
                            Modifier
                                .weight(1.3f)
                                .fillMaxHeight()
                                .padding(bottom = 16.dp)
                        )
                    }
                } else {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        controls()
                        Transcript(
                            Modifier
                                .weight(1f)
                                .padding(bottom = 16.dp)
                        )
                    }
                }
            }
        }

        // Vollbild-Ansichten liegen über der gesamten Oberfläche
        if (pickerVisible) {
            ModelPickerOverlay(
                manifest = manifest,
                manifestError = manifestError,
                currentFile = modelFile,
                downloadingFile = downloadingFile,
                downloadProgress = downloadProgress,
                downloadError = downloadError,
                localFiles = localFiles,
                onRetry = { refreshManifest() },
                onDownload = { startDownload(it) },
                onActivate = { loadModel(it); pickerVisible = false },
                onDelete = { confirmDeleteModel = it },
                dismissEnabled = localFiles.isNotEmpty(),
                onDismiss = { pickerVisible = false },
            )
        }

        if (showHistory) {
            HistoryOverlay(
                entries = history,
                onClose = { showHistory = false },
                onCopy = { copyTranscript(it) },
                onDelete = {
                    HistoryStore.delete(context, it)
                    history = HistoryStore.load(context)
                },
                onClearAll = {
                    HistoryStore.clear(context)
                    history = emptyList()
                },
            )
        }

        if (showOnboarding) {
            OnboardingOverlay(
                onFinish = {
                    Settings.setOnboardingDone(context)
                    showOnboarding = false
                    proceedAfterOnboarding()
                }
            )
        }
    }
}

/// Bittet das System, die Diktat-Kachel in die Schnelleinstellungen zu legen
/// (Android 13+). Das System zeigt dafür einen eigenen Bestätigungsdialog.
@androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun requestDictationTile(context: Context, onResult: (String) -> Unit) {
    val sbm = context.getSystemService(android.app.StatusBarManager::class.java) ?: return
    sbm.requestAddTileService(
        android.content.ComponentName(context, DictationTileService::class.java),
        context.getString(R.string.dictate_label),
        android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_mic),
        context.mainExecutor,
    ) { result ->
        onResult(
            when (result) {
                android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Kachel hinzugefügt — Schnelleinstellungen herunterziehen"
                android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "Die Kachel ist schon in den Schnelleinstellungen"
                android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "Kachel nicht hinzugefügt"
                else -> "Das System hat die Anfrage abgelehnt — Kachel bitte von Hand hinzufügen"
            }
        )
    }
}
