package dev.whisper.transcribe

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/// Nimmt Mikrofonaudio als 16-kHz-Mono-PCM auf, wie es whisper.cpp erwartet.
class AudioRecorder {

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    private var buffer = ShortArray(SAMPLE_RATE) // 1 s Startkapazität, wächst dynamisch

    val isRecording: Boolean get() = running

    @SuppressLint("MissingPermission") // Permission wird vor dem Start in der UI angefragt
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, SAMPLE_RATE) // mindestens 1 s Puffer
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        record = rec
        buffer = ShortArray(SAMPLE_RATE)
        used = 0
        rec.startRecording()
        running = true
        thread = Thread {
            val chunk = ShortArray(2048)
            while (running) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n > 0) append(chunk, n)
            }
        }.also { it.start() }
        return true
    }

    private var used = 0

    private fun append(chunk: ShortArray, n: Int) {
        if (used + n > buffer.size) {
            val grown = ShortArray(buffer.size * 2)
            System.arraycopy(buffer, 0, grown, 0, used)
            buffer = grown
        }
        System.arraycopy(chunk, 0, buffer, used, n)
        used += n
    }

    /// Stoppt die Aufnahme und liefert die Samples normiert auf [-1, 1].
    fun stop(): FloatArray {
        running = false
        try { thread?.join(1000) } catch (_: InterruptedException) {}
        thread = null
        record?.let {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        record = null
        synchronized(buffer) {
            val out = FloatArray(used)
            for (i in 0 until used) out[i] = buffer[i] / 32768.0f
            return out
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000
    }
}
