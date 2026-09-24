package dev.whisper.transcribe

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/// "0:07", "1:23" oder "12,5 s" — kurz und ohne Nachkommastellen ab einer Minute.
fun formatDuration(seconds: Float): String =
    if (seconds < 60f) "%.1f s".format(seconds)
    else "%d:%02d min".format(seconds.toInt() / 60, seconds.toInt() % 60)

fun formatClock(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

/// Lesbarer Modellname: aus dem Manifest, sonst aus dem Dateinamen
/// ("ggml-small-q5_1.bin" → "Whisper small", "ggml-parakeet-tdt-0.6b-v3-…" → "Parakeet v3").
fun modelDisplayName(file: String?, manifest: List<ModelInfo>?): String {
    if (file == null) return "Kein Modell"
    manifest?.firstOrNull { it.file == file }?.let { return it.label.substringBefore(" — ") }
    val base = file.removePrefix("ggml-").removeSuffix(".bin")
    if (base.startsWith("parakeet")) {
        val version = Regex("""-(v\d+)""").find(base)?.groupValues?.get(1)
        return listOfNotNull("Parakeet", version).joinToString(" ")
    }
    // Quantisierung (q5_1, q8_0 …) ist für die Anzeige Rauschen
    return "Whisper " + base.replace(Regex("""-q\d+_\d+$"""), "")
}

/// Großer runder Aufnahmeknopf. Während der Aufnahme pulsiert ein Ring mit dem
/// Mikrofonpegel — sichtbare Rückmeldung, dass tatsächlich Ton ankommt.
@Composable
fun RecordButton(
    recording: Boolean,
    level: Float,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
) {
    val container by animateColorAsState(
        if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        label = "recordColor",
    )
    val ring by animateFloatAsState(
        if (recording) 1f + level.coerceIn(0f, 1f) * 0.45f else 1f,
        animationSpec = tween(120),
        label = "recordRing",
    )
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (recording) {
            Box(
                Modifier
                    .size(size * 0.74f)
                    .scale(ring)
                    .background(container.copy(alpha = 0.18f), CircleShape)
            )
        }
        FilledIconButton(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = container),
            modifier = Modifier
                .size(size * 0.68f)
                .semantics { contentDescription = if (recording) "Aufnahme beenden" else "Aufnahme starten" },
        ) {
            Icon(
                if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = null,
                modifier = Modifier.size(size * 0.28f),
            )
        }
    }
}

/// Laufende Arbeit (Datei lesen, transkribieren) mit Fortschritt.
data class Work(
    val title: String,
    /// null = unbestimmt (noch keine Schätzung möglich)
    val fraction: Float? = null,
    val detail: String? = null,
    val onCancel: (() -> Unit)? = null,
)

@Composable
fun WorkCard(work: Work, modifier: Modifier = Modifier) {
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(work.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                work.fraction?.let {
                    Text(
                        "%.0f %%".format(it * 100),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            val fraction = work.fraction
            if (fraction == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                val animated by animateFloatAsState(fraction, tween(300), label = "work")
                LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth())
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    work.detail ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                work.onCancel?.let { cancel ->
                    TextButton(onClick = cancel) { Text("Abbrechen") }
                }
            }
        }
    }
}

/// Hinweis- oder Fehlermeldung, schließbar.
@Composable
fun StatusCard(message: String, isError: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = if (isError) CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) else CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
    Card(modifier.fillMaxWidth(), colors = colors) {
        Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (isError) Icons.Filled.ErrorOutline else Icons.Filled.Info, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Meldung schließen") }
        }
    }
}

@Composable
fun TranscriptCard(
    text: String,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Transkript", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (text.isNotEmpty()) {
                    IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, contentDescription = "Kopieren") }
                    IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = "Teilen") }
                } else {
                    Spacer(Modifier.height(48.dp))
                }
            }
            if (text.isEmpty()) {
                EmptyState(
                    icon = Icons.AutoMirrored.Outlined.Notes,
                    title = "Noch keine Transkription",
                    hint = "Aufnehmen, eine Datei wählen oder eine Sprachnachricht aus WhatsApp & Co. hierher teilen.",
                    modifier = Modifier.padding(end = 12.dp),
                )
            } else {
                SelectionContainer(Modifier.padding(end = 12.dp)) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, hint: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/// Kopfzeile der Vollbild-Ansichten (Modelle, Verlauf) mit Schließen-Knopf.
@Composable
fun OverlayHeader(
    title: String,
    subtitle: String? = null,
    onClose: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        actions()
        onClose?.let { IconButton(onClick = it) { Icon(Icons.Filled.Close, contentDescription = "Schließen") } }
    }
}
