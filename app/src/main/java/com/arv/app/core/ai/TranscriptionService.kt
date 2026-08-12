package com.arv.app.core.ai

import com.arv.app.core.model.TranscriptSegment
import kotlinx.coroutines.delay
import java.io.File

/**
 * The only thing the app knows about speech-to-text.
 *
 * Nothing outside this package may reference a provider by name. Transcription is the
 * single largest cost and vendor risk in the project (docs/PITCH.md slide 8), so it stays
 * swappable: if a provider's pricing or quota changes mid-term, we replace one class.
 */
interface TranscriptionService {

    /**
     * @param audio the local file. Always local. We transcribe from the phone's copy,
     *              never from a remote path, so this works before the upload finishes.
     * @param languageHint BCP-47 tag, or null to let the provider detect.
     */
    suspend fun transcribe(
        audio: File,
        languageHint: String? = null,
        onProgress: (Float) -> Unit = {}
    ): Result<TranscriptionResult>
}

data class TranscriptionResult(
    val segments: List<TranscriptSegment>,
    val language: String,
    val provider: String,
    val modelVersion: String
) {
    val fullText: String get() = segments.joinToString(" ") { it.text }
}

/**
 * Deterministic stand-in used by AI-1 so the rest of the team is not blocked on a provider
 * account, and by tests so they never hit the network. Timings are fabricated but
 * well-formed, which is exactly what the transcript UI needs to be built against.
 */
class FakeTranscriptionService(
    private val latencyMs: Long = 1200L
) : TranscriptionService {

    override suspend fun transcribe(
        audio: File,
        languageHint: String?,
        onProgress: (Float) -> Unit
    ): Result<TranscriptionResult> {
        val steps = 5
        repeat(steps) { i ->
            delay(latencyMs / steps)
            onProgress((i + 1) / steps.toFloat())
        }

        val assetId = audio.nameWithoutExtension
        val segments = SAMPLE_LINES.mapIndexed { index, line ->
            TranscriptSegment(
                assetId = assetId,
                startMs = index * 12_000L,
                endMs = (index + 1) * 12_000L,
                text = line,
                confidence = 0.92f
            )
        }

        return Result.success(
            TranscriptionResult(
                segments = segments,
                language = languageHint ?: "en-US",
                provider = "fake",
                modelVersion = "0"
            )
        )
    }

    private companion object {
        val SAMPLE_LINES = listOf(
            "She'd start the roast before church so the whole house smelled like it by the time we got back.",
            "My father never ate before grace. Not once. Not even the year he was sick.",
            "And there was a song she hummed. I couldn't tell you the words, it was her mother's, from back home.",
            "You want me to try it? I'll butcher it."
        )
    }
}
