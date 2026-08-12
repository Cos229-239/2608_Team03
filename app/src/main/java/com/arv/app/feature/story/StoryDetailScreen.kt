package com.arv.app.feature.story

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arv.app.core.data.local.TranscriptSegmentEntity
import com.arv.app.core.di.ServiceLocator
import com.arv.app.core.model.Story
import com.arv.app.core.model.TranscriptStatus
import com.arv.app.ui.components.formatElapsed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class StoryDetailViewModel(
    app: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(app) {

    private val storyId: String = savedStateHandle["storyId"] ?: ""
    private val repo = ServiceLocator.storyRepository(app)

    val story: StateFlow<Story?> = repo.observeById(storyId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** AI-4: the real transcript, live from Room, keyed off the story's audio asset. */
    val segments: StateFlow<List<TranscriptSegmentEntity>> =
        flow { emit(repo.primaryAsset(storyId)?.assetId) }
            .flatMapLatest { assetId ->
                if (assetId == null) flowOf(emptyList())
                else repo.observeTranscript(assetId)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The correction loop. The DAO keeps the machine's original text alongside the fix,
     * so provenance survives the edit.
     */
    fun correct(segmentId: Long, newText: String) {
        if (newText.isBlank()) return
        viewModelScope.launch { repo.correctSegment(segmentId, newText.trim()) }
    }
}

/**
 * Screen 07. Transcript and audio are one object, not two tabs. The reason to read a
 * transcript is to find the moment you want to hear. Tap a line to fix it; the machine's
 * original text is kept underneath every human correction.
 */
@Composable
fun StoryDetailScreen(
    modifier: Modifier = Modifier,
    viewModel: StoryDetailViewModel = viewModel()
) {
    val story by viewModel.story.collectAsStateWithLifecycle()
    val segments by viewModel.segments.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                story?.title ?: "Loading",
                style = MaterialTheme.typography.headlineMedium
            )
        }
        item {
            Text(
                buildString {
                    append(story?.eraLabel ?: "")
                    story?.placeLabel?.let { append("  ·  $it") }
                    story?.durationMs?.takeIf { it > 0 }?.let {
                        append("  ·  ${formatElapsed(it)}")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        story?.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEach { tag ->
                        AssistChip(onClick = {}, label = { Text(tag) })
                    }
                }
            }
        }

        item { HorizontalDivider() }

        when (story?.transcriptStatus) {
            TranscriptStatus.PENDING, TranscriptStatus.RUNNING -> item {
                Text(
                    "Transcribing. This usually takes a couple of minutes and finishes even if you leave.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TranscriptStatus.FAILED -> item {
                Text(
                    "Transcription failed. The recording is safe. Only the text is missing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            TranscriptStatus.NONE -> item {
                Text(
                    "No transcript for this item.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> Unit
        }

        items(segments, key = { it.id }) { segment ->
            SegmentRow(
                segment = segment,
                onCorrect = { newText -> viewModel.correct(segment.id, newText) }
            )
        }
    }
}

@Composable
private fun SegmentRow(
    segment: TranscriptSegmentEntity,
    onCorrect: (String) -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(segment.text) { mutableStateOf(segment.text) }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            formatElapsed(segment.startMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp)
        )
        Column(Modifier.fillMaxWidth()) {
            if (editing) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        onCorrect(draft)
                        editing = false
                    }) { Text("Save fix") }
                    OutlinedButton(onClick = {
                        draft = segment.text
                        editing = false
                    }) { Text("Cancel") }
                }
            } else {
                Text(
                    segment.text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.clickable { editing = true }
                )
                if (segment.humanVerified) {
                    // Brass in spirit: the mark that a person checked the machine.
                    Text(
                        "Corrected by a person",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}
