package com.construct.messenger.transcription

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder

/**
 * A voice note (AAC in M4A) or a video note's sound (MP4) as whisper wants it: 16 kHz, mono,
 * floats in −1…1. Decoded from memory with the platform's codecs; nothing is written to disk.
 */
internal object PcmDecoder {
    const val RATE = 16_000

    /** The first audio track of [container], at [RATE] mono. Throws when there is none. */
    fun decode(container: ByteArray): FloatArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(Bytes(container))
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("no audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return drain(extractor, codec, format)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun drain(extractor: MediaExtractor, codec: MediaCodec, input: MediaFormat): FloatArray {
        var rate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val pcm = ShortList()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        while (true) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                out >= 0 -> {
                    val buffer = codec.getOutputBuffer(out)!!.order(ByteOrder.LITTLE_ENDIAN)
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val shorts = buffer.asShortBuffer()
                    while (shorts.hasRemaining()) pcm.add(shorts.get())
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        return resample(toMono(pcm.toArray(), channels), rate, RATE)
    }

    /** Interleaved 16-bit samples, averaged across [channels], as −1…1. */
    fun toMono(samples: ShortArray, channels: Int): FloatArray {
        val c = channels.coerceAtLeast(1)
        val frames = samples.size / c
        return FloatArray(frames) { f ->
            var sum = 0f
            for (ch in 0 until c) sum += samples[f * c + ch]
            sum / c / 32768f
        }
    }

    /** Linear interpolation from [from] Hz to [to] Hz — enough for speech into a model. */
    fun resample(samples: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || samples.isEmpty()) return samples
        val length = (samples.size.toLong() * to / from).toInt()
        val step = from.toDouble() / to
        return FloatArray(length) { i ->
            val pos = i * step
            val j = pos.toInt()
            val frac = (pos - j).toFloat()
            val a = samples[j]
            val b = samples[minOf(j + 1, samples.size - 1)]
            a + (b - a) * frac
        }
    }

    private class Bytes(private val bytes: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= bytes.size) return -1
            val n = minOf(size.toLong(), bytes.size - position).toInt()
            System.arraycopy(bytes, position.toInt(), buffer, offset, n)
            return n
        }

        override fun getSize(): Long = bytes.size.toLong()
        override fun close() = Unit
    }

    private class ShortList {
        private var data = ShortArray(1 shl 16)
        private var size = 0
        fun add(s: Short) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = s
        }
        fun toArray(): ShortArray = data.copyOf(size)
    }

    private const val TIMEOUT_US = 10_000L
}
