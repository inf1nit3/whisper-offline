package dev.whisper.transcribe

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// Zusatzmodelle für die Sprechertrennung laden
// ---------------------------------------------------------------------------

@Composable
fun MeetingModelsOverlay(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf<List<ModelInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var present by remember { mutableStateOf(MeetingModels.dir(context).list()?.toSet() ?: emptySet()) }

    LaunchedEffect(Unit) {
        catalog = try { withContext(Dispatchers.IO) { ModelRegistry.fetchCatalog().meeting } }
        catch (e: Exception) { error = e.message ?: "Server nicht erreichbar"; emptyList() }
    }

    OverlayScaffold(title = "Sprechererkennung",
        subtitle = "Zwei kleine Modelle (ca. 28 MB) — danach trennt der Meeting-Modus die Sprecher, komplett offline.",
        onClose = onClose) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { StatusCard(it, isError = true, onDismiss = { error = null }) }
            val list = catalog
            if (list == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            list?.forEach { info ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row { Text(info.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall); Text(info.sizeText()) }
                        Text(info.tagline, style = MaterialTheme.typography.bodySmall)
                        if (info.file in present) Text("✓ geladen", color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
            val missing = list?.filter { it.file !in present }.orEmpty()
            if (busy) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else if (missing.isNotEmpty()) {
                Button(onClick = {
                    busy = true
                    scope.launch {
                        try {
                            missing.forEachIndexed { i, info ->
                                withContext(Dispatchers.IO) {
                                    ModelRegistry.download(info, MeetingModels.dir(context)) { d, t ->
                                        if (t > 0) progress = (i + d.toFloat() / t) / missing.size
                                    }
                                }
                            }
                        } catch (e: Exception) { error = "Download fehlgeschlagen: ${e.message}" }
                        present = MeetingModels.dir(context).list()?.toSet() ?: emptySet()
                        busy = false
                    }
                }) { Icon(Icons.Filled.Download, null); Spacer(Modifier.width(8.dp)); Text("Alle laden") }
            } else if (list != null && list.isNotEmpty()) {
                Text("Bereit — Meetings werden jetzt nach Sprechern getrennt.", color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Meeting-Modus
// ---------------------------------------------------------------------------

@Composable
fun MeetingOverlay(modelFile: String?, language: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val state by MeetingSession.state.collectAsState()
    var openId by remember { mutableStateOf<String?>(null) }
    var showModels by remember { mutableStateOf(false) }

    // Fertig ausgewertet → gleich das Ergebnis zeigen
    LaunchedEffect(state.phase) {
        if (state.phase == MeetingSession.Phase.DONE) { openId = state.meetingId; MeetingSession.reset() }
    }

    if (showModels) { MeetingModelsOverlay(onClose = { showModels = false }); return }

    openId?.let { id ->
        MeetingResultView(id, onClose = { openId = null })
        return
    }

    OverlayScaffold(title = "Meeting", subtitle = "Lange Gespräche aufnehmen, nach Sprechern getrennt transkribieren",
        onClose = { if (!MeetingSession.isActive) onClose() else onClose() }) {
        when (state.phase) {
            MeetingSession.Phase.RECORDING, MeetingSession.Phase.PAUSED -> RecordingView(state)
            MeetingSession.Phase.PROCESSING -> ProcessingView(state)
            else -> StartView(state, onOpen = { openId = it }, onModels = { showModels = true })
        }
    }
}

@Composable
private fun StartView(state: MeetingSession.State, onOpen: (String) -> Unit, onModels: () -> Unit) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var speakers by remember { mutableIntStateOf(0) }
    var meetings by remember { mutableStateOf(MeetingStore.list(context)) }
    var confirmDelete by remember { mutableStateOf<Meeting?>(null) }
    val modelsReady = remember { MeetingModels.available(context) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) MeetingSession.start(context, title, speakers)
    }

    confirmDelete?.let { m ->
        AlertDialog(onDismissRequest = { confirmDelete = null },
            title = { Text("Meeting löschen?") },
            text = { Text("„${m.title}“ wird mit Aufnahme und Transkript gelöscht.") },
            confirmButton = { TextButton(onClick = { MeetingStore.delete(context, m.id); meetings = MeetingStore.list(context); confirmDelete = null }) {
                Text("Löschen", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Abbrechen") } })
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    OutlinedTextField(title, { title = it }, label = { Text("Titel (optional)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    var open by remember { mutableStateOf(false) }
                    Box {
                        AssistChip(onClick = { open = true }, label = { Text("Sprecher: " + if (speakers == 0) "automatisch" else "$speakers") },
                            leadingIcon = { Icon(Icons.Filled.Groups, null, Modifier.size(18.dp)) })
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            DropdownMenuItem(text = { Text("Automatisch") }, onClick = { speakers = 0; open = false })
                            (2..8).forEach { n -> DropdownMenuItem(text = { Text("$n") }, onClick = { speakers = n; open = false }) }
                        }
                    }
                    if (!modelsReady) {
                        Text("Ohne Sprechererkennung wird das Meeting ohne Sprechertrennung transkribiert.",
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onModels) { Text("Sprechererkennung laden (28 MB)") }
                    }
                    Text("Tipp: Handy mittig auf den Tisch legen. Die Aufnahme läuft auch bei ausgeschaltetem Bildschirm weiter. " +
                        "Telefonate lassen sich unter Android nicht mitschneiden.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = {
                        val perms = buildList {
                            add(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED))
                            MeetingSession.start(context, title, speakers)
                        else permission.launch(perms.toTypedArray())
                    }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Filled.FiberManualRecord, null); Spacer(Modifier.width(8.dp)); Text("Aufnahme starten")
                    }
                }
            }
        }
        if (meetings.isNotEmpty()) item { Text("Bisherige Meetings", style = MaterialTheme.typography.titleSmall) }
        items(meetings, key = { it.id }) { m ->
            OutlinedCard(Modifier.fillMaxWidth().clickable(enabled = m.state == "done") { onOpen(m.id) }) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(
                            SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date(m.startedMs)),
                            formatDuration(m.durationSec).takeIf { m.durationSec > 0 },
                            "${m.speakers.size} Sprecher".takeIf { m.diarized },
                        ).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                    if (m.state != "done" && m.id != state.meetingId) {
                        TextButton(onClick = { MeetingSession.process(context, m.id) }) { Text("Auswerten") }
                    }
                    IconButton(onClick = { confirmDelete = m }) {
                        Icon(Icons.Outlined.Delete, "Löschen", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingView(state: MeetingSession.State) {
    val context = LocalContext.current
    var confirmDiscard by remember { mutableStateOf(false) }
    if (confirmDiscard) {
        AlertDialog(onDismissRequest = { confirmDiscard = false },
            title = { Text("Meeting verwerfen?") },
            text = { Text("Die Aufnahme wird gelöscht und lässt sich nicht wiederherstellen.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; MeetingSession.discard(context) }) {
                Text("Verwerfen", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Weiter aufnehmen") } })
    }
    val paused = state.phase == MeetingSession.Phase.PAUSED
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(16.dp))
        Text(formatClock(state.seconds), style = MaterialTheme.typography.displayMedium,
            color = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        Text(if (paused) "Pausiert" else "Aufnahme läuft", style = MaterialTheme.typography.titleMedium)
        LinearProgressIndicator(progress = { state.level }, modifier = Modifier.fillMaxWidth(0.6f),
            color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = { if (paused) MeetingSession.resumeRecording(context) else MeetingSession.pause() }) {
                Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null); Spacer(Modifier.width(6.dp))
                Text(if (paused) "Fortsetzen" else "Pause")
            }
            Button(onClick = { MeetingSession.finish(context) }) {
                Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text("Beenden & auswerten")
            }
        }
        TextButton(onClick = { confirmDiscard = true }) { Text("Verwerfen", color = MaterialTheme.colorScheme.error) }
        if (state.live.isNotEmpty()) {
            ElevatedCard(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                    Text("Live", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                    Text(state.live, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ProcessingView(state: MeetingSession.State) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(32.dp))
        Text("Meeting wird ausgewertet", style = MaterialTheme.typography.titleLarge)
        val p = state.progress
        if (p == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
        Text(state.detail, style = MaterialTheme.typography.bodyMedium)
        Text("Läuft im Hintergrund weiter — du kannst die App verlassen. Sprechererkennung und Transkription laufen lokal.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { MeetingSession.cancelProcessing() }) { Text("Abbrechen") }
    }
}

// ---------------------------------------------------------------------------
// Ergebnis
// ---------------------------------------------------------------------------

@Composable
private fun MeetingResultView(id: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var meeting by remember { mutableStateOf(MeetingStore.load(context, id)) }
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<Int?>(null) }
    var notesBusy by remember { mutableStateOf(false) }
    var notesError by remember { mutableStateOf<String?>(null) }
    val m = meeting ?: run { onClose(); return }

    fun update(next: Meeting) { MeetingStore.save(context, next); meeting = next }

    renaming?.let { idx ->
        RenameSpeakerDialog(
            m = m, index = idx,
            onDismiss = { renaming = null },
            onRename = { name, remember ->
                val speakers = m.speakers.toMutableList().also { it[idx] = Speaker(name, recognized = false) }
                update(m.copy(speakers = speakers))
                if (remember) MeetingVoices.load(context, id).getOrNull(idx)?.let { VoiceProfiles.save(context, name, it) }
                renaming = null
            },
            onMerge = { into ->
                // Beiträge umhängen, Sprecher entfernen, Nummern nachrücken
                val utt = m.utterances.map { if (it.speaker == idx) it.copy(speaker = into) else it }
                    .map { if (it.speaker > idx) it.copy(speaker = it.speaker - 1) else it }
                val sp = m.speakers.filterIndexed { i, _ -> i != idx }
                val voices = MeetingVoices.load(context, id).filterIndexed { i, _ -> i != idx }
                if (voices.isNotEmpty()) MeetingVoices.save(context, id, voices)
                update(m.copy(utterances = utt, speakers = sp, diarized = sp.size > 1))
                renaming = null
            },
        )
    }

    fun generateNotes() {
        notesBusy = true; notesError = null
        scope.launch {
            val lang = TextRules.guessLanguage(m.transcriptText()).ifEmpty { "de" }
            val o = Enhancer.meetingNotes(context, m.transcriptText(), lang)
            notesBusy = false
            if (o.text != null) update(m.copy(notes = o.text)) else notesError = o.error ?: "Abgebrochen"
        }
    }

    OverlayScaffold(
        title = m.title,
        subtitle = listOfNotNull(
            SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date(m.startedMs)),
            formatDuration(m.durationSec),
        ).joinToString(" · "),
        onClose = onClose,
        actions = {
            IconButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Meeting", meetingText(m)))
            }) { Icon(Icons.Filled.ContentCopy, "Alles kopieren") }
            IconButton(onClick = { exportPdf(context, m) }) { Icon(Icons.Filled.PictureAsPdf, "Als PDF teilen") }
        },
    ) {
        if (m.diarized) {
            val talk = m.talkTime()
            val total = talk.values.sum().coerceAtLeast(1f)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                m.speakers.forEachIndexed { i, s ->
                    AssistChip(onClick = { renaming = i },
                        label = { Text("${s.name} · ${(talk[i] ?: 0f).div(total).times(100).toInt()} %") },
                        leadingIcon = { Icon(if (s.recognized) Icons.Filled.VerifiedUser else Icons.Filled.Person, null, Modifier.size(18.dp)) })
                }
            }
            Text("Antippen zum Umbenennen — Redezeit in Prozent. ✓ = an gespeicherter Stimme erkannt.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Transkript") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Notizen") })
        }
        if (tab == 0) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Transkript durchsuchen") },
                leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp))
            val shown = m.utterances.filter { query.isBlank() || it.text.contains(query.trim(), true) }
            if (shown.isEmpty()) EmptyState(Icons.Filled.SearchOff, "Keine Treffer", "Anderen Suchbegriff versuchen.")
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(shown) { u ->
                    Column {
                        Text((if (m.diarized) m.speakerName(u.speaker) + " · " else "") + formatClock(u.start.toInt()),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        SelectionContainer { Text(u.text, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                notesError?.let { StatusCard(it, isError = true, onDismiss = { notesError = null }) }
                when {
                    notesBusy -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Meeting wird ausgewertet …") }
                    m.notes.isNotBlank() -> {
                        SelectionContainer { Text(m.notes, style = MaterialTheme.typography.bodyMedium) }
                        Row {
                            TextButton(onClick = {
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText("Notizen", m.notes))
                            }) { Text("Notizen kopieren") }
                            if (LlmManager.isConfigured(context)) TextButton(onClick = { generateNotes() }) { Text("Neu erstellen") }
                        }
                    }
                    LlmManager.isConfigured(context) -> {
                        Text("Macht aus dem Transkript eine Zusammenfassung mit Entscheidungen, Aufgaben und offenen Fragen.")
                        Button(onClick = { generateNotes() }) { Icon(Icons.Filled.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("Notizen erstellen") }
                    }
                    else -> Text("Für Notizen braucht es ein Sprachmodell (Einstellungen → Sprachmodell).")
                }
            }
        }
    }
}


@Composable
private fun RenameSpeakerDialog(m: Meeting, index: Int, onDismiss: () -> Unit,
                                onRename: (String, Boolean) -> Unit, onMerge: (Int) -> Unit) {
    var name by remember { mutableStateOf(m.speakerName(index).takeUnless { it.startsWith("Sprecher ") } ?: "") }
    var rememberVoice by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sprecher benennen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(rememberVoice, { rememberVoice = it })
                    Text("Stimme merken — in künftigen Meetings automatisch zuordnen", style = MaterialTheme.typography.bodySmall)
                }
                if (m.speakers.size > 1) {
                    Text("Oder zusammenführen mit:", style = MaterialTheme.typography.labelLarge)
                    m.speakers.forEachIndexed { i, s ->
                        if (i != index) TextButton(onClick = { onMerge(i) }) { Text(s.name) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRename(name.trim(), rememberVoice) }, enabled = name.isNotBlank()) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

private fun meetingText(m: Meeting): String = buildString {
    append(m.title).append('\n')
    append(SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date(m.startedMs)))
    append(" · ").append(formatDuration(m.durationSec)).append("\n\n")
    if (m.notes.isNotBlank()) append(m.notes.trim()).append("\n\n---\n\n")
    append(m.transcriptText())
}

/// PDF mit Notizen und Transkript, über das Teilen-Menü (Android-eigene PdfDocument-API).
private fun exportPdf(context: Context, m: Meeting) {
    val doc = PdfDocument()
    val pageW = 595; val pageH = 842; val margin = 48 // A4 in Punkt
    val paint = TextPaint().apply { textSize = 10.5f; isAntiAlias = true }
    val layout = StaticLayout.Builder.obtain(meetingText(m), 0, meetingText(m).length, paint, pageW - 2 * margin)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(2f, 1f).build()
    var line = 0
    var pageNo = 1
    while (line < layout.lineCount) {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
        val canvas = page.canvas
        val top = layout.getLineTop(line)
        var last = line
        while (last < layout.lineCount && layout.getLineBottom(last) - top <= pageH - 2 * margin) last++
        canvas.save()
        canvas.translate(margin.toFloat(), (margin - top).toFloat())
        canvas.clipRect(0, top, pageW - 2 * margin, layout.getLineBottom(last - 1))
        layout.draw(canvas)
        canvas.restore()
        doc.finishPage(page)
        line = last; pageNo++
    }
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val safe = m.title.replace(Regex("""[^\p{L}\p{N} _-]"""), "").take(60).ifBlank { "Meeting" }
    val file = File(dir, "$safe.pdf")
    file.outputStream().use { doc.writeTo(it) }
    doc.close()
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(safe, uri)
    context.startActivity(Intent.createChooser(send, "Meeting teilen"))
}
