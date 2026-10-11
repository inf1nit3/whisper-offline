package dev.whisper.transcribe

import android.content.Context
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// WAV (16 kHz, Mono, 16 Bit) — direkt auf die Platte, auch stundenlang
// ---------------------------------------------------------------------------

object Wav {
    const val HEADER = 44

    fun header(dataBytes: Long): ByteArray = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt((36 + dataBytes).toInt())
        put("WAVE".toByteArray()); put("fmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(dataBytes.toInt())
    }.array()

    /// Kopf an die tatsächliche Länge anpassen (nach Aufnahme oder Absturz).
    fun fixHeader(f: File) {
        if (!f.exists() || f.length() < HEADER) return
        RandomAccessFile(f, "rw").use { raf -> raf.seek(0); raf.write(header(f.length() - HEADER)) }
    }

    fun sampleCount(f: File): Int = ((f.length() - HEADER).coerceAtLeast(0) / 2).toInt()

    /// Samples [from, to) als Floats in [-1, 1].
    fun read(f: File, from: Int, to: Int): FloatArray {
        val n = (to - from).coerceAtLeast(0)
        if (n == 0) return FloatArray(0)
        val bytes = ByteArray(n * 2)
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(HEADER + from * 2L)
            raf.readFully(bytes, 0, minOf(bytes.size, (raf.length() - raf.filePointer).toInt()))
        }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(n) { bb.get(it) / 32768f }
    }
}

/// Aufnahme in eine WAV-Datei. Pause/Weiter hängen an dieselbe Datei an.
class FileRecorder(context: Context, private val file: File) : SampleSource {
    private val recorder = AudioRecorder(context.applicationContext, keepInMemory = false)
    private var out: BufferedOutputStream? = null
    @Volatile private var written = Wav.sampleCount(file)
    private val lock = Any()

    val level: Float get() = recorder.level
    val isRecording: Boolean get() = recorder.isRecording
    override val sampleCount: Int get() = written

    fun start(): Boolean {
        if (!file.exists()) { file.parentFile?.mkdirs(); file.writeBytes(Wav.header(0)) }
        out = BufferedOutputStream(FileOutputStream(file, true), 64 * 1024)
        val buf = ByteBuffer.allocate(4096 * 2).order(ByteOrder.LITTLE_ENDIAN)
        recorder.onChunk = { chunk, n ->
            synchronized(lock) {
                buf.clear()
                for (i in 0 until n) buf.putShort(chunk[i])
                out?.write(buf.array(), 0, n * 2)
                written += n
            }
        }
        if (!recorder.start()) { out?.close(); out = null; return false }
        return true
    }

    fun stop(cue: Boolean = true) {
        recorder.stop(cue)
        synchronized(lock) { out?.flush(); out?.close(); out = null }
        Wav.fixHeader(file)
    }

    override fun snapshot(from: Int, to: Int): FloatArray {
        synchronized(lock) { out?.flush() }
        val end = if (to < 0) written else minOf(to, written)
        return Wav.read(file, from.coerceIn(0, end), end)
    }
}

// ---------------------------------------------------------------------------
// Datenmodell und Ablage: filesDir/meetings/<id>/{audio.wav, meeting.json}
// ---------------------------------------------------------------------------

data class Utterance(val speaker: Int, val start: Float, val end: Float, val text: String)

data class Speaker(val name: String, val recognized: Boolean = false)

data class Meeting(
    val id: String,
    val title: String,
    val startedMs: Long,
    /// "recording", "recorded" (wartet auf Auswertung), "done"
    val state: String,
    val speakerCount: Int = 0,
    val durationSec: Float = 0f,
    val utterances: List<Utterance> = emptyList(),
    val speakers: List<Speaker> = emptyList(),
    val notes: String = "",
    val diarized: Boolean = false,
) {
    fun speakerName(i: Int) = speakers.getOrNull(i)?.name ?: "Sprecher ${i + 1}"

    /// Transkript als Text: „Name: …“ je Redebeitrag.
    fun transcriptText(): String = utterances.joinToString("\n\n") { u ->
        if (diarized) "${speakerName(u.speaker)}: ${u.text}" else u.text
    }

    /// Redezeit je Sprecher in Sekunden.
    fun talkTime(): Map<Int, Float> = utterances.groupBy { it.speaker }.mapValues { (_, l) -> l.sumOf { (it.end - it.start).toDouble() }.toFloat() }
}

