package dev.whisper.transcribe

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/// Verlauf der bisherigen Transkriptionen.
@Composable
fun HistoryOverlay(
    entries: List<HistoryEntry>,
    onClose: () -> Unit,
    onCopy: (String) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
    onClearAll: () -> Unit,
    onExport: (List<HistoryEntry>) -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val shown = remember(entries, query) {
        if (query.isBlank()) entries
        else entries.filter { it.text.contains(query.trim(), ignoreCase = true) }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
            title = { Text("Ganzen Verlauf löschen?") },
            text = { Text("Alle ${entries.size} Einträge werden entfernt. Das lässt sich nicht rückgängig machen.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClearAll() }) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Abbrechen") } },
        )
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        // Auf Tablets nicht über die volle Breite — lesbar zentriert
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 720.dp)
        ) {
            OverlayHeader(
                title = "Verlauf",
                subtitle = when {
                    entries.isEmpty() -> null
                    query.isBlank() -> "${entries.size} Einträge"
                    else -> "${shown.size} von ${entries.size} Einträgen"
                },
                onClose = onClose,
            ) {
                if (entries.isNotEmpty()) {
                    // Exportiert, was gerade sichtbar ist — mit Suche also nur die Treffer
                    IconButton(onClick = { onExport(shown) }, enabled = shown.isNotEmpty()) {
                        Icon(Icons.Filled.IosShare, contentDescription = "Als Textdatei teilen")
                    }
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Alle löschen")
                    }
                }
            }
            if (entries.isNotEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Im Verlauf suchen") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Suche leeren")
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
            if (entries.isNotEmpty() && shown.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.SearchOff,
                    title = "Nichts gefunden",
                    hint = "Kein Eintrag enthält „${query.trim()}“.",
                    modifier = Modifier.padding(top = 24.dp),
                )
            } else if (entries.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.History,
                    title = "Noch keine Einträge",
                    hint = "Jede Transkription landet automatisch hier.",
                    modifier = Modifier.padding(top = 48.dp),
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(shown) { e ->
                        HistoryCard(e, onCopy = { onCopy(e.text) }, onDelete = { onDelete(e) })
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(e: HistoryEntry, onCopy: () -> Unit, onDelete: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.dateText(), style = MaterialTheme.typography.labelLarge)
                    Text(
                        listOfNotNull(
                            modelDisplayName(e.model.takeIf { it.isNotBlank() }, null).takeIf { e.model.isNotBlank() },
                            e.language.takeIf { it.isNotBlank() },
                            formatDuration(e.audioSeconds).takeIf { e.audioSeconds > 0 },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, contentDescription = "Kopieren") }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Eintrag löschen", tint = MaterialTheme.colorScheme.error)
                }
            }
            // heightIn MUSS vor verticalScroll stehen: LazyColumn misst seine
            // Kinder mit unbegrenzter Höhe, und ein vertikal scrollbares Element
            // unter Infinity-Constraints wirft in Compose eine
            // IllegalStateException — die App ging beim Öffnen des Verlaufs aus.
            Text(
                e.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(end = 12.dp, top = 4.dp)
                    .heightIn(max = 160.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

/// Vollbild-Ansicht zur Modellwahl: Server-Manifest mit Vor-/Nachteilen,
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
    dismissEnabled: Boolean,
    onDismiss: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        // Auf Tablets nicht über die volle Breite — lesbar zentriert
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 720.dp)
        ) {
            OverlayHeader(
                title = "Modell wählen",
                subtitle = "Einmaliger Download von scheisssewasser.xyz — danach komplett offline",
                onClose = if (dismissEnabled) onDismiss else null,
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                downloadError?.let {
                    StatusCard("Downloadfehler: $it", isError = true, onDismiss = onRetry)
                }
                when {
                    manifestError != null -> {
                        StatusCard("Server nicht erreichbar: $manifestError", isError = true, onDismiss = onRetry)
                        FilledTonalButton(onClick = onRetry) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Erneut versuchen")
                        }
                    }
                    manifest == null -> Row(
                        Modifier.padding(vertical = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Frage Server nach verfügbaren Modellen…")
                    }
                    else -> manifest.forEach { info ->
                        ModelCard(
                            info = info,
                            isLocal = localFiles.contains(info.file),
                            isCurrent = currentFile == info.file,
                            isDownloading = downloadingFile == info.file,
                            anyDownloading = downloadingFile != null,
                            downloadProgress = downloadProgress,
                            onDownload = { onDownload(info) },
                            onActivate = { onActivate(info.file) },
                            onDelete = { onDelete(info.file) },
                        )
                    }
                }

                // Modelle, die lokal liegen, aber nicht (mehr) im Manifest stehen
                val extra = localFiles.filter { f -> manifest?.none { it.file == f } ?: false }
                if (extra.isNotEmpty()) {
                    Text(
                        "Weitere lokale Modelle",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                extra.forEach { f ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(f, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            if (f == currentFile) ActiveBadge()
                            else {
                                TextButton(onClick = { onActivate(f) }) { Text("Aktivieren") }
                                IconButton(onClick = { onDelete(f) }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "Löschen", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveBadge() {
    AssistChip(
        onClick = {},
        label = { Text("Aktiv") },
        leadingIcon = { Icon(Icons.Filled.CheckCircle, contentDescription = null, Modifier.size(18.dp)) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
            leadingIconContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        border = null,
    )
}

@Composable
private fun ModelCard(
    info: ModelInfo,
    isLocal: Boolean,
    isCurrent: Boolean,
    isDownloading: Boolean,
    anyDownloading: Boolean,
    downloadProgress: Float,
    onDownload: () -> Unit,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
) {
    val border by animateColorAsState(
        if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        label = "modelBorder",
    )
    OutlinedCard(
        Modifier.fillMaxWidth(),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(border)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(info.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    info.sizeText(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(info.tagline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            info.pros.forEach { ProsConsLine(Icons.Filled.Check, it, MaterialTheme.colorScheme.tertiary) }
            info.cons.forEach { ProsConsLine(Icons.Filled.Remove, it, MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(12.dp))
            when {
                isDownloading -> {
                    LinearProgressIndicator(progress = { downloadProgress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "Lade herunter … %.0f %%".format(downloadProgress * 100),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                isCurrent -> ActiveBadge()
                isLocal -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onActivate) { Text("Aktivieren") }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "bereits auf dem Gerät",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Modell löschen", tint = MaterialTheme.colorScheme.error)
                    }
                }
                else -> FilledTonalButton(onClick = onDownload, enabled = !anyDownloading) {
                    Icon(Icons.Filled.Download, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Herunterladen")
                }
            }
        }
    }
}

@Composable
private fun ProsConsLine(icon: ImageVector, text: String, tint: androidx.compose.ui.graphics.Color) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier
            .padding(top = 2.dp)
            .size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

/// Einführung beim ersten App-Start: 4 Seiten, ohne Überspringen.
private data class OnboardingPage(
    val icon: ImageVector,
    val title: String,
    val intro: String? = null,
    val bullets: List<Pair<ImageVector, String>> = emptyList(),
    val footer: String? = null,
)

private val ONBOARDING = listOf(
    OnboardingPage(
        icon = Icons.Filled.GraphicEq,
        title = "Willkommen!",
        intro = "Dies ist Scheisssewasser's Whisper — eine private Sprach-Transkriptions-App von Scheisssewasser. Sie wandelt Gesprochenes in Text um, direkt auf deinem Gerät.",
        footer = "Privat entwickelt — für den persönlichen Einsatz, ohne Firma und ohne kommerzielle Interessen.",
    ),
    OnboardingPage(
        icon = Icons.Filled.AutoAwesome,
        title = "Was kann die App?",
        bullets = listOf(
            Icons.Filled.Mic to "Sprache zu Text: Mikrofonaufnahmen in Sekunden transkribieren",
            Icons.Filled.AudioFile to "Dateien: Audio- und Videodateien in Text umwandeln",
            Icons.Filled.Share to "Teilen: Sprachnachrichten aus WhatsApp & Co. direkt an die App schicken",
            Icons.Filled.KeyboardVoice to "Diktat: eigene Tastatur schreibt direkt ins Textfeld, dazu Schnelleinstellungs-Kachel",
            Icons.Filled.History to "Verlauf: alle Transkriptionen bleiben abrufbar",
        ),
    ),
    OnboardingPage(
        icon = Icons.Filled.VerifiedUser,
        title = "Deine Vorteile",
        bullets = listOf(
            Icons.Filled.Lock to "100 % offline: Dein Audio verlässt das Gerät nie — keine Cloud, keine Datensammelei",
            Icons.Filled.MoneyOff to "Kostenlos und ohne Konto nutzbar",
            Icons.Filled.Tune to "Mehrere Modelle wählbar — maximal genau oder blitzschnell",
            Icons.Filled.SystemUpdate to "Automatische Updates direkt aus der App",
        ),
    ),
    OnboardingPage(
        icon = Icons.Filled.RocketLaunch,
        title = "Los geht's!",
        intro = "Im nächsten Schritt wählst du ein Sprachmodell. Es wird einmalig von einem Server von scheisssewasser.xyz bezogen und heruntergeladen — danach läuft alles komplett offline.",
        footer = "Tippe auf „Los geht's“, um zu beginnen.",
    ),
)

@Composable
fun OnboardingOverlay(onFinish: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    val isLast = page == ONBOARDING.lastIndex
    val p = ONBOARDING[page]

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 560.dp)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.5f))
            Box(
                Modifier
                    .size(88.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(p.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(p.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                p.intro?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                }
                p.bullets.forEach { (icon, text) ->
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                p.footer?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.weight(1f))

            // Seitenpunkte
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 20.dp)) {
                ONBOARDING.indices.forEach { i ->
                    Box(
                        Modifier
                            .size(if (i == page) 10.dp else 8.dp)
                            .background(
                                if (i == page) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                CircleShape,
                            )
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (page > 0) {
                    OutlinedButton(onClick = { page-- }, modifier = Modifier.height(52.dp)) { Text("Zurück") }
                }
                Button(
                    onClick = { if (isLast) onFinish() else page++ },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                ) {
                    Text(if (isLast) "Los geht's" else "Weiter", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/// Eigene Ersetzungsregeln verwalten (Erkennungsfehler dauerhaft korrigieren).
@Composable
fun ReplacementsOverlay(
    rules: List<Replacements.Rule>,
    onChange: (List<Replacements.Rule>) -> Unit,
    onClose: () -> Unit,
) {
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    fun add() {
        if (from.isBlank()) return
        onChange(rules.filterNot { it.from.equals(from.trim(), ignoreCase = true) } +
            Replacements.Rule(from.trim(), to.trim()))
        from = ""
        to = ""
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 720.dp)
        ) {
            OverlayHeader(
                title = "Ersetzungen",
                subtitle = "Wird ein Wort immer wieder falsch erkannt, hier einmal korrigieren",
                onClose = onClose,
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = from, onValueChange = { from = it },
                            label = { Text("Erkannt wird") },
                            placeholder = { Text("z. B. Scheißewasser") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = to, onValueChange = { to = it },
                            label = { Text("Ersetzen durch") },
                            placeholder = { Text("z. B. Scheisssewasser") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = { add() }, enabled = from.isNotBlank()) {
                            Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Regel hinzufügen")
                        }
                        Text(
                            "Gilt für ganze Wörter, Groß-/Kleinschreibung egal — in der App, der Tastatur und im Diktat.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (rules.isEmpty()) {
                    EmptyState(
                        icon = Icons.Outlined.FindReplace,
                        title = "Noch keine Regeln",
                        hint = "Typische Kandidaten: Namen, Firmen, Fachbegriffe.",
                    )
                }
                rules.forEach { r ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(r.from, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "wird zu",
                                tint = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 8.dp))
                            Text(r.to.ifEmpty { "(entfernen)" }, style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onChange(rules - r) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Regel löschen",
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}
