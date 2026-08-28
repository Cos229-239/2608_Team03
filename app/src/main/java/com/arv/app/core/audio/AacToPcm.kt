package com.arv.app.core.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

/**
 * Turns a recorded .m4a into the only thing a speech recognizer will accept: 16 kHz,
 * mono, signed 16-bit PCM.
 *
 * The app records 48 kHz mono AAC inside an MP4 container because that is what survives a
 * 45 minute interview on a phone. Vosk, and every other offline recognizer, wants raw
 * little-endian PCM at 16 kHz. Nothing on the platform does that conversion, so this does.
 *
 * It streams. A 45 minute interview is about 86 MB as 16 kHz PCM, which is not something to
 * hold in memory on a phone that is also running the recorder, so decoded audio is handed
 * out in chunks and never accumulated.
 */
object AacToPcm {

    const val TARGET_RATE = 16_000

    private const val TIMEOUT_US = 10_000L

    /**
     * Decodes [file] and calls [onPcm] with successive chunks of 16 kHz mono PCM.
     *
     * [onProgress] reports 0..1 based on presentation time against the container duration.
     * The callback must not retain the array; it is reused between chunks.
     *
     * @return total number of samples emitted, or throws if the file has no audio track.
     */
    fun decode(
        file: File,
        onProgress: (Float) -> Unit = {},
        onPcm: (ShortArray, Int) -> Unit
    ): Long {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                ?.startsWith("audio/") == true
        } ?: run {
            extractor.release()
            error("No audio track in ${file.name}")
        }

        val inputFormat = extractor.getTrackFormat(trackIndex)
        extractor.selectTrack(trackIndex)

        val durationUs = runCatching { inputFormat.getLong(MediaFormat.KEY_DURATION) }
            .getOrDefault(0L)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inputFormat, null, null, 0)
        codec.start()

        // Set from the decoder's OUTPUT format, not the input format. They can differ,
        // and the output is the one whose bytes we are about to interpret.
        var sourceRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        // Carried across chunks so resampling does not restart its phase at every buffer,
        // which would put a click at every boundary.
        var resamplePos = 0.0
        var carry: Short? = null
        var emitted = 0L

        val info = MediaCodec.BufferInfo()
        var sawInputEnd = false
        var sawOutputEnd = false

        try {
            while (!sawOutputEnd) {
                if (!sawInputEnd) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEnd = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        sourceRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> {
                        if (outIndex >= 0) {
                            if (info.size > 0) {
                                val out = codec.getOutputBuffer(outIndex)!!
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                val shorts = out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

                                val frames = ShortArray(shorts.remaining())
                                shorts.get(frames)

                                val mono = toMono(frames, channels)
                                val resampled = resample(
                                    mono, sourceRate, resamplePos, carry
                                )
                                resamplePos = resampled.nextPos
                                carry = resampled.carry

                                if (resampled.count > 0) {
                                    onPcm(resampled.data, resampled.count)
                                    emitted += resampled.count
                                }
                            }

                            codec.releaseOutputBuffer(outIndex, false)

                            if (durationUs > 0) {
                                onProgress(
                                    (info.presentationTimeUs.toFloat() / durationUs)
                                        .coerceIn(0f, 1f)
                                )
                            }

                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawOutputEnd = true
                            }
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }

        onProgress(1f)
        return emitted
    }

    /** Averages interleaved channels down to one. Mono input passes straight through. */
    private fun toMono(interleaved: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        val out = ShortArray(frames)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += interleaved[f * channels + c]
            out[f] = (sum / channels).toShort()
        }
        return out
    }

    private class Resampled(
        val data: ShortArray,
        val count: Int,
        val nextPos: Double,
        val carry: Short?
    )

    /**
     * Linear interpolation down to [TARGET_RATE].
     *
     * [pos] and [carry] thread the fractional read position and the final sample of the
     * previous chunk through the call, so a chunk boundary is not a discontinuity. Without
     * that, every decoder buffer would start a fresh interpolation and leave a periodic
     * click that a recognizer reads as noise.
     */
    private fun resample(
        input: ShortArray,
        sourceRate: Int,
        pos: Double,
        carry: Short?
    ): Resampled {
        if (input.isEmpty()) return Resampled(ShortArray(0), 0, pos, carry)
        if (sourceRate == TARGET_RATE) {
            return Resampled(input, input.size, 0.0, input.lastOrNull())
        }

        val step = sourceRate.toDouble() / TARGET_RATE
        // Index -1 refers to the carried last sample of the previous chunk.
        fun sampleAt(i: Int): Short = when {
            i < 0 -> carry ?: input[0]
            i >= input.size -> input[input.size - 1]
            else -> input[i]
        }

        val estimate = ((input.size - pos) / step).toInt() + 2
        val out = ShortArray(if (estimate > 0) estimate else 1)
        var n = 0
        var p = pos

        while (p < input.size) {
            val i = kotlin.math.floor(p).toInt()
            val frac = p - i
            val a = sampleAt(i)
            val b = sampleAt(i + 1)
            val v = a + (b - a) * frac
            if (n < out.size) {
                out[n++] = v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
            p += step
        }

        return Resampled(out, n, p - input.size, input.last())
    }
}
