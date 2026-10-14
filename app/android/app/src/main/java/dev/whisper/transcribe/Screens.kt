package dev.whisper.transcribe

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

// ---------------------------------------------------------------------------
// Gemeinsame Bausteine
// ---------------------------------------------------------------------------

/// Vollbild-Ansicht mit Kopfzeile, auf Tablets lesbar zentriert.
@Composable
fun OverlayScaffold(
    title: String,
    subtitle: String? = null,
    onClose: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 720.dp)
        ) {
            OverlayHeader(title = title, subtitle = subtitle, onClose = onClose, actions = actions)
            content()
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(title: String, hint: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = hint?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
        modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) },
    )
}

@Composable
private fun NavRow(title: String, hint: String?, icon: ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = hint?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

// ---------------------------------------------------------------------------
// Einstellungen
// ---------------------------------------------------------------------------

@Composable
fun SettingsOverlay(
    onClose: () -> Unit,
    onOpenDictionary: () -> Unit,
    onOpenLlm: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenMeetingModels: () -> Unit,
    onTranslateChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var feedback by remember { mutableStateOf(Settings.audioFeedback(context)) }
    var ducking by remember { mutableStateOf(Settings.ducking(context)) }
    var commands by remember { mutableStateOf(Settings.spokenCommands(context)) }
    var live by remember { mutableStateOf(Settings.liveTranscription(context)) }
    var translate by remember { mutableStateOf(Settings.translate(context)) }
    var history by remember { mutableStateOf(Settings.historyEnabled(context)) }
    var enhanceAuto by remember { mutableStateOf(Settings.enhanceAuto(context)) }
    var appContext by remember { mutableStateOf(Settings.appContext(context)) }
    var keepLoaded by remember { mutableStateOf(Settings.llmKeepLoaded(context)) }
    var notesAuto by remember { mutableStateOf(Settings.meetingNotesAuto(context)) }
    var editPrompt by remember { mutableStateOf(false) }
    val llmFile = remember(enhanceAuto) { LlmManager.modelFile(context)?.name }

    if (editPrompt) PromptEditorDialog(onDismiss = { editPrompt = false })

    OverlayScaffold(title = "Einstellungen", onClose = onClose) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionTitle("Diktat")
            MicrophoneRow()
            SwitchRow("Töne", "Kurzer Ton bei Start, Ende und Fehlern", feedback) {
                feedback = it; Settings.setAudioFeedback(context, it)
            }
            SwitchRow("Andere Wiedergabe leiser", "Musik und Podcasts werden während der Aufnahme leiser", ducking) {
                ducking = it; Settings.setDucking(context, it)
            }
            SwitchRow("Sprachbefehle", "„Komma“, „Punkt“, „Fragezeichen“, „neue Zeile“, „neuer Absatz“ werden umgesetzt", commands) {
                commands = it; Settings.setSpokenCommands(context, it)
            }
            SwitchRow("Live-Transkription", "Text erscheint schon beim Sprechen; das Ergebnis steht nach dem Stoppen fast sofort da. Kostet während der Aufnahme Rechenzeit.", live) {
                live = it; Settings.setLiveTranscription(context, it)
            }
            SwitchRow("Ins Englische übersetzen", "Nur Whisper-Modelle — Parakeet transkribiert immer in der gesprochenen Sprache", translate) {
                translate = it; Settings.setTranslate(context, it); onTranslateChanged(it)
            }
            NavRow("Wörterbuch", "Eigene Wörter, Ersetzungen und Snippets", Icons.Filled.Spellcheck, onOpenDictionary)

            SectionTitle("Enhance — Text verbessern")
            NavRow(
                "Sprachmodell",
                if (!LlmBridge.available) "In dieser App-Version nicht enthalten"
                else llmFile?.let { "Aktiv: ${it.removeSuffix(".gguf")}" } ?: "Noch keins gewählt — läuft komplett auf dem Gerät",
                Icons.Filled.AutoAwesome, onOpenLlm,
            )
            SwitchRow("Diktate automatisch verbessern",
                "Tastatur, Diktat-Overlay und Aufnahmen in der App. Füllwörter raus, Zeichensetzung, Formatierung. Dauert ein paar Sekunden länger.",
                enhanceAuto, enabled = llmFile != null) {
                enhanceAuto = it; Settings.setEnhanceAuto(context, it)
            }
            SwitchRow("App-Kontext verwenden",
                "Die Tastatur erkennt Chat, E-Mail, Notiz … und formatiert passend. Es wird nur der Feldtyp und die App gelesen, kein Inhalt.",
                appContext) { appContext = it; Settings.setAppContext(context, it) }
            NavRow("App-Profile", "Modus pro App festlegen, Apps ausschließen, nach dem Diktat automatisch senden",
                Icons.Filled.Apps, onOpenProfiles)
            NavRow("System-Prompt", if (Settings.enhancePrompt(context) != null) "Eigene Regeln aktiv" else "Standard",
                Icons.Filled.EditNote) { editPrompt = true }
            SwitchRow("Modell im Speicher behalten",
                "Schneller bei häufiger Nutzung, belegt aber dauerhaft 1–3 GB Arbeitsspeicher", keepLoaded) {
                keepLoaded = it; Settings.setLlmKeepLoaded(context, it)
                if (!it) LlmManager.release(context)
            }

            SectionTitle("Meeting")
            SwitchRow("Notizen automatisch schreiben",
                "Nach dem Meeting: Zusammenfassung, Entscheidungen, Aufgaben, offene Fragen (braucht ein Sprachmodell)",
                notesAuto) { notesAuto = it; Settings.setMeetingNotesAuto(context, it) }
            NavRow("Sprechererkennung", "Zusatzmodelle für die Sprechertrennung laden", Icons.Filled.RecordVoiceOver, onOpenMeetingModels)

            SectionTitle("Verlauf")
            SwitchRow("Verlauf speichern",
                "Aus: Transkripte werden nirgends gespeichert. Statistiken gibt es dann auch keine.", history) {
                history = it; Settings.setHistoryEnabled(context, it)
            }
        }
    }
}

