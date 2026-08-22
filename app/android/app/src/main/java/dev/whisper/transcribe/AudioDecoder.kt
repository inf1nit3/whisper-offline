package dev.whisper.transcribe

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

/// Dekodiert beliebige Audio-/Videodateien (MP3, M4A, OGG, WAV, MP4, ...)
/// über MediaExtractor/MediaCodec zu 16-kHz-Mono-Floats für whisper.cpp.
object AudioDecoder {

    fun decode(context: Context, uri: Uri, onProgress: (Float) -> Unit = {}): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        require(trackIndex >= 0) { "Keine Audiospur gefunden" }
        extractor.selectTrack(trackIndex)

        val mime = format!!.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val out = ArrayList<Float>(16000 * 30)
        val info = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false
        var presentationUs: Long = -1

        while (!sawOutputEOS) {
            if (!sawInputEOS) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(inBuf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEOS = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            when (val outIdx = codec.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                else -> if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    if (presentationUs < 0) presentationUs = info.presentationTimeUs
                    appendPcm(outBuf, info, format, mime, out)
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEOS = true
                    }
                }
            }
        }
        codec.stop()
        codec.release()
        extractor.release()

        val durationUs = format.getInteger(MediaFormat.KEY_DURATION)
        if (durationUs > 0) onProgress(1f)
        return resample(out.toFloatArray(), format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
    }

    private fun appendPcm(
        buf: java.nio.ByteBuffer, info: MediaCodec.BufferInfo,
        format: MediaFormat, mime: String, out: MutableList<Float>
    ) {
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        when {
            mime == "audio/mp4a-latm" || buf.remaining() % 2 == 0 -> {
                // PCM 16-bit annehmen (Standard bei MediaCodec-Decodern)
                val shortView = buf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val frames = shortView.remaining() / channels
                for (f in 0 until frames) {
                    var acc = 0
                    for (c in 0 until channels) acc += shortView.get()
                    out.add((acc / channels) / 32768.0f)
                }
            }
            else -> {
                // PCM 8-bit Fallback
                while (buf.hasRemaining()) {
                    var acc = 0
                    for (c in 0 until channels) {
                        if (buf.hasRemaining()) acc += buf.get().toInt() and 0xFF
                    }
                    out.add(((acc / channels) - 128) / 128.0f)
                }
            }
        }
    }

    /// Einfaches lineares Resampling (für Spracherkennung völlig ausreichend).
    private fun resample(samples: FloatArray, fromRate: Int): FloatArray {
        if (fromRate == AudioRecorder.SAMPLE_RATE || samples.isEmpty()) return samples
        val ratio = fromRate / AudioRecorder.SAMPLE_RATE.toFloat()
        val outLen = (samples.size / ratio).toInt()
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val src = i * ratio
            val i0 = src.toInt()
            val i1 = minOf(i0 + 1, samples.size - 1)
            val frac = src - i0
            out[i] = samples[i0] * (1 - frac) + samples[i1] * frac
        }
        return out
    }
}
