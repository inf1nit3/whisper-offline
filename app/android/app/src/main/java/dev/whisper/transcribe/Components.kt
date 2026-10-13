package dev.whisper.transcribe

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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

/// Großer Aufnahmeknopf. Während der Aufnahme wird er mit Federbewegung zum
/// abgerundeten Quadrat (Stopp) und ein Ring pulsiert mit dem Mikrofonpegel —
/// sichtbare Rückmeldung, dass tatsächlich Ton ankommt. [working]: Während
/// transkribiert wird, dreht sich ein Fortschrittskreis im Knopf.
@Composable
fun RecordButton(
    recording: Boolean,
    level: Float,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    working: Boolean = false,
) {
    val container by animateColorAsState(
        when {
            recording -> MaterialTheme.colorScheme.error
            working -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        },
        label = "recordColor",
    )
    val ring by animateFloatAsState(
        if (recording) 1f + level.coerceIn(0f, 1f) * 0.45f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "recordRing",
    )
    // Anteil der Knopfgröße: 0.5 = Kreis, 0.28 = abgerundetes Quadrat
    val corner by animateFloatAsState(
        if (recording) 0.28f else 0.5f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow),
        label = "recordCorner",
    )
    val buttonSize = size * 0.68f
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
            shape = RoundedCornerShape(buttonSize * corner.coerceIn(0f, 0.5f)),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = container,
                // Beim Transkribieren nicht ausgegraut — der Knopf zeigt den Fortschritt
                disabledContainerColor = if (working) container else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            ),
            modifier = Modifier
                .size(buttonSize)
                .semantics {
                    contentDescription = when {
                        recording -> "Aufnahme beenden"
                        working -> "Transkription läuft"
                        else -> "Aufnahme starten"
                    }
                },
        ) {
            AnimatedContent(
                targetState = when { recording -> 1; working -> 2; else -> 0 },
                transitionSpec = {
                    (scaleIn(tween(220)) + fadeIn()) togetherWith (scaleOut(tween(180)) + fadeOut())
                },
                label = "recordIcon",
            ) { state ->
                when (state) {
                    1 -> Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(size * 0.28f))
                    2 -> CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onTertiary,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(size * 0.2f),
                    )
                    else -> Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(size * 0.28f))
                }
            }
        }
    }
}

enum class WaveState { IDLE, RECORDING, WORKING }

/// Wellenform unter dem Aufnahmeknopf: drei überlagerte Sinuswellen, deren
/// Höhe dem Mikrofonpegel folgt; beim Transkribieren eine ruhige Welle, im
/// Leerlauf eine stille Linie. Läuft nur, wenn sich etwas bewegt — im
/// Leerlauf und bei abgeschalteten Systemanimationen kostet sie keine Frames.
@Composable
fun LiveWaveform(state: WaveState, level: Float, modifier: Modifier = Modifier) {
    val target = when (state) {
        // Wurzel: Sprache liegt meist im unteren Pegelbereich
        WaveState.RECORDING -> kotlin.math.sqrt(level.coerceIn(0f, 1f)).coerceIn(0.08f, 1f)
        WaveState.WORKING -> 0.22f
        WaveState.IDLE -> 0f
    }
    val amplitude by animateFloatAsState(
        target,
        animationSpec = spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow),
        label = "waveAmp",
    )
    val color by animateColorAsState(
        when (state) {
            WaveState.RECORDING -> MaterialTheme.colorScheme.error
            WaveState.WORKING -> MaterialTheme.colorScheme.tertiary
            WaveState.IDLE -> MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = tween(400),
        label = "waveColor",
    )
    val accent = MaterialTheme.colorScheme.primary

    val context = LocalContext.current
    val animate = remember {
        android.provider.Settings.Global.getFloat(
            context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ) > 0f
    }
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state, animate) {
        if (state == WaveState.IDLE || !animate) return@LaunchedEffect
        val speed = if (state == WaveState.RECORDING) 5.7f else 3.2f // rad/s
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                phase = (phase + (now - last) / 1e9f * speed) % (2f * Math.PI.toFloat())
                last = now
            }
        }
    }

    Canvas(modifier) {
        val w = size.width
        val mid = size.height / 2f
        val step = 3.dp.toPx()
        // (Deckkraft, Strichstärke, Frequenz)
        val layers = listOf(Triple(1f, 3.dp.toPx(), 1f), Triple(0.55f, 2.dp.toPx(), 1.6f), Triple(0.3f, 1.5.dp.toPx(), 2.3f))
        layers.forEachIndexed { i, (alpha, stroke, freq) ->
            val path = Path()
            var x = 0f
            while (x <= w + step) {
                val nx = (x / w).coerceAtMost(1f)
                // Fenster: an den Rändern läuft die Welle sanft auf null aus
                val envelope = kotlin.math.sin(nx * Math.PI).toFloat()
                val angle = nx * 4f * Math.PI.toFloat() * freq + phase * (i + 1)
                val y = mid + kotlin.math.sin(angle) * mid * 0.84f * amplitude * envelope
                if (x == 0f) path.moveTo(0f, y) else path.lineTo(nx * w, y)
                x += step
            }
            drawPath(
                path,
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, color.copy(alpha = alpha), accent.copy(alpha = alpha * color.alpha), Color.Transparent),
                ),
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
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

/// Transkript mit Kopieren, Teilen und Bearbeiten. Beim Bearbeiten wird der
/// Text zum Eingabefeld; der Haken übernimmt die Änderung.
@Composable
fun TranscriptCard(
    text: String,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
    /// Live-Vorschau während der Aufnahme: nur lesen, keine Aktionen.
    live: Boolean = false,
    /// ✨ Umformen per Sprachmodell; null = kein Modell eingerichtet.
    onTransform: (() -> Unit)? = null,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    // Cursor ans Ende — man ergänzt meist etwas oder korrigiert den Schluss
    var draft by remember(text, editing) { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(editing) { if (editing) focus.requestFocus() }
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (live) "Live" else "Transkript", style = MaterialTheme.typography.titleMedium,
                    color = if (live) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f))
                when {
                    live -> Spacer(Modifier.height(48.dp))
                    editing -> {
                        TextButton(onClick = { editing = false }) { Text("Verwerfen") }
                        IconButton(onClick = { onEdit(draft.text.trim()); editing = false }) {
                            Icon(Icons.Filled.Check, contentDescription = "Änderungen übernehmen",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    text.isNotEmpty() -> {
                        onTransform?.let { t ->
                            IconButton(onClick = t) { Icon(Icons.Filled.AutoAwesome, contentDescription = "Umformen") }
                        }
                        IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, contentDescription = "Bearbeiten") }
                        IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, contentDescription = "Kopieren") }
                        IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = "Teilen") }
                    }
                    else -> Spacer(Modifier.height(48.dp))
                }
            }
            when {
                editing -> OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(end = 12.dp)
                        .focusRequester(focus),
                )
                text.isEmpty() -> EmptyState(
                    icon = Icons.AutoMirrored.Outlined.Notes,
                    title = "Noch keine Transkription",
                    hint = "Aufnehmen, eine Datei wählen oder eine Sprachnachricht aus WhatsApp & Co. hierher teilen.",
                    modifier = Modifier.padding(end = 12.dp),
                )
                else -> SelectionContainer(Modifier.padding(end = 12.dp)) {
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
