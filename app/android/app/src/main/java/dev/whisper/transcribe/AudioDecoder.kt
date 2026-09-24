package dev.whisper.transcribe

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/// Dekodiert beliebige Audio-/Videodateien (MP3, M4A, OGG, WAV, MP4, ...)
/// über MediaExtractor/MediaCodec zu 16-kHz-Mono-Floats für whisper.cpp.
object AudioDecoder {

    /// Wie oft der Decoder nach dem Eingabe-Ende hintereinander nichts liefern
    /// darf (je 10 ms), bevor wir aufgeben. Manche Hersteller-Decoder melden
    /// das Ausgabe-Ende nie — ohne Grenze hinge die Schleife für immer.
    private const val MAX_IDLE_AFTER_EOS = 300

    /// [onProgress] bekommt den Anteil 0..1 der bereits gelesenen Datei.
    fun decode(context: Context, uri: Uri, onProgress: (Float) -> Unit = {}): FloatArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("Keine Audiospur gefunden")
            extractor.selectTrack(track)

            val inFormat = extractor.getTrackFormat(track)
            // KEY_DURATION ist ein Long — getInteger wirft darauf ClassCastException.
            val durationUs =
                if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION)
                else 0L

            val codec = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(inFormat, null, null, 0)
                codec.start()
                return drain(extractor, codec, inFormat, durationUs, onProgress)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun drain(
        extractor: MediaExtractor, codec: MediaCodec, inFormat: MediaFormat,
        durationUs: Long, onProgress: (Float) -> Unit,
    ): FloatArray {
        // Kanalzahl, Rate und Sampleformat stehen verbindlich erst im
        // Ausgabeformat des Decoders: HE-AAC etwa liefert die doppelte Rate
        // und aus Mono-Parametric-Stereo zwei Kanäle.
        var outFormat = inFormat
        var resampler: Resampler? = null
        val out = FloatSink(AudioRecorder.SAMPLE_RATE * 30)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var idle = 0
        var lastReported = -1

        while (true) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val size = extractor.readSampleData(codec.getInputBuffer(inIdx)!!, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    outFormat = codec.outputFormat
                    resampler = null
                }
                outIdx >= 0 -> {
                    idle = 0
                    val r = resampler ?: Resampler(outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE), out)
                        .also { resampler = it }
                    if (info.size > 0) appendPcm(codec.getOutputBuffer(outIdx)!!, info, outFormat, r)
                    codec.releaseOutputBuffer(outIdx, false)

                    if (durationUs > 0) {
                        val percent = (info.presentationTimeUs * 100 / durationUs).toInt().coerceIn(0, 100)
                        if (percent != lastReported) {
                            lastReported = percent
                            onProgress(percent / 100f)
                        }
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                inputDone && ++idle > MAX_IDLE_AFTER_EOS -> break
            }
        }
        return out.toArray()
    }

    private fun appendPcm(buf: ByteBuffer, info: MediaCodec.BufferInfo, format: MediaFormat, r: Resampler) {
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        buf.order(ByteOrder.nativeOrder())
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        val encoding =
            if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            else AudioFormat.ENCODING_PCM_16BIT

        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = buf.asFloatBuffer()
                repeat(fb.remaining() / channels) {
                    var acc = 0f
                    repeat(channels) { acc += fb.get() }
                    r.push(acc / channels)
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                repeat(buf.remaining() / channels) {
                    var acc = 0
                    repeat(channels) { acc += (buf.get().toInt() and 0xFF) - 128 }
                    r.push(acc / (channels * 128f))
                }
            }
            else -> {
                val sb = buf.asShortBuffer()
                repeat(sb.remaining() / channels) {
                    var acc = 0
                    repeat(channels) { acc += sb.get() }
                    r.push(acc / (channels * 32768f))
                }
            }
        }
    }

    /// Wachsender Float-Puffer ohne Boxing. Eine ArrayList<Float> braucht rund
    /// 20 Byte je Sample — zehn Minuten 44,1-kHz-Audio also über 500 MB Heap.
    private class FloatSink(initial: Int) {
        private var data = FloatArray(initial)
        private var size = 0

        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }

    /// Rechnet fortlaufend auf 16 kHz um, ohne die Datei in Quellrate
    /// zwischenzuspeichern. Beim Herunterrechnen mittelt er jedes Zielsample
    /// über sein Quellintervall — ein einfacher Tiefpass, sonst fielen Anteile
    /// über 8 kHz als Störung ins Sprachband. Hochrechnen (8-kHz-AMR u. ä.)
    /// interpoliert linear.
    private class Resampler(fromRate: Int, private val out: FloatSink) {
        private val step = fromRate.toDouble() / AudioRecorder.SAMPLE_RATE
        private var n = -1L          // Index des zuletzt gelesenen Quellsamples

        // Herunterrechnen
        private var acc = 0f
        private var count = 0
        private var boundary = step  // Ende des Quellintervalls des nächsten Zielsamples

        // Hochrechnen
        private var prev = 0f
        private var t = 0.0          // Quellposition des nächsten Zielsamples

        fun push(x: Float) {
            n++
            when {
                step == 1.0 -> out.add(x)
                step > 1.0 -> {
                    acc += x
                    count++
                    if (n + 1 >= boundary) {
                        out.add(acc / count)
                        acc = 0f
                        count = 0
                        boundary += step
                    }
                }
                else -> {
                    if (n == 0L) prev = x
                    while (t <= n) {
                        out.add(prev + (x - prev) * (t - (n - 1)).toFloat())
                        t += step
                    }
                    prev = x
                }
            }
        }
    }
}