@Composable
private fun MicrophoneRow() {
    val context = LocalContext.current
    val devices = remember { runCatching { AudioRecorder.inputDevices(context) }.getOrDefault(emptyList()) }
    var selected by remember { mutableIntStateOf(Settings.micDevice(context)) }
    var open by remember { mutableStateOf(false) }
    val current = devices.firstOrNull { it.id == selected }?.let { AudioRecorder.deviceLabel(it) } ?: "Systemvorgabe"
    Box {
        ListItem(
            headlineContent = { Text("Mikrofon") },
            supportingContent = { Text(current) },
            leadingContent = { Icon(Icons.Filled.Mic, contentDescription = null) },
            trailingContent = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.clickable { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Systemvorgabe") }, onClick = {
                selected = 0; Settings.setMicDevice(context, 0); open = false
            })
            devices.forEach { d ->
                DropdownMenuItem(text = { Text(AudioRecorder.deviceLabel(d)) }, onClick = {
                    selected = d.id; Settings.setMicDevice(context, d.id); open = false
                })
            }
        }
    }
}

@Composable
fun PromptEditorDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val default = remember { Prompts.defaultEnhanceRules(context) }
    var text by remember { mutableStateOf(Settings.enhancePrompt(context) ?: default) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("System-Prompt") },
        text = {
            Column {
                Text(
                    "Regeln, nach denen Enhance Diktate überarbeitet. Der Standard ist englisch, damit das Modell " +
                        "ihn zuverlässig befolgt — eigene Regeln gehen in jeder Sprache. Sprache, Modus und Wortliste " +
                        "hängt die App selbst an.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 400.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Settings.setEnhancePrompt(context, text.trim().takeIf { it.isNotEmpty() && it != default.trim() })
                onDismiss()
            }) { Text("Speichern") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { text = default }) { Text("Standard") }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Wörterbuch: eigene Wörter, Ersetzungen, Snippets (mit Live-Filter)
// ---------------------------------------------------------------------------

@Composable
fun DictionaryOverlay(onClose: () -> Unit) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var words by remember { mutableStateOf(Vocabulary.words) }
    var rules by remember { mutableStateOf(Replacements.rules) }
    var snippets by remember { mutableStateOf(Snippets.all) }

    OverlayScaffold(title = "Wörterbuch", subtitle = "Gilt in der App, der Tastatur und im Diktat", onClose = onClose) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Eigene Wörter") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Ersetzungen") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Snippets") })
        }
        Spacer(Modifier.height(12.dp))
        when (tab) {
            0 -> WordsContent(words) { Vocabulary.save(context, it); words = Vocabulary.words }
            1 -> ReplacementsContent(rules, { Replacements.save(context, it); rules = Replacements.rules })
            else -> SnippetsContent(snippets) { Snippets.save(context, it); snippets = Snippets.all }
        }
    }
}

