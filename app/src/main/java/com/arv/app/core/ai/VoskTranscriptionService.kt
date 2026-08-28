package com.arv.app.core.ai

import com.arv.app.core.audio.AacToPcm
import com.arv.app.core.model.TranscriptSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Real speech-to-text, running entirely on the phone.
 *
 * This is the implementation the interface was written for. Nothing about a family's audio
 * crosses the network: the .m4a is decoded locally by [AacToPcm], fed to a local acoustic
 * model, and the words come back with real timings taken from the recognizer rather than
 * invented.
 *
 * Those timings are the point. The transcript exists so someone can find the moment they
 * want to hear and tap into it, which only works if the number beside a line is where that
 * line actually is in the audio.
 */
class VoskTranscriptionService(
    private val modelStore: VoskModelStore
) : TranscriptionService {

    override suspend fun transcribe(
        audio: File,
        languageHint: String?,
        onProgress: (Float) -> Unit
    ): Result<TranscriptionResult> = withContext(Dispatchers.Default) {
        val modelPath = modelStore.path()
            ?: return@withContext Result.failure(
                ModelNotReady("Speech model is not downloaded yet")
            )

        runCatching {
            val heard = mutableListOf<TranscriptSegment>()
            val assetId = audio.nameWithoutExtension

            // Loudness over time, sampled as the audio goes past. Used afterwards to throw
            // away whatever the recognizer produced from stretches that were not speech.
            val levels = mutableListOf<Level>()
            var samplesSeen = 0L

            Model(modelPath).use { model ->
                Recognizer(model, AacToPcm.TARGET_RATE.toFloat()).use { recognizer ->
                    // Word-level timings, which is what makes a transcript line a door
                    // into the audio rather than a paragraph of text.
                    recognizer.setWords(true)

                    AacToPcm.decode(
                        file = audio,
                        // Decoding is the long part; leave the last sliver for the tail.
                        onProgress = { onProgress(it * 0.95f) }
                    ) { pcm, count ->
                        if (!coroutineIsActive()) throw CancellationException()

                        val startMs = samplesSeen * 1000 / AacToPcm.TARGET_RATE
                        samplesSeen += count
                        val endMs = samplesSeen * 1000 / AacToPcm.TARGET_RATE
                        levels += Level(startMs, endMs, rms(pcm, count))

                        val bytes = ByteBuffer
                            .allocate(count * 2)
                            .order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until count) bytes.putShort(pcm[i])

                        if (recognizer.acceptWaveForm(bytes.array(), count * 2)) {
                            parseSegment(assetId, recognizer.result)?.let(heard::add)
                        }
                    }

                    parseSegment(assetId, recognizer.finalResult)?.let(heard::add)
                }
            }

            onProgress(1f)
            TranscriptionResult(
                segments = keepOnlySpeech(heard, levels),
                language = languageHint ?: "en-US",
                provider = PROVIDER,
                modelVersion = MODEL_VERSION
            )
        }
    }

    /**
     * Turns one recognizer result into a segment, or null when the result is empty.
     *
     * Silence produces `{"text": ""}` constantly during a long pause. Those are dropped
     * rather than stored, because a transcript full of blank lines with timestamps is a
     * worse artifact than a shorter honest one.
     */
    private fun parseSegment(assetId: String, json: String): TranscriptSegment? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val text = obj.optString("text").trim()
        if (text.isEmpty()) return null

        val words = obj.optJSONArray("result")
        val startMs: Long
        val endMs: Long
        var confidence = 0f

        if (words != null && words.length() > 0) {
            startMs = (words.getJSONObject(0).optDouble("start", 0.0) * 1000).toLong()
            endMs = (words.getJSONObject(words.length() - 1).optDouble("end", 0.0) * 1000)
                .toLong()
            var sum = 0.0
            for (i in 0 until words.length()) {
                sum += words.getJSONObject(i).optDouble("conf", 0.0)
            }
            confidence = (sum / words.length()).toFloat()
        } else {
            // No word array means no timings. Say zero rather than guess a position,
            // because a wrong timestamp sends someone to the wrong moment and quietly
            // teaches them the transcript cannot be trusted.
            startMs = 0L
            endMs = 0L
        }

        return TranscriptSegment(
            assetId = assetId,
            startMs = startMs,
            endMs = endMs,
            text = text,
            confidence = confidence
        )
    }

    /** Loudness of one decoded chunk, and the stretch of the recording it covers. */
    private class Level(val startMs: Long, val endMs: Long, val rms: Double)

    private fun rms(pcm: ShortArray, count: Int): Double {
        if (count <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until count) {
            val v = pcm[i].toDouble()
            sum += v * v
        }
        return kotlin.math.sqrt(sum / count)
    }

    /**
     * Throws away anything the recognizer produced from audio that was not speech.
     *
     * A recognizer never says "that was just the room". Handed a quiet hiss it returns its
     * best guess at words, and on a device with any mic noise at all that means confident
     * looking sentences nobody said, written into a family archive underneath a real
     * recording. That is the same failure as a fabricated transcript; it just arrives from
     * the other direction, and it is worse, because it looks like a transcription of
     * something.
     *
     * The gate is relative, not a fixed decibel number, because rooms differ and so do
     * phones. The recording's own quiet stretches define its noise floor, and audio has to
     * stand clearly above that floor to count as somebody talking. A recording that is
     * nothing but noise has a floor equal to itself, so nothing clears it and the honest
     * answer, no transcript, is what comes out.
     */
    private fun keepOnlySpeech(
        segments: List<TranscriptSegment>,
        levels: List<Level>
    ): List<TranscriptSegment> {
        if (segments.isEmpty() || levels.isEmpty()) return segments

        val sorted = levels.map { it.rms }.sorted()
        val floor = sorted[(sorted.size * NOISE_FLOOR_PERCENTILE).toInt()
            .coerceIn(0, sorted.size - 1)]

        // An entirely silent file has a floor of zero; give it an absolute backstop so a
        // multiply by zero does not let everything through.
        val threshold = maxOf(floor * SPEECH_OVER_NOISE, MIN_ABSOLUTE_RMS)

        return segments.filter { segment ->
            val window = levels.filter { it.endMs > segment.startMs && it.startMs < segment.endMs }
            if (window.isEmpty()) return@filter false
            // Loudest part of the span, not the average. Real speech has pauses inside it,
            // and averaging them in would quietly discard genuine quiet talkers, which is
            // exactly who this app is for.
            val peak = window.maxOf { it.rms }
            peak >= threshold && segment.confidence >= MIN_CONFIDENCE
        }
    }

    /** Raised when transcription is asked for before the one-time model setup has run. */
    class ModelNotReady(message: String) : Exception(message)

    private companion object {
        const val PROVIDER = "vosk"
        const val MODEL_VERSION = "vosk-model-small-en-us-0.15"

        /** The quietest fifth of a recording is taken to be its room tone. */
        const val NOISE_FLOOR_PERCENTILE = 0.2

        /** About 10 dB over room tone before something counts as a voice. */
        const val SPEECH_OVER_NOISE = 3.0

        /** Roughly -52 dBFS. Below this nothing is speech on any device. */
        const val MIN_ABSOLUTE_RMS = 80.0

        /**
         * Deliberately low. The loudness gate is doing the real work; this only removes
         * the cases where the recognizer itself signalled that it was guessing.
         */
        const val MIN_CONFIDENCE = 0.5f
    }
}

/**
 * [AacToPcm.decode] is a blocking callback loop, so the usual `isActive` is out of reach
 * inside it. This keeps cancellation checkable from within the callback.
 */
private fun coroutineIsActive(): Boolean = !Thread.currentThread().isInterrupted
