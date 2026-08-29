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