object MeetingStore {

    fun dir(context: Context): File = File(context.filesDir, "meetings").also { it.mkdirs() }
    fun folder(context: Context, id: String) = File(dir(context), id)
    fun audio(context: Context, id: String) = File(folder(context, id), "audio.wav")

    fun list(context: Context): List<Meeting> =
        dir(context).listFiles()?.mapNotNull { load(context, it.name) }?.sortedByDescending { it.startedMs } ?: emptyList()

    fun load(context: Context, id: String): Meeting? = runCatching {
        val o = JSONObject(File(folder(context, id), "meeting.json").readText())
        Meeting(
            id = o.getString("id"),
            title = o.optString("title"),
            startedMs = o.getLong("started"),
            state = o.optString("state", "recorded"),
            speakerCount = o.optInt("speakerCount"),
            durationSec = o.optDouble("duration", 0.0).toFloat(),
            utterances = o.optJSONArray("utterances")?.let { a ->
                List(a.length()) { i ->
                    val u = a.getJSONObject(i)
                    Utterance(u.getInt("s"), u.getDouble("a").toFloat(), u.getDouble("b").toFloat(), u.getString("t"))
                }
            } ?: emptyList(),
            speakers = o.optJSONArray("speakers")?.let { a ->
                List(a.length()) { i -> a.getJSONObject(i).let { Speaker(it.getString("n"), it.optBoolean("r")) } }
            } ?: emptyList(),
            notes = o.optString("notes"),
            diarized = o.optBoolean("diarized"),
        )
    }.getOrNull()

    fun save(context: Context, m: Meeting) {
        val o = JSONObject()
            .put("id", m.id).put("title", m.title).put("started", m.startedMs).put("state", m.state)
            .put("speakerCount", m.speakerCount).put("duration", m.durationSec.toDouble())
            .put("notes", m.notes).put("diarized", m.diarized)
            .put("utterances", JSONArray().apply {
                m.utterances.forEach { put(JSONObject().put("s", it.speaker).put("a", it.start.toDouble()).put("b", it.end.toDouble()).put("t", it.text)) }
            })
            .put("speakers", JSONArray().apply { m.speakers.forEach { put(JSONObject().put("n", it.name).put("r", it.recognized)) } })
        val f = File(folder(context, m.id), "meeting.json")
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(f)
    }

    fun delete(context: Context, id: String) { folder(context, id).deleteRecursively() }
}

// ---------------------------------------------------------------------------
// Gespeicherte Stimmen: Name → Stimmabdruck (Embedding)
// ---------------------------------------------------------------------------

object VoiceProfiles {
    private fun file(context: Context) = File(context.filesDir, "voices.json")

    fun load(context: Context): Map<String, FloatArray> = runCatching {
        val o = JSONObject(file(context).readText())
        o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } } }
    }.getOrDefault(emptyMap())

    fun save(context: Context, name: String, embedding: FloatArray) {
        val all = load(context).toMutableMap()
        all[name] = embedding
        val o = JSONObject()
        all.forEach { (k, v) -> o.put(k, JSONArray().apply { v.forEach { put(it.toDouble()) } }) }
        file(context).writeText(o.toString())
    }
}

// ---------------------------------------------------------------------------
// Reine Logik: Sprecher über Fenster hinweg zuordnen, Redebeiträge bilden
// ---------------------------------------------------------------------------

object SpeakerMath {

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 0f else (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }

    /// Globale Sprecher als laufende Mittelwerte ihrer Stimmabdrücke.
    class Registry(private val threshold: Float, private val maxSpeakers: Int) {
        val centroids = mutableListOf<FloatArray>()
        private val counts = mutableListOf<Int>()