@Composable
private fun WordsContent(words: List<String>, onChange: (List<String>) -> Unit) {
    var input by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    val filtered = remember(words, filter) {
        val q = filter.trim()
        if (q.isEmpty()) words else words.filter { it.contains(q, ignoreCase = true) }
    }
    fun add() {
        val w = input.trim()
        if (w.isEmpty()) return
        onChange(listOf(w) + words)
        input = ""
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(
            "Namen und Fachbegriffe in der gewünschten Schreibweise. Whisper bekommt die Liste vorab und schreibt " +
                "sie dann eher richtig; ähnlich Klingendes („Scheiße Wasser“) wird danach korrigiert. Enhance hält sich ebenfalls daran.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text("Neues Wort …") }, singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { add() }, enabled = input.isNotBlank()) {
                Icon(Icons.Filled.Add, contentDescription = "Wort hinzufügen")
            }
        }
        if (words.size > 8) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = filter, onValueChange = { filter = it },
                placeholder = { Text("Wörter filtern …") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (filter.isNotEmpty()) IconButton(onClick = { filter = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Filter zurücksetzen")
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            if (filter.isBlank()) "${words.size} Wörter" else "${filtered.size} von ${words.size} Wörtern",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        if (words.isEmpty()) {
            EmptyState(Icons.Filled.Spellcheck, "Noch keine eigenen Wörter",
                "Typische Kandidaten: Namen, Firmen, Produkte, Fachbegriffe.")
            if (Vocabulary.defaults.isNotEmpty()) {
                TextButton(onClick = { onChange(Vocabulary.defaults) }) {
                    Text("Häufige englische Begriffe hinzufügen")
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(filtered, key = { it }) { w ->
                ListItem(
                    headlineContent = { Text(w) },
                    trailingContent = {
                        IconButton(onClick = { onChange(words - w) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Wort entfernen", tint = MaterialTheme.colorScheme.error)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SnippetsContent(snippets: List<Snippets.Snippet>, onChange: (List<Snippets.Snippet>) -> Unit) {
    var trigger by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Snippets.Snippet?>(null) }
    fun save() {
        if (trigger.isBlank() || text.isEmpty()) return
        val s = Snippets.Snippet(trigger.trim(), text)
        onChange(snippets.filterNot { it == editing || it.trigger.equals(s.trigger, ignoreCase = true) } + s)
        trigger = ""; text = ""; editing = null
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(trigger, { trigger = it }, label = { Text("Trigger-Phrase") },
                    placeholder = { Text("z. B. meine Adresse") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(text, { text = it }, label = { Text("Einzufügender Text") },
                    minLines = 2, modifier = Modifier.fillMaxWidth())
                Row {
                    Button(onClick = { save() }, enabled = trigger.isNotBlank() && text.isNotEmpty()) {
                        Text(if (editing != null) "Speichern" else "Snippet anlegen")
                    }
                    if (editing != null) TextButton(onClick = { editing = null; trigger = ""; text = "" }) { Text("Abbrechen") }
                }
                Text("Sagt man nur die Trigger-Phrase — ohne weitere Worte —, wird stattdessen dieser Text eingefügt.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (snippets.isEmpty()) EmptyState(Icons.AutoMirrored.Filled.ShortText, "Keine Snippets",
            "Praktisch für Adresse, Signatur, Links oder Textbausteine.")
        snippets.forEach { s ->
            OutlinedCard(Modifier.fillMaxWidth().clickable { editing = s; trigger = s.trigger; text = s.text }) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("„${s.trigger}“", style = MaterialTheme.typography.titleSmall)
                        Text(s.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { onChange(snippets - s) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Snippet löschen", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Statistiken
// ---------------------------------------------------------------------------

@Composable
fun StatsOverlay(onClose: () -> Unit) {
    val context = LocalContext.current
    val all = remember { HistoryStore.load(context) }
    var days by remember { mutableIntStateOf(30) } // 0 = gesamt
    var source by remember { mutableStateOf<String?>(null) }
    val stats = remember(days, source) { Stats.compute(all, days, source, System.currentTimeMillis()) }

    OverlayScaffold(title = "Statistiken", onClose = onClose) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!Settings.historyEnabled(context)) {
                StatusCard("Der Verlauf ist abgeschaltet — neue Transkriptionen fließen nicht in die Statistik ein.",
                    isError = false, onDismiss = {})
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7 to "7 Tage", 30 to "30 Tage", 0 to "Gesamt").forEach { (d, label) ->
                    FilterChip(selected = days == d, onClick = { days = d }, label = { Text(label) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null to "Alle", "dictation" to "Diktat", HistoryEntry.SOURCE_FILE to "Datei", HistoryEntry.SOURCE_MEETING to "Meeting")
                    .forEach { (s, label) -> FilterChip(selected = source == s, onClick = { source = s }, label = { Text(label) }) }
            }
            if (stats.count == 0) {
                EmptyState(Icons.Filled.BarChart, "Noch keine Daten", "Starte eine Transkription — sie erscheint dann hier.")
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Transkriptionen", stats.count.toString(), Modifier.weight(1f))
                StatTile("Wörter", stats.words.toString(), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Gesprochen", formatHours(stats.audioSeconds), Modifier.weight(1f))
                StatTile("Ø Dauer", formatDuration(stats.avgSeconds), Modifier.weight(1f))
            }
            StatTile("Zeit gespart ggü. Tippen", formatHours(stats.savedSeconds), Modifier.fillMaxWidth(),
                hint = "Annahme: 35 Wörter pro Minute beim Tippen auf dem Handy")
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Wörter pro Tag", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(12.dp))
                    WordsChart(stats.perDay)
                }
            }
            if (stats.languages.isNotEmpty()) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Sprachen", style = MaterialTheme.typography.titleSmall)
                        stats.languages.forEach { (lang, n) ->
                            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                                Text(languageLabel(lang), Modifier.weight(1f))
                                Text("${n}×", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun languageLabel(code: String) = when (code) {
    "de" -> "Deutsch"; "en" -> "Englisch"; "fr" -> "Französisch"; "es" -> "Spanisch"; "it" -> "Italienisch"
    "nl" -> "Niederländisch"; "pl" -> "Polnisch"; "tr" -> "Türkisch"; "" -> "Mehrsprachig (Parakeet)"
    else -> code
}

private fun formatHours(seconds: Float): String {
    val m = (seconds / 60).toInt()
    return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, hint: String? = null) {
    ElevatedCard(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun WordsChart(perDay: List<Pair<String, Int>>) {
    val bar = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val max = (perDay.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        val n = perDay.size.coerceAtLeast(1)
        val gap = size.width / n * 0.25f
        val w = size.width / n - gap
        perDay.forEachIndexed { i, (_, v) ->
            val x = i * (w + gap)
            drawRoundRect(track, Offset(x, 0f), Size(w, size.height), CornerRadius(4f, 4f))
            val h = size.height * v / max
            if (v > 0) drawRoundRect(bar, Offset(x, size.height - h), Size(w, h), CornerRadius(4f, 4f))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Text(perDay.firstOrNull()?.first ?: "", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        Text("max. $max", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(perDay.lastOrNull()?.first ?: "", style = MaterialTheme.typography.labelSmall)
    }
}

/// Kennzahlen aus dem Verlauf — reine Rechnung, unit-getestet.
object Stats {
    data class Result(
        val count: Int,
        val words: Int,
        val audioSeconds: Float,
        val avgSeconds: Float,
        val savedSeconds: Float,
        val perDay: List<Pair<String, Int>>,
        val languages: List<Pair<String, Int>>,
    )

    /// Tippgeschwindigkeit auf dem Handy, Wörter pro Minute.
    const val TYPING_WPM = 35f

    /// [days] = 0: alles. [source] "dictation" fasst App-Aufnahme, Tastatur und Overlay zusammen.
    fun compute(all: List<HistoryEntry>, days: Int, source: String?, now: Long): Result {
        val since = if (days > 0) now - days * 86_400_000L else 0L
        val entries = all.filter { e ->
            e.timeMs >= since && when (source) {
                null -> true
                "dictation" -> e.source in setOf(HistoryEntry.SOURCE_APP, HistoryEntry.SOURCE_KEYBOARD, HistoryEntry.SOURCE_DICTATION)
                else -> e.source == source
            }
        }
        val words = entries.sumOf { it.words }
        val audio = entries.sumOf { it.audioSeconds.toDouble() }.toFloat()
        val typing = words / TYPING_WPM * 60f
        // Diagramm: je Tag, höchstens 30 Balken (bei „Gesamt“ die letzten 30 Tage mit Einträgen)
        val span = if (days > 0) days else 30
        val cal = Calendar.getInstance()
        val perDay = (span - 1 downTo 0).map { back ->
            cal.timeInMillis = now
            cal.add(Calendar.DAY_OF_YEAR, -back)
            val y = cal.get(Calendar.YEAR); val d = cal.get(Calendar.DAY_OF_YEAR)
            val label = "%02d.%02d.".format(cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1)
            label to entries.filter { e ->
                cal.timeInMillis = e.timeMs
                cal.get(Calendar.YEAR) == y && cal.get(Calendar.DAY_OF_YEAR) == d
            }.sumOf { it.words }
        }
        val languages = entries.filter { it.source != HistoryEntry.SOURCE_SELECTION }
            .groupingBy { it.language }.eachCount().toList().sortedByDescending { it.second }
        return Result(
            count = entries.size,
            words = words,
            audioSeconds = audio,
            avgSeconds = if (entries.isEmpty()) 0f else audio / entries.size,
            savedSeconds = (typing - audio).coerceAtLeast(0f),
            perDay = perDay,
            languages = languages,
        )
    }
}

// ---------------------------------------------------------------------------
// Sprachmodell (Enhance) wählen und laden
// ---------------------------------------------------------------------------

@Composable
fun LlmPickerOverlay(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf<List<ModelInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var current by remember { mutableStateOf(LlmManager.modelFile(context)?.name) }
    var local by remember { mutableStateOf(localLlms(context)) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        error = null
        scope.launch {
            catalog = try {
                withContext(Dispatchers.IO) { ModelRegistry.fetchCatalog().llms }
            } catch (e: Exception) { error = e.message ?: "Server nicht erreichbar"; emptyList() }
        }
    }
    LaunchedEffect(Unit) { refresh() }

    confirmDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Sprachmodell löschen?") },
            text = { Text("„$f“ wird vom Gerät entfernt und kann jederzeit neu geladen werden.") },
            confirmButton = {
                TextButton(onClick = {
                    if (current == f) { LlmBridge.free(); Settings.setLlmModel(context, null); Settings.setEnhanceAuto(context, false); current = null }
                    File(LlmManager.llmDir(context), f).delete()
                    local = localLlms(context); confirmDelete = null
                }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Abbrechen") } },
        )
    }

    OverlayScaffold(
        title = "Sprachmodell",
        subtitle = "Für Enhance, Umformen und Meeting-Notizen. Läuft komplett auf dem Gerät.",
        onClose = onClose,
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!LlmBridge.available) {
                StatusCard("Die Enhance-Engine fehlt in dieser App-Version.", isError = true, onDismiss = {})
                return@Column
            }
            Text("Größere Modelle formulieren deutlich besser, brauchen aber mehr Speicher und Zeit. " +
                "Empfehlung ab 8 GB RAM: Qwen3 4B.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { StatusCard("Server nicht erreichbar: $it", isError = true, onDismiss = { refresh() }) }
            val list = catalog
            if (list == null) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp)); Text("Frage Server nach Sprachmodellen …")
            }
            list?.forEach { info ->
                val isLocal = info.file in local
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(info.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text(info.sizeText(), style = MaterialTheme.typography.labelMedium)
                        }
                        Text(info.tagline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        info.pros.forEach { Text("✓  $it", style = MaterialTheme.typography.bodySmall) }
                        info.cons.forEach { Text("–  $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Spacer(Modifier.height(10.dp))
                        when {
                            downloading == info.file -> {
                                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                                Text("Lade herunter … %.0f %%".format(progress * 100), style = MaterialTheme.typography.bodySmall)
                            }
                            current == info.file -> Row(verticalAlignment = Alignment.CenterVertically) {
                                AssistChip(onClick = {}, label = { Text("Aktiv") }, leadingIcon = { Icon(Icons.Filled.CheckCircle, null) })
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { confirmDelete = info.file }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "Löschen", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            isLocal -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(onClick = { LlmBridge.free(); Settings.setLlmModel(context, info.file); current = info.file }) { Text("Aktivieren") }
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { confirmDelete = info.file }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "Löschen", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            else -> FilledTonalButton(enabled = downloading == null, onClick = {
                                downloading = info.file; progress = 0f; error = null
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            ModelRegistry.download(info, LlmManager.llmDir(context)) { d, t -> if (t > 0) progress = d.toFloat() / t }
                                        }
                                        LlmBridge.free()
                                        Settings.setLlmModel(context, info.file)
                                        current = info.file
                                        local = localLlms(context)
                                    } catch (e: Exception) {
                                        error = "Download fehlgeschlagen: ${e.message}"
                                    } finally { downloading = null }
                                }
                            }) {
                                Icon(Icons.Filled.Download, contentDescription = null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp)); Text("Herunterladen")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun localLlms(context: Context): List<String> =
    LlmManager.llmDir(context).listFiles()?.filter { it.name.endsWith(".gguf") }?.map { it.name } ?: emptyList()

// ---------------------------------------------------------------------------
// App-Profile
// ---------------------------------------------------------------------------

@Composable
fun AppProfilesOverlay(onClose: () -> Unit) {
    val context = LocalContext.current
    var profiles by remember { mutableStateOf(AppProfiles.all) }
    var picking by remember { mutableStateOf(false) }
    val apps = remember { launchableApps(context) }

    fun update(pkg: String, p: AppProfiles.Profile?) {
        AppProfiles.set(context, pkg, p); profiles = AppProfiles.all
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("App auswählen") },
            text = {
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    items(apps.filter { it.first !in profiles.keys }) { (pkg, label) ->
                        ListItem(headlineContent = { Text(label) }, supportingContent = { Text(pkg) },
                            modifier = Modifier.clickable { update(pkg, AppProfiles.Profile(mode = FormatMode.STANDARD)); picking = false })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Schließen") } },
        )
    }

    OverlayScaffold(
        title = "App-Profile",
        subtitle = "Gilt für die Diktat-Tastatur. Ohne Profil erkennt die App den passenden Modus selbst.",
        onClose = onClose,
        actions = { IconButton(onClick = { picking = true }) { Icon(Icons.Filled.Add, contentDescription = "App hinzufügen") } },
    ) {
        if (profiles.isEmpty()) {
            EmptyState(Icons.Filled.Apps, "Noch keine Profile",
                "Mit + eine App hinzufügen, z. B. um sie von Enhance auszuschließen oder nach dem Diktat automatisch zu senden.",
                Modifier.padding(top = 24.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(profiles.entries.toList()) { (pkg, p) ->
                val label = apps.firstOrNull { it.first == pkg }?.second ?: pkg
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            IconButton(onClick = { update(pkg, null) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Profil entfernen", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        ModeDropdown(p.mode, enabled = !p.excluded) { update(pkg, p.copy(mode = it)) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Ausschließen (rohes Diktat)", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = p.excluded, onCheckedChange = { update(pkg, p.copy(excluded = it)) })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Nach dem Diktat automatisch senden", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = p.autoSend, onCheckedChange = { update(pkg, p.copy(autoSend = it)) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ModeDropdown(mode: FormatMode, enabled: Boolean = true, onSelect: (FormatMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true }, enabled = enabled,
            label = { Text("Modus: ${mode.label}") },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FormatMode.selectable.forEach { m ->
                DropdownMenuItem(text = { Text(m.label) }, onClick = { onSelect(m); open = false },
                    trailingIcon = if (m == mode) { { Icon(Icons.Filled.Check, null) } } else null)
            }
        }
    }
}

private fun launchableApps(context: Context): List<Pair<String, String>> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return runCatching {
        pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != context.packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }.getOrDefault(emptyList())
}

// ---------------------------------------------------------------------------
// Umformen (Transkript in der App, markierter Text in anderen Apps)
// ---------------------------------------------------------------------------

data class TransformAction(val key: String, val label: String, val icon: ImageVector)

val TRANSFORM_ACTIONS = listOf(
    TransformAction("improve", "Verbessern", Icons.Filled.AutoAwesome),
    TransformAction("spelling", "Rechtschreibung", Icons.Filled.Spellcheck),
    TransformAction("shorten", "Kürzen", Icons.Filled.Compress),
    TransformAction("expand", "Ausführlicher", Icons.Filled.Expand),
    TransformAction("professional", "Professioneller", Icons.Filled.BusinessCenter),
    TransformAction("casual", "Lockerer", Icons.Filled.EmojiEmotions),
    TransformAction("bullets", "Aufzählung", Icons.AutoMirrored.Filled.FormatListBulleted),
    TransformAction("todo", "Aufgabenliste", Icons.Filled.Checklist),
    TransformAction("translate", "Übersetzen", Icons.Filled.Translate),
)

/// Anweisung für eine Aktion; „Übersetzen“ geht in die jeweils andere Sprache.
fun transformInstruction(context: Context, key: String, text: String): String {
    val target = if (TextRules.guessLanguage(text) == "en") "German" else "English"
    return Prompts.action(context, key, target)
}

/// Liste der Umform-Aktionen plus eigene Anweisung (getippt).
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransformPanel(onAction: (TransformAction) -> Unit, onCustom: (String) -> Unit, extra: @Composable () -> Unit = {}) {
    var custom by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Fließend umbrechen: feste Dreierreihen liefen auf schmalen Geräten über
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TRANSFORM_ACTIONS.forEach { a ->
                AssistChip(onClick = { onAction(a) }, label = { Text(a.label, maxLines = 1) },
                    leadingIcon = { Icon(a.icon, null, Modifier.size(18.dp)) })
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = custom, onValueChange = { if (it.length <= 1000) custom = it },
            label = { Text("Eigene Anweisung") },
            placeholder = { Text("z. B. „als freundliche Absage formulieren“") },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                Row {
                    extra()
                    IconButton(onClick = { onCustom(custom.trim()) }, enabled = custom.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Anwenden")
                    }
                }
            },
        )
    }
}

/// Dialog „Umformen“ für das Transkript in der App.
@Composable
fun TransformDialog(text: String, onDismiss: () -> Unit, onRun: (instruction: String, title: String) -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
        title = { Text("Transkript umformen") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Das Sprachmodell bearbeitet den ganzen Text. Das Ergebnis ersetzt das Transkript; " +
                    "der Verlauf behält das Original.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                TransformPanel(
                    onAction = { a -> onRun(transformInstruction(context, a.key, text), a.label) },
                    onCustom = { onRun(it, "Eigene Anweisung") },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
