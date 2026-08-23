package dev.whisper.transcribe

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.migrate(this)
        setContent { App() }
    }
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
fun App() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    var modelState by remember { mutableStateOf("Kein Modell geladen") }
    var modelFile by remember { mutableStateOf<String?>(null) }
    var modelReady by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf("") }
    var durationS by remember { mutableStateOf(0f) }
    var language by remember { mutableStateOf(Settings.language(context)) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(listOf<HistoryEntry>()) }
    var showChangelog by remember { mutableStateOf(false) }
    var changelogText by remember { mutableStateOf("") }
    var useGpu by remember { mutableStateOf(Settings.useGpu(context)) }
    val gpuAvailable = remember { WhisperBridge.hasGpuBackend() }
    // Parakeet ist mehrsprachig und hat kein festes Encoder-Fenster — beide
    // Bedienelemente wären dort wirkungslos und werden ausgeblendet.
    var isParakeet by remember { mutableStateOf(false) }

    fun recordHistory(text: String, audioSeconds: Float) {
        HistoryStore.add(context, HistoryEntry(
            timeMs = System.currentTimeMillis(),
            text = text,
            model = modelFile ?: "",
            language = language,
            audioSeconds = audioSeconds,
        ))
    }

    // Modell-Auswahl / Download
    var pickerVisible by remember { mutableStateOf(false) }
    var manifest by remember { mutableStateOf<List<ModelInfo>?>(null) }
    var manifestError by remember { mutableStateOf<String?>(null) }
    var downloadingFile by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    val recorder = remember { AudioRecorder() }

    fun loadModel(fileName: String, displayName: String = fileName) {
        modelState = "Lade $displayName…"
        modelReady = false
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                WhisperBridge.loadModel(
                    File(ModelRegistry.modelsDir(context), fileName).absolutePath, useGpu
                )
            }
            modelReady = ok
            modelFile = fileName
            prefs.edit().putString("model_file", fileName).apply()
            isParakeet = ok && WhisperBridge.engineKind() == WhisperBridge.ENGINE_PARAKEET
            modelState = if (ok) "$displayName bereit" else "Modellfehler"
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
                loadModel(info.file, info.label)
            } catch (e: Exception) {
                downloadError = e.message ?: "Download fehlgeschlagen"
            } finally {
                downloadingFile = null
            }
        }
    }

    fun refreshManifest() {
        manifestError = null
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
        val exists = stored != null && File(ModelRegistry.modelsDir(context), stored).exists()
        if (exists) {
            loadModel(stored!!)
        } else {
            pickerVisible = true
        }
        refreshManifest()
    }

    LaunchedEffect(Unit) {
        if (Settings.onboardingDone(context)) {
            proceedAfterOnboarding()
        } else {
            showOnboarding = true
        }
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
            if (UpdateChecker.isNewer(current, release.tag)) {
                updateRelease = release
            } else if (!silent) {
                updateMessage = "Version $current ist aktuell"
            }
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
                val ok = ApkInstaller.startInstall(context, apk)
                if (!ok) {
                    updateMessage =
                        "Installation blockiert: erlaube „Unbekannte Apps\" in den Einstellungen — Button öffnet sie"
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
                        WhisperBridge.transcribe(samples, language, false)
                    }
                    transcript = text.trim()
                    if (transcript.isNotEmpty()) recordHistory(transcript, durationS)
                    statusMessage = null
                } catch (e: Exception) {
                    statusMessage = "Fehler: ${e.message}"
                } finally {
                    busy = false
                }
            }
        }
    }

    // Offline-Changelog aus den Assets (CHANGELOG.md wird beim Build synchronisiert)
    if (showChangelog) {
        AlertDialog(
            onDismissRequest = { showChangelog = false },
            title = { Text("Changelog") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    changelogText.split("\n\n").forEach { block ->
                        val isHeading = block.trimStart().startsWith("##")
                        Text(
                            block.trim(),
                            style = if (isHeading) MaterialTheme.typography.titleSmall
                            else MaterialTheme.typography.bodySmall,
                            fontWeight = if (isHeading) FontWeight.Bold else null,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showChangelog = false }) { Text("Schließen") }
            }
        )
    }

    // Update-Dialog: neue Version mit Notes + Download, oder Statusmeldung
    val shownRelease = updateRelease
    if (shownRelease != null) {
        AlertDialog(
            onDismissRequest = { if (!updateBusy) updateRelease = null },
            title = { Text("Update verfügbar — ${shownRelease.tag}") },
            text = {
                Column {
                    Text("Änderungen in ${shownRelease.tag}:",
                        style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(shownRelease.body.ifBlank { "Neue Version ${shownRelease.tag}." },
                        maxLines = 12, overflow = TextOverflow.Ellipsis)
                    if (updateBusy) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { updateProgress }, Modifier.fillMaxWidth()
                        )
                        Text("%.0f %%".format(updateProgress * 100),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    updateMessage?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                if (!updateBusy) {
                    TextButton(onClick = { startUpdateDownload(shownRelease) }) {
                        Text("Herunterladen & Installieren")
                    }
                }
            },
            dismissButton = {
                if (!updateBusy) {
                    TextButton(onClick = { updateRelease = null }) { Text("Später") }
                }
            }
        )
    } else if (updateMessage != null && updateChecked) {
        AlertDialog(
            onDismissRequest = { updateMessage = null },
            title = { Text("Updates") },
            text = { Text(updateMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { updateMessage = null }) { Text("OK") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scheisssewasser's Whisper") },
                actions = {
                    IconButton(onClick = {
                        if (changelogText.isEmpty()) {
                            scope.launch {
                                changelogText = withContext(Dispatchers.IO) {
                                    runCatching {
                                        context.assets.open("CHANGELOG.md")
                                            .bufferedReader().use { it.readText() }
                                    }.getOrDefault("Changelog nicht gefunden.")
                                }
                            }
                        }
                        showChangelog = true
                    }) {
                        Icon(Icons.Filled.MenuBook, "Changelog")
                    }
                    IconButton(onClick = { checkForUpdate(silent = false) }) {
                        Icon(Icons.Filled.SystemUpdate, "Updates")
                    }
                    IconButton(onClick = {
                        history = HistoryStore.load(context)
                        showHistory = true
                    }) {
                        Icon(Icons.Filled.History, "Verlauf")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(modelState, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))

                var langExpanded by remember { mutableStateOf(false) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        OutlinedButton(onClick = { langExpanded = true }, enabled = !isParakeet) {
                            Icon(Icons.Filled.Language, null)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (isParakeet) "Mehrsprachig"
                                else LANGUAGES.first { it.first == language }.second
                            )
                        }
                        DropdownMenu(expanded = langExpanded, onDismissRequest = { langExpanded = false }) {
                            LANGUAGES.forEach { (code, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                    language = code
                                    Settings.setLanguage(context, code)
                                    langExpanded = false
                                })
                            }
                        }
                    }
                    OutlinedButton(onClick = { pickerVisible = true }, enabled = downloadingFile == null) {
                        Icon(Icons.Filled.Tune, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Modell wechseln")
                    }
                }

                // Vergleichsschalter CPU gegen GPU — nur sichtbar, wenn die
                // Engine überhaupt mit einem GPU-Backend gebaut wurde.
                if (gpuAvailable) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("GPU (Vulkan)", style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = useGpu,
                            enabled = !busy && !recording && downloadingFile == null,
                            onCheckedChange = { on ->
                                useGpu = on
                                Settings.setUseGpu(context, on)
                                modelFile?.let { loadModel(it) }
                            }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

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
                                    WhisperBridge.transcribe(samples, language, false)
                                }
                                transcript = text.trim()
                                if (transcript.isNotEmpty()) recordHistory(transcript, durationS)
                                statusMessage = null
                                busy = false
                            }
                        }
                    },
                    enabled = modelReady && !busy && downloadingFile == null,
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
                    enabled = modelReady && !busy && !recording && downloadingFile == null
                ) {
                    Icon(Icons.Filled.Description, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Datei transkribieren")
                }

                statusMessage?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Transkript", style = MaterialTheme.typography.titleMedium)
                    if (transcript.isNotEmpty()) {
                        Row {
                            TextButton(onClick = {
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        android.content.Intent.EXTRA_TEXT,
                                        "🎙️ (transkribiert mit Scheisssewasser's Whisper)\n\n$transcript"
                                    )
                                }
                                context.startActivity(
                                    android.content.Intent.createChooser(send, "Transkript teilen")
                                )
                            }) {
                                Icon(Icons.Filled.Share, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Teilen")
                            }
                            TextButton(onClick = { clipboard.setText(AnnotatedString(transcript)) }) {
                                Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Kopieren")
                            }
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

            if (showOnboarding) {
                OnboardingOverlay(
                    onFinish = {
                        Settings.setOnboardingDone(context)
                        showOnboarding = false
                        proceedAfterOnboarding()
                    }
                )
            }

            // Löschen-Dialog für heruntergeladene Modelle
            var confirmDeleteModel by remember { mutableStateOf<String?>(null) }
            confirmDeleteModel?.let { fileName ->
                AlertDialog(
                    onDismissRequest = { confirmDeleteModel = null },
                    title = { Text("Modell löschen?") },
                    text = { Text("\"$fileName\" wird vom Gerät entfernt. Dein aktives Modell bleibt davon unberührt, falls es ein anderes ist.") },
                    confirmButton = {
                        TextButton(onClick = {
                            File(ModelRegistry.modelsDir(context), fileName).delete()
                            confirmDeleteModel = null
                        }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmDeleteModel = null }) { Text("Abbrechen") }
                    }
                )
            }

            if (pickerVisible) {
                ModelPickerOverlay(
                    manifest = manifest,
                    manifestError = manifestError,
                    currentFile = modelFile,
                    downloadingFile = downloadingFile,
                    downloadProgress = downloadProgress,
                    downloadError = downloadError,
                    localFiles = ModelRegistry.localModelFiles(context).map { it.name },
                    onRetry = { refreshManifest() },
                    onDownload = { startDownload(it) },
                    onActivate = { loadModel(it); pickerVisible = false },
                    onDelete = { confirmDeleteModel = it },
                    onDismissIfModelReady = { if (modelReady) pickerVisible = false },
                )
            }

            if (showHistory) {
                HistoryOverlay(
                    entries = history,
                    onClose = { showHistory = false },
                    onCopy = { clipboard.setText(AnnotatedString(it)) },
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
        }
    }
}

/// Verlauf der bisherigen Transkriptionen.
@Composable
fun HistoryOverlay(
    entries: List<HistoryEntry>,
    onClose: () -> Unit,
    onCopy: (String) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
    onClearAll: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Verlauf (${entries.size})", style = MaterialTheme.typography.headlineSmall)
                Row {
                    if (entries.isNotEmpty()) {
                        TextButton(onClick = onClearAll) { Text("Alle löschen") }
                    }
                    TextButton(onClick = onClose) { Text("Schließen") }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (entries.isEmpty()) {
                Text("Noch keine Einträge.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(entries) { e ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(e.dateText(), fontWeight = FontWeight.Bold)
                                    Row {
                                        TextButton(onClick = { onCopy(e.text) }) {
                                            Icon(Icons.Filled.ContentCopy, null, Modifier.size(14.dp))
                                        }
                                        TextButton(onClick = { onDelete(e) }) {
                                            Icon(Icons.Filled.Delete, null, Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                                // heightIn MUSS vor verticalScroll stehen: LazyColumn
                                // misst seine Kinder mit unbegrenzter Höhe, und ein
                                // vertikal scrollbares Element unter Infinity-Constraints
                                // wirft in Compose eine IllegalStateException — die App
                                // ging deshalb beim Öffnen des Verlaufs kommentarlos aus.
                                Text(
                                    e.text,
                                    Modifier
                                        .heightIn(max = 160.dp)
                                        .verticalScroll(rememberScrollState())
                                )
                                Text(
                                    listOfNotNull(
                                        e.model.takeIf { it.isNotBlank() },
                                        e.language.takeIf { it.isNotBlank() },
                                        "%.1f s Audio".format(e.audioSeconds).takeIf { e.audioSeconds > 0 },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/// Vollbild-Overlay zur Modellwahl: Server-Manifest mit Vor-/Nachteilen,
/// Download mit Fortschritt, Aktivieren bereits geladener Modelle.
@Composable
fun ModelPickerOverlay(
    manifest: List<ModelInfo>?,
    manifestError: String?,
    currentFile: String?,
    downloadingFile: String?,
    downloadProgress: Float,
    downloadError: String?,
    localFiles: List<String>,
    onRetry: () -> Unit,
    onDownload: (ModelInfo) -> Unit,
    onActivate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismissIfModelReady: () -> Unit,
) {
    Surface(
        Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Modell wählen", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Modelle werden einmalig von einem Server von scheisssewasser.xyz bezogen und heruntergeladen",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (currentFile != null) {
                    TextButton(onClick = onDismissIfModelReady) { Text("Schließen") }
                }
            }
            Spacer(Modifier.height(16.dp))

            when {
                manifestError != null -> {
                    Text("Server nicht erreichbar: $manifestError")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onRetry) { Text("Erneut versuchen") }
                }
                manifest == null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Frage Server nach verfügbaren Modellen…")
                    }
                }
                else -> {
                    manifest.forEach { info ->
                        val isLocal = localFiles.contains(info.file)
                        val isCurrent = currentFile == info.file
                        val isDownloading = downloadingFile == info.file
                        ElevatedCard(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(info.label, fontWeight = FontWeight.Bold)
                                    Text(info.sizeText(), style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    info.tagline,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(8.dp))
                                info.pros.forEach { Text("✓  $it", style = MaterialTheme.typography.bodySmall) }
                                info.cons.forEach {
                                    Text(
                                        "✗  $it",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.height(10.dp))

                                when {
                                    isDownloading -> {
                                        LinearProgressIndicator(
                                            progress = { downloadProgress },
                                            Modifier.fillMaxWidth()
                                        )
                                        Text(
                                            "%.0f %%".format(downloadProgress * 100),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    isCurrent -> Text(
                                        "✓ Aktiv",
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    isLocal -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { onActivate(info.file) }) {
                                            Text("Aktivieren (bereits geladen)")
                                        }
                                        OutlinedButton(onClick = { onDelete(info.file) }) {
                                            Icon(
                                                Icons.Filled.Delete, "Löschen",
                                                Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                    else -> Button(
                                        onClick = { onDownload(info) },
                                        enabled = downloadingFile == null
                                    ) {
                                        Icon(Icons.Filled.Download, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Herunterladen")
                                    }
                                }
                            }
                        }
                    }

                    // Modelle, die lokal liegen aber nicht im Manifest stehen
                    // (z. B. früher heruntergeladene, inzwischen entfernte)
                    if (localFiles.any { f -> manifest.none { it.file == f } }) {
                        Text(
                            "Weitere lokale Modelle",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                    localFiles.filter { f -> manifest.none { it.file == f } }.forEach { f ->
                        ElevatedCard(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(f)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { onActivate(f) }) { Text("Aktivieren") }
                                    OutlinedButton(onClick = { onDelete(f) }) {
                                        Icon(
                                            Icons.Filled.Delete, "Löschen",
                                            Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }

                    downloadError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Downloadfehler: $it",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

/// Einführung beim ersten App-Start: 4 Seiten, ohne Überspringen.
private data class OnboardingPage(
    val title: String,
    val intro: String? = null,
    val bullets: List<String> = emptyList(),
    val footer: String? = null,
)

@Composable
fun OnboardingOverlay(onFinish: () -> Unit) {
    val pages = listOf(
        OnboardingPage(
            title = "Willkommen!",
            intro = "Dies ist Scheisssewasser's Whisper — eine private Sprach-Transkriptions-App von Scheisssewasser. Sie wandelt Gesprochenes in Text um, direkt auf deinem Gerät.",
            footer = "Privat entwickelt — für den persönlichen Einsatz, ohne Firma und ohne kommerzielle Interessen.",
        ),
        OnboardingPage(
            title = "Was kann die App?",
            bullets = listOf(
                "🎙  Sprache zu Text: Mikrofonaufnahmen in Sekunden transkribieren",
                "📄  Dateien: Audio- und Videodateien in Text umwandeln",
                "⌨️  Diktat-Modus: unter Windows per Hotkey in jedes Textfeld, auf Android über die Diktat-Kachel",
                "🕘  Verlauf: alle Transkriptionen bleiben abrufbar",
                "🌐  Sprachen: Deutsch, English oder automatisch",
            ),
        ),
        OnboardingPage(
            title = "Deine Vorteile",
            bullets = listOf(
                "🔒  100 % offline: Dein Audio verlässt das Gerät nie — keine Cloud, keine Datensammelei",
                "💸  Kostenlos und ohne Konto nutzbar",
                "⚙️  Mehrere Modelle wählbar — maximal genau oder blitzschnell",
                "🔄  Automatische Updates direkt aus der App",
            ),
        ),
        OnboardingPage(
            title = "Los geht's!",
            intro = "Im nächsten Schritt wählst du ein Sprachmodell. Es wird einmalig von einem Server von scheisssewasser.xyz bezogen und heruntergeladen — danach läuft alles komplett offline.",
            footer = "Tippe auf „Weiter“, um zu beginnen.",
        ),
    )
    var page by remember { mutableStateOf(0) }
    val isLast = page == pages.lastIndex

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.6f))
            Text(
                "Scheisssewasser's Whisper",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Seite ${page + 1} von ${pages.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.weight(0.4f))

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text(pages[page].title, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(10.dp))
                    pages[page].intro?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                    }
                    pages[page].bullets.forEach {
                        Text("•  $it", style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 3.dp))
                    }
                    pages[page].footer?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = { if (isLast) onFinish() else page++ },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (isLast) "Los geht's" else "Weiter", fontSize = MaterialTheme.typography.titleMedium.fontSize)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