        /// Index des passenden globalen Sprechers; neu angelegt, wenn keiner
        /// ähnlich genug ist (bei fester Sprecherzahl: der ähnlichste).
        fun assign(e: FloatArray): Int {
            var best = -1; var bestSim = -1f
            centroids.forEachIndexed { i, c -> val s = cosine(c, e); if (s > bestSim) { bestSim = s; best = i } }
            val full = maxSpeakers > 0 && centroids.size >= maxSpeakers
            if (best >= 0 && (bestSim >= threshold || full)) {
                val n = counts[best]
                centroids[best] = FloatArray(e.size) { (centroids[best][it] * n + e[it]) / (n + 1) }
                counts[best] = n + 1
                return best
            }
            centroids += e.copyOf(); counts += 1
            return centroids.size - 1
        }
    }

    data class Turn(val speaker: Int, val start: Float, val end: Float)

    /// Aufeinanderfolgende Abschnitte desselben Sprechers zu Redebeiträgen
    /// zusammenfassen — höchstens [maxLen] Sekunden je Stück, damit Whisper
    /// in einem 30-s-Fenster bleibt.
    fun mergeTurns(turns: List<Turn>, maxGap: Float = 1.0f, maxLen: Float = 28f): List<Turn> {
        val out = mutableListOf<Turn>()
        for (t in turns.sortedBy { it.start }) {
            val last = out.lastOrNull()
            if (last != null && last.speaker == t.speaker && t.start - last.end <= maxGap && t.end - last.start <= maxLen) {
                out[out.lastIndex] = last.copy(end = maxOf(last.end, t.end))
            } else {
                // Zu lange Abschnitte teilen
                var s = t.start
                while (t.end - s > maxLen) { out += Turn(t.speaker, s, s + maxLen); s += maxLen }
                out += Turn(t.speaker, s, t.end)
            }
        }
        return out
    }
}

// ---------------------------------------------------------------------------
// Sprechertrennung mit sherpa-onnx
// ---------------------------------------------------------------------------

object MeetingModels {
    fun dir(context: Context) = File(context.filesDir, "meeting").also { it.mkdirs() }
    fun segmentation(context: Context) = dir(context).listFiles()?.firstOrNull { it.name.contains("segmentation") && it.name.endsWith(".onnx") }
    fun embedding(context: Context) = dir(context).listFiles()?.firstOrNull { !it.name.contains("segmentation") && it.name.endsWith(".onnx") }
    fun available(context: Context) = segmentation(context) != null && embedding(context) != null
}

class Diarizer(context: Context, numSpeakers: Int) : AutoCloseable {
    private val threads = WhisperBridge.detectThreads().coerceIn(1, 4)
    private val embCfg = SpeakerEmbeddingExtractorConfig(MeetingModels.embedding(context)!!.absolutePath, threads, false, "cpu")
    private val diar = OfflineSpeakerDiarization(null, OfflineSpeakerDiarizationConfig(
        segmentation = OfflineSpeakerSegmentationModelConfig(
            OfflineSpeakerSegmentationPyannoteModelConfig(MeetingModels.segmentation(context)!!.absolutePath, 0.5f),
            threads, false, "cpu"),
        embedding = embCfg,
        clustering = FastClusteringConfig(if (numSpeakers > 0) numSpeakers else -1, 0.5f, false),
        minDurationOn = 0.3f,
        minDurationOff = 0.5f,
    ))
    private val extractor = SpeakerEmbeddingExtractor(null, embCfg)

    data class Result(val turns: List<SpeakerMath.Turn>, val embeddings: List<FloatArray>)

    /// Ganze Aufnahme in Fenstern von [windowSec] Sekunden. Sherpa nummeriert
    /// Sprecher je Fenster neu — über Stimmabdrücke werden sie global zugeordnet.
    fun run(audio: File, numSpeakers: Int, windowSec: Int = 600, onProgress: (Float) -> Unit): Result {
        val total = Wav.sampleCount(audio)
        val win = windowSec * SR
        // Gemessen (WeSpeaker ResNet34): verschiedene Personen ~0,5, dieselbe 0,7–0,97
        val registry = SpeakerMath.Registry(threshold = SAME_SPEAKER, maxSpeakers = numSpeakers)
        val turns = mutableListOf<SpeakerMath.Turn>()
        var from = 0
        while (from < total) {
            val to = minOf(total, from + win)
            val samples = Wav.read(audio, from, to)
            val segs = diar.process(samples)
            val offset = from / SR.toFloat()
            // Je lokalem Sprecher bis zu 20 s Audio für den Stimmabdruck
            val local = segs.groupBy { it.speaker }.mapValues { (_, list) ->
                val buf = ArrayList<Float>()
                for (s in list.sortedByDescending { it.end - it.start }) {
                    val a = (s.start * SR).toInt().coerceIn(0, samples.size)
                    val b = (s.end * SR).toInt().coerceIn(a, samples.size)
                    for (i in a until b) { buf += samples[i]; if (buf.size >= 20 * SR) break }
                    if (buf.size >= 20 * SR) break
                }
                registry.assign(embed(buf.toFloatArray()))
            }
            segs.forEach { s -> turns += SpeakerMath.Turn(local[s.speaker] ?: 0, s.start + offset, s.end + offset) }
            from = to
            onProgress(from.toFloat() / total)
        }
        return Result(turns, registry.centroids)
    }

