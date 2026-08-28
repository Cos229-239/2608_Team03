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
 * account, and by tests so they never hit the network.
 *
 * It deliberately does NOT invent family speech. It used to emit four lines of plausible
 * dialogue ("My father never ate before grace. Not once.") at 0.92 confidence, written to
 * the same table and rendered in the same type as a real transcript, with nothing in the
 * data marking them as machine-authored fiction. In an archive whose entire claim is that
 * a voice is never synthesized, a stranger's invented sentences sitting under someone's
 * recording of their grandmother is the worst thing this app could do, and it would have
 * been the first thing a reviewer saw after recording themselves.
 *
 * It also no longer fabricates timings. The old version emitted fixed twelve-second steps
 * regardless of the audio, so a four second recording produced lines at 0s, 12s, 24s and
 * 36s, every one of them past the end of the file, and tapping a timestamp seeked into
 * nothing. One segment, no invented clock.
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

        return Result.success(
            TranscriptionResult(
                segments = listOf(
                    TranscriptSegment(
                        assetId = audio.nameWithoutExtension,
                        startMs = 0L,
                        endMs = 0L,
                        text = PLACEHOLDER,
                        // Zero, not 0.92. A confidence score is a measurement, and
                        // nothing here measured anything.
                        confidence = 0f
                    )
                ),
                language = languageHint ?: "en-US",
                provider = "placeholder",
                modelVersion = "0"
            )
        )
    }

    private companion object {
        const val PLACEHOLDER =
            "This recording has not been transcribed yet. The words are still only in the audio."
    }
}
