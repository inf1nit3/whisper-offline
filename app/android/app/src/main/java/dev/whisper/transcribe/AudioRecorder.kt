package dev.whisper.transcribe

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build

/// Nimmt Mikrofonaudio als 16-kHz-Mono-PCM auf, wie es whisper.cpp erwartet.
///
/// Mit [context] kümmert sich der Recorder auch um das Drumherum aus den
/// Einstellungen: Start-/Stopp-Ton, andere Wiedergabe leiser stellen
/// (Ducking) und das gewählte Mikrofon. Ohne [keepInMemory] landen die Samples
/// nur bei [onChunk] — für stundenlange Meetings, die direkt in eine Datei gehen.
class AudioRecorder(
    private val context: Context? = null,
    private val keepInMemory: Boolean = true,
) : SampleSource {

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    private val lock = Any()
    private var buffer = ShortArray(SAMPLE_RATE) // 1 s Startkapazität, wächst dynamisch
    private var used = 0

    /// Bekommt jeden gelesenen Block (Aufnahme-Thread).
    @Volatile var onChunk: ((ShortArray, Int) -> Unit)? = null

    /// Samples nach dem Start stummschalten, damit der eigene Startton nicht im
    /// Transkript landet.
    private var muteUntil = 0

    private var focusRequest: AudioFocusRequest? = null
    private var commDeviceSet = false

    val isRecording: Boolean get() = running

    /// Pegel des zuletzt gelesenen Blocks (RMS, grob 0..1) für die Anzeige.
    @Volatile var level = 0f
        private set

    /// Bisher aufgenommene Samples (nur mit [keepInMemory]).
    override val sampleCount: Int get() = synchronized(lock) { used }

    @SuppressLint("MissingPermission") // Permission wird vor dem Start in der UI angefragt
    fun start(): Boolean {
        if (running) return true
        val ctx = context
        val device = ctx?.let { preferredDevice(it) }
        val source = MediaRecorder.AudioSource.MIC
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = try {
            AudioRecord(
                source, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, SAMPLE_RATE) // mindestens 1 s Puffer
            )
        } catch (e: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        if (device != null) {
            // Bluetooth-Headsets liefern ihr Mikrofon nur über den
            // Kommunikationspfad; kabelgebundene und USB-Mikrofone direkt.
            if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && Build.VERSION.SDK_INT >= 31) {
                commDeviceSet = runCatching { audioManager(ctx).setCommunicationDevice(device) }.getOrDefault(false)
            }
            rec.preferredDevice = device
        }
        if (ctx != null && Settings.ducking(ctx)) requestFocus(ctx)
        val cue = ctx != null && Settings.audioFeedback(ctx)
        if (cue) Cues.start()

        record = rec
        synchronized(lock) {
            buffer = ShortArray(if (keepInMemory) SAMPLE_RATE else 0)
            used = 0
        }
        muteUntil = if (cue) SAMPLE_RATE * 15 / 100 else 0
        rec.startRecording()
        running = true
        thread = Thread {
            val chunk = ShortArray(2048)
            var seen = 0
            while (running) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n > 0) {
                    if (seen < muteUntil) {
                        val mute = minOf(n, muteUntil - seen)
                        java.util.Arrays.fill(chunk, 0, mute, 0)
                    }
                    seen += n
                    if (keepInMemory) append(chunk, n)
                    onChunk?.invoke(chunk, n)
                    level = rms(chunk, n)
                }
            }
        }.also { it.start() }
        return true
    }

    private fun append(chunk: ShortArray, n: Int) = synchronized(lock) {
        if (used + n > buffer.size) {
            val grown = ShortArray(maxOf(buffer.size * 2, used + n))
            System.arraycopy(buffer, 0, grown, 0, used)
            buffer = grown
        }
        System.arraycopy(chunk, 0, buffer, used, n)
        used += n
    }

    private fun rms(chunk: ShortArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) sum += chunk[i].toDouble() * chunk[i]
        // Sprache liegt meist weit unter Vollaussteuerung — mal 4, damit die
        // Anzeige sichtbar ausschlägt.
        return (kotlin.math.sqrt(sum / n) / 32768.0 * 4).toFloat().coerceIn(0f, 1f)
    }

    /// Kopie der Samples [from, to) als Floats in [-1, 1] — während der Aufnahme
    /// abrufbar (Live-Transkription).
    override fun snapshot(from: Int, to: Int): FloatArray = synchronized(lock) {
        val end = if (to < 0) used else minOf(to, used)
        val start = from.coerceIn(0, end)
        FloatArray(end - start) { buffer[start + it] / 32768.0f }
    }

    /// Stoppt die Aufnahme und liefert die Samples normiert auf [-1, 1].
    /// [cue] = false: ohne Stopp-Ton (Abbrechen, Verwerfen).
    fun stop(cue: Boolean = true): FloatArray {
        val wasRunning = running
        running = false
        level = 0f
        try { thread?.join(1000) } catch (_: InterruptedException) {}
        thread = null
        record?.let {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        record = null
        context?.let { ctx ->
            abandonFocus(ctx)
            if (commDeviceSet && Build.VERSION.SDK_INT >= 31) runCatching { audioManager(ctx).clearCommunicationDevice() }
            commDeviceSet = false
            if (wasRunning && cue && Settings.audioFeedback(ctx)) Cues.stop()
        }
        return snapshot(0, -1)
    }

    private fun audioManager(ctx: Context) = ctx.getSystemService(AudioManager::class.java)

    private fun requestFocus(ctx: Context) {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        if (runCatching { audioManager(ctx).requestAudioFocus(req) }.isSuccess) focusRequest = req
    }

    private fun abandonFocus(ctx: Context) {
        focusRequest?.let { runCatching { audioManager(ctx).abandonAudioFocusRequest(it) } }
        focusRequest = null
    }

    companion object {
        const val SAMPLE_RATE = 16000

        /// Eingabegeräte zur Auswahl: eingebautes Mikrofon, Headset, USB, Bluetooth.
        fun inputDevices(context: Context): List<AudioDeviceInfo> =
            context.getSystemService(AudioManager::class.java)
                .getDevices(AudioManager.GET_DEVICES_INPUTS)
                .filter { it.type in SUPPORTED_TYPES }
                .distinctBy { it.id }

        private val SUPPORTED_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        )

        fun deviceLabel(d: AudioDeviceInfo): String {
            val kind = when (d.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Eingebautes Mikrofon"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Kabel-Headset"
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
                else -> "Mikrofon"
            }
            val name = d.productName?.toString()?.takeIf { it.isNotBlank() && it != Build.MODEL }
            return if (name != null && d.type != AudioDeviceInfo.TYPE_BUILTIN_MIC) "$kind · $name" else kind
        }

        private fun preferredDevice(context: Context): AudioDeviceInfo? {
            val id = Settings.micDevice(context)
            if (id == 0) return null
            return runCatching { inputDevices(context).firstOrNull { it.id == id } }.getOrNull()
        }
    }
}

/// Kurze Signaltöne für Aufnahmestart, -ende und Fehler.
object Cues {
    private val tones by lazy { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60) }.getOrNull() }

    fun start() { tones?.startTone(ToneGenerator.TONE_PROP_BEEP, 90) }
    fun stop() { tones?.startTone(ToneGenerator.TONE_PROP_ACK, 120) }
    fun error() { tones?.startTone(ToneGenerator.TONE_PROP_NACK, 200) }

    fun error(context: Context) { if (Settings.audioFeedback(context)) error() }
}