    fun embed(samples: FloatArray): FloatArray {
        val stream = extractor.createStream()
        stream.acceptWaveform(samples, SR)
        stream.inputFinished()
        val e = if (extractor.isReady(stream)) extractor.compute(stream) else FloatArray(extractor.dim())
        stream.release()
        return e
    }

    override fun close() { diar.release(); extractor.release() }

    companion object {
        private const val SR = 16000
        /// Ab dieser Kosinus-Ähnlichkeit gilt ein Stimmabdruck als dieselbe Person.
        const val SAME_SPEAKER = 0.62f
    }
}

// ---------------------------------------------------------------------------
// Laufende Sitzung: Aufnahme → Auswertung, beobachtbar für Oberfläche und Dienst
// ---------------------------------------------------------------------------

object MeetingSession {

    enum class Phase { IDLE, RECORDING, PAUSED, PROCESSING, DONE, ERROR }

    data class State(
        val phase: Phase = Phase.IDLE,
        val meetingId: String? = null,
        val seconds: Int = 0,
        val level: Float = 0f,
        val live: String = "",
        val progress: Float? = null,
        val detail: String = "",
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recorder: FileRecorder? = null
    private var ticker: Job? = null
    private var live: LiveTranscriber? = null
    private var work: Job? = null

    val isActive: Boolean get() = _state.value.phase in setOf(Phase.RECORDING, Phase.PAUSED, Phase.PROCESSING)

    fun start(context: Context, title: String, speakerCount: Int): Boolean {
        if (isActive) return false
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val id = "m$now"
        val m = Meeting(id, title.ifBlank { "Meeting " + java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.GERMANY).format(now) },
            now, "recording", speakerCount)
        MeetingStore.save(app, m)
        _state.value = State(Phase.RECORDING, id)
        if (!resume(app)) {
            MeetingStore.delete(app, id)
            _state.value = State(Phase.ERROR, error = "Mikrofon konnte nicht geöffnet werden")
            return false
        }
        MeetingService.start(app)
        return true
    }

    private fun resume(app: Context): Boolean {
        val id = _state.value.meetingId ?: return false
        val rec = FileRecorder(app, MeetingStore.audio(app, id))
        if (!rec.start()) return false
        recorder = rec
        _state.value = _state.value.copy(phase = Phase.RECORDING)
        if (Settings.liveTranscription(app) && WhisperBridge.isModelLoaded()) {
            live = LiveTranscriber(rec, Settings.language(app), false, scope) { t -> _state.value = _state.value.copy(live = t) }
                .also { it.start() }
        }
        ticker = scope.launch {
            while (isActive) {
                val r = recorder ?: break
                _state.value = _state.value.copy(seconds = r.sampleCount / 16000, level = r.level)
                delay(200)
            }
        }
        return true
    }

    fun pause() {
        if (_state.value.phase != Phase.RECORDING) return
        live?.stop(); live = null
        ticker?.cancel()
        recorder?.stop(); recorder = null
        _state.value = _state.value.copy(phase = Phase.PAUSED, level = 0f)
    }

    fun resumeRecording(context: Context) {
        if (_state.value.phase != Phase.PAUSED) return
        if (!resume(context.applicationContext)) _state.value = _state.value.copy(error = "Mikrofon konnte nicht geöffnet werden")
    }

    /// Aufnahme verwerfen (mit Datei).
    fun discard(context: Context) {
        live?.stop(); live = null
        ticker?.cancel()
        recorder?.stop(cue = false); recorder = null
        work?.cancel()
        _state.value.meetingId?.let { MeetingStore.delete(context.applicationContext, it) }
        _state.value = State()
        MeetingService.stop(context.applicationContext)
    }

    /// Aufnahme beenden und auswerten.
    fun finish(context: Context) {
        val app = context.applicationContext
        val id = _state.value.meetingId ?: return
        live?.stop(); live = null
        ticker?.cancel()
        recorder?.stop(); recorder = null
        MeetingStore.load(app, id)?.let { MeetingStore.save(app, it.copy(state = "recorded")) }
        process(app, id)
    }

    /// Gespeicherte, noch nicht ausgewertete Aufnahme (z. B. nach Absturz) auswerten.
    fun process(context: Context, id: String) {
        val app = context.applicationContext
        Wav.fixHeader(MeetingStore.audio(app, id))
        _state.value = State(Phase.PROCESSING, id, detail = "Bereite vor …")
        MeetingService.start(app)
        work = scope.launch {
            try {
                MeetingProcessor.run(app, id) { p, d -> _state.value = _state.value.copy(progress = p, detail = d) }
                _state.value = State(Phase.DONE, id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = State()
            } catch (e: Exception) {
                _state.value = State(Phase.ERROR, id, error = e.message ?: "Auswertung fehlgeschlagen")
            } finally {
                MeetingService.stop(app)
            }
        }
    }

    fun cancelProcessing() {
        WhisperBridge.cancel(); Enhancer.cancel(); work?.cancel()
    }

    fun reset() { if (!isActive) _state.value = State() }
}

/// Auswertung: Sprecher trennen → je Redebeitrag transkribieren → Notizen.
object MeetingProcessor {

    suspend fun run(context: Context, id: String, onProgress: (Float?, String) -> Unit) {
        val meeting = MeetingStore.load(context, id) ?: error("Meeting nicht gefunden")
        val audio = MeetingStore.audio(context, id)
        val total = Wav.sampleCount(audio)
        if (total < 16000) error("Aufnahme zu kurz")
        val model = Settings.prefs(context).getString("model_file", null)
            ?.let { File(ModelRegistry.modelsDir(context), it) }?.takeIf { it.exists() } ?: error("Kein Transkriptionsmodell geladen")
        onProgress(null, "Lade Modell …")
        if (!WhisperBridge.load(context, model.absolutePath, Settings.useGpu(context))) error("Modell konnte nicht geladen werden")

        // 1. Sprecher
        var turns: List<SpeakerMath.Turn>
        var speakers: List<Speaker>
        var embeddings: List<FloatArray> = emptyList()
        val diarized = MeetingModels.available(context)
        if (diarized) {
            onProgress(0f, "Erkenne Sprecher …")
            val r = Diarizer(context, meeting.speakerCount).use { d -> d.run(audio, meeting.speakerCount) { p -> onProgress(p, "Erkenne Sprecher …") } }
            turns = r.turns
            embeddings = r.embeddings
            // Gespeicherte Stimmen wiedererkennen
            val known = VoiceProfiles.load(context)
            speakers = r.embeddings.mapIndexed { i, e ->
                val match = known.maxByOrNull { (_, v) -> SpeakerMath.cosine(v, e) }
                if (match != null && SpeakerMath.cosine(match.value, e) >= Diarizer.SAME_SPEAKER) Speaker(match.key, recognized = true)
                else Speaker("Sprecher ${i + 1}")
            }
        } else {
            // Ohne Sprechermodelle: an Pausen in Stücke teilen, ein Sprecher
            turns = pauseChunks(audio, total)
            speakers = listOf(Speaker("Sprecher 1"))
        }
        if (turns.isEmpty()) error("Keine Sprache erkannt")

        // 2. Transkription je Redebeitrag
        val pieces = SpeakerMath.mergeTurns(turns)
        val language = Settings.language(context)
        val utterances = mutableListOf<Utterance>()
        pieces.forEachIndexed { i, t ->
            onProgress(i.toFloat() / pieces.size, "Transkribiere ${i + 1} von ${pieces.size} …")
            val samples = Wav.read(audio, (t.start * 16000).toInt(), (t.end * 16000).toInt())
            if (samples.size < 8000) return@forEachIndexed
            val text = WhisperBridge.transcribe(samples, language)
            if (text == null && WhisperBridge.wasCancelled()) throw kotlinx.coroutines.CancellationException()
            val clean = text?.replace('\n', ' ')?.trim().orEmpty()
            if (clean.isNotEmpty()) utterances += Utterance(t.speaker, t.start, t.end, clean)
        }
        // Benachbarte Beiträge desselben Sprechers verbinden (lesbarer)
        val joined = mutableListOf<Utterance>()
        for (u in utterances) {
            val last = joined.lastOrNull()
            if (last != null && last.speaker == u.speaker && u.start - last.end < 2f) joined[joined.lastIndex] = last.copy(end = u.end, text = last.text + " " + u.text)
            else joined += u
        }
        // Sprecher ohne Text fallen weg, Nummern rücken nach
        val used = joined.map { it.speaker }.distinct().sorted()
        val remap = used.withIndex().associate { (i, s) -> s to i }
        var result = meeting.copy(
            state = "done",
            durationSec = total / 16000f,
            utterances = joined.map { it.copy(speaker = remap.getValue(it.speaker)) },
            speakers = used.map { speakers.getOrElse(it) { s -> Speaker("Sprecher ${s + 1}") } }
                .mapIndexed { i, s -> if (!s.recognized) Speaker("Sprecher ${i + 1}") else s },
            diarized = diarized && used.size > 1,
        )
        MeetingStore.save(context, result)
        // Stimmabdrücke in der neuen Sprecher-Reihenfolge — zum Merken beim Umbenennen
        if (embeddings.isNotEmpty()) MeetingVoices.save(context, id, used.map { embeddings[it] })
        HistoryStore.add(context, HistoryEntry(meeting.startedMs, result.transcriptText(), model.name,
            if (WhisperBridge.engineKind() == WhisperBridge.ENGINE_PARAKEET) "" else TextPipeline.dictationLanguage(context),
            total / 16000f, HistoryEntry.SOURCE_MEETING))

        // 3. Notizen
        if (Settings.meetingNotesAuto(context) && LlmManager.isConfigured(context) && result.utterances.size >= 2) {
            onProgress(null, "Schreibe Notizen …")
            val lang = TextRules.guessLanguage(result.transcriptText()).ifEmpty { "de" }
            val o = Enhancer.meetingNotes(context, result.transcriptText(), lang) { d -> onProgress(null, d) }
            if (o.text != null) { result = result.copy(notes = o.text); MeetingStore.save(context, result) }
        }
    }

    /// Stücke von höchstens ~28 s, an Pausen geschnitten.
    private fun pauseChunks(audio: File, total: Int): List<SpeakerMath.Turn> {
        val out = mutableListOf<SpeakerMath.Turn>()
        var from = 0
        val max = 28 * 16000
        while (from < total) {
            val end = minOf(total, from + max)
            val samples = Wav.read(audio, from, end)
            val cut = if (end < total) Pauses.lastPause(samples, minSpeechSamples = 8 * 16000).takeIf { it > 0 } ?: samples.size else samples.size
            out += SpeakerMath.Turn(0, from / 16000f, (from + cut) / 16000f)
            from += cut
        }
        return out
    }
}

/// Stimmabdrücke der Sprecher eines Meetings (Index = Sprecher).
object MeetingVoices {
    private fun file(context: Context, id: String) = File(MeetingStore.folder(context, id), "voices.json")

    fun save(context: Context, id: String, list: List<FloatArray>) {
        file(context, id).writeText(JSONArray().apply { list.forEach { e -> put(JSONArray().apply { e.forEach { put(it.toDouble()) } }) } }.toString())
    }

    fun load(context: Context, id: String): List<FloatArray> = runCatching {
        val a = JSONArray(file(context, id).readText())
        List(a.length()) { i -> a.getJSONArray(i).let { e -> FloatArray(e.length()) { e.getDouble(it).toFloat() } } }
    }.getOrDefault(emptyList())
}
