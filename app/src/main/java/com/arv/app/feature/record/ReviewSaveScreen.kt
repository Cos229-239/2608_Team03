package com.arv.app.feature.record

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arv.app.core.di.ServiceLocator
import com.arv.app.core.model.AiUsePolicy
import com.arv.app.core.model.ArchiveArea
import com.arv.app.core.model.EraPrecision
import com.arv.app.core.model.Person
import com.arv.app.core.model.Visibility
import com.arv.app.ui.components.formatElapsed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Identity for the unsaved recording inside [com.arv.app.core.audio.PlaybackController].
 *  It has no storyId yet, and it must not collide with a real one. */
internal const val DRAFT_PLAYBACK_KEY = "review-draft"

data class ReviewSaveUiState(
    val title: String = "",
    val narratorIds: List<String> = emptyList(),
    val eraText: String = "",
    val eraUnknown: Boolean = false,
    val place: String = "",
    val tagText: String = "",
    val visibility: Visibility = Visibility.FAMILY,
    val aiUsePolicy: AiUsePolicy = AiUsePolicy.SUMMARY_OK,
    val area: ArchiveArea = ArchiveArea.STORIES,
    val saving: Boolean = false,
    val savedStoryId: String? = null
)

class ReviewSaveViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.storyRepository(app)
    private val familyId = ServiceLocator.familyId

    private val _state = MutableStateFlow(ReviewSaveUiState())
    val state: StateFlow<ReviewSaveUiState> = _state.asStateFlow()

    val people: StateFlow<List<Person>> = repo.observePeople(familyId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onTitle(v: String) { _state.value = _state.value.copy(title = v) }
    fun onEra(v: String) { _state.value = _state.value.copy(eraText = v, eraUnknown = false) }
    fun onPlace(v: String) { _state.value = _state.value.copy(place = v) }
    fun onTags(v: String) { _state.value = _state.value.copy(tagText = v) }
    fun onVisibility(v: Visibility) { _state.value = _state.value.copy(visibility = v) }
    fun onAiUsePolicy(v: AiUsePolicy) { _state.value = _state.value.copy(aiUsePolicy = v) }

    fun toggleEraUnknown() {
        val s = _state.value
        _state.value = s.copy(eraUnknown = !s.eraUnknown, eraText = if (!s.eraUnknown) "" else s.eraText)
    }

    fun toggleNarrator(personId: String) {
        val current = _state.value.narratorIds
        _state.value = _state.value.copy(
            narratorIds = if (personId in current) current - personId else current + personId
        )
    }

    /**
     * Accepts "1958-1964", "1958 to 1964", or "1953". Anything it cannot read becomes
     * UNKNOWN rather than a guess, because a wrong year in an archive outlives the
     * person who could have corrected it.
     */
    private fun parseEra(text: String): Triple<Int?, Int?, EraPrecision> {
        val years = Regex("\\d{4}").findAll(text).map { it.value.toInt() }.toList()
        return when {
            years.isEmpty() -> Triple(null, null, EraPrecision.UNKNOWN)
            years.size == 1 -> Triple(years[0], years[0], EraPrecision.EXACT)
            else -> Triple(years.min(), years.max(), EraPrecision.RANGE)
        }
    }

    fun save(localAudioPath: String, durationMs: Long, nowMillis: Long) {
        val s = _state.value
        if (s.saving) return
        _state.value = s.copy(saving = true)

        val (start, end, precision) =
            if (s.eraUnknown) Triple(null, null, EraPrecision.UNKNOWN) else parseEra(s.eraText)

        viewModelScope.launch {
            val id = repo.saveRecording(
                familyId = familyId,
                createdByUserId = ServiceLocator.userId,
                localAudioPath = localAudioPath,
                durationMs = durationMs,
                title = s.title,
                narratorIds = s.narratorIds,
                eraStart = start,
                eraEnd = end,
                eraPrecision = precision,
                placeLabel = s.place,
                tags = s.tagText.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                visibility = s.visibility,
                aiUsePolicy = s.aiUsePolicy,
                area = s.area,
                now = nowMillis
            )
            // Navigate immediately; transcription catches up on its own and the story
            // page flips from Transcribing to Ready when it lands.
            _state.value = _state.value.copy(saving = false, savedStoryId = id)
            repo.transcribeStory(id, ServiceLocator.transcriptionService)
        }
    }
}

/**
 * Screen 05. The metadata is captured here, seconds after the recording stops, because
 * this is the only moment anyone remembers what year it was or what the street was called.
 * Ask later and the answer is gone.
 */
@Composable
fun ReviewSaveScreen(
    localAudioPath: String,
    durationMs: Long,
    nowMillis: Long,
    onSaved: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReviewSaveViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val people by viewModel.people.collectAsStateWithLifecycle()

    state.savedStoryId?.let { id ->
        androidx.compose.runtime.LaunchedEffect(id) { onSaved(id) }
    }

    // Belongs to the screen, not to a list item. Inside the LazyColumn this disposed
    // whenever the player card scrolled out of view, which stopped playback mid-sentence.
    DisposableEffect(Unit) {
        onDispose { ServiceLocator.playback.stop() }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 48.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Keep this story", style = MaterialTheme.typography.headlineMedium)
        }

        item {
            // Nobody decides whether to keep a recording of someone by reading its length.
            // Hearing it back before saving is the point of this screen, and it was the one
            // thing missing from it.
            val playback by ServiceLocator.playback.state.collectAsStateWithLifecycle()
            val isThisDraft = playback.storyId == DRAFT_PLAYBACK_KEY
            val playable = ServiceLocator.playback.canPlay(localAudioPath)
            val positionMs = if (isThisDraft) playback.positionMs else 0L
            val totalMs =
                if (isThisDraft && playback.durationMs > 0L) playback.durationMs else durationMs

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Recorded just now", style = MaterialTheme.typography.bodyMedium)
                        Text(formatElapsed(totalMs), style = MaterialTheme.typography.bodyMedium)
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = {
                                ServiceLocator.playback.toggle(DRAFT_PLAYBACK_KEY, localAudioPath)
                            },
                            enabled = playable,
                            modifier = Modifier.size(48.dp)
                        ) {
                            val playing = isThisDraft && playback.isPlaying
                            Icon(
                                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription =
                                    if (playing) "Pause" else "Play what you just recorded"
                            )
                        }

                        Slider(
                            value = if (totalMs > 0L) positionMs.toFloat() / totalMs else 0f,
                            onValueChange = { fraction ->
                                ServiceLocator.playback.seekTo(
                                    DRAFT_PLAYBACK_KEY,
                                    localAudioPath,
                                    (fraction * totalMs).toLong()
                                )
                            },
                            enabled = playable,
                            modifier = Modifier.weight(1f)
                        )

                        Text(formatElapsed(positionMs), style = MaterialTheme.typography.bodySmall)
                    }

                    if (!playable) {
                        Text(
                            "The audio file is missing, so there is nothing to play back.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::onTitle,
                label = { Text("Title") },
                placeholder = { Text("Sunday kitchen, Bellwood Avenue") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item { SectionLabel("Whose voice is this?") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                people.forEach { person ->
                    FilterChip(
                        selected = person.personId in state.narratorIds,
                        onClick = { viewModel.toggleNarrator(person.personId) },
                        label = { Text(person.displayName) }
                    )
                }
            }
        }

        item { SectionLabel("When did this happen?") }
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.eraText,
                    onValueChange = viewModel::onEra,
                    enabled = !state.eraUnknown,
                    label = { Text("Year or range") },
                    placeholder = { Text("1958 to 1964") },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            // A first-class answer, not a fallback. See EraPrecision in the domain model.
            FilterChip(
                selected = state.eraUnknown,
                onClick = viewModel::toggleEraUnknown,
                label = { Text("I'm not sure") }
            )
        }

        item {
            OutlinedTextField(
                value = state.place,
                onValueChange = viewModel::onPlace,
                label = { Text("Where") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            OutlinedTextField(
                value = state.tagText,
                onValueChange = viewModel::onTags,
                label = { Text("Tags, comma separated") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item { HorizontalDivider() }

        item { SectionLabel("Who can see this") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VisibilityChip(state, viewModel, Visibility.FAMILY, "Whole family")
                VisibilityChip(state, viewModel, Visibility.BRANCH, "My branch")
                VisibilityChip(state, viewModel, Visibility.PRIVATE, "Only me")
            }
        }

        item { SectionLabel("What the librarian may do with it") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PolicyChip(state, viewModel, AiUsePolicy.SUMMARY_OK, "Summarize")
                PolicyChip(state, viewModel, AiUsePolicy.QUOTE_ONLY, "Quote only")
                PolicyChip(state, viewModel, AiUsePolicy.NONE, "Never use it")
            }
        }
        item {
            Text(
                "Separate from who can see it. Letting relatives read something is not the same as letting a model rewrite it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "AUTHENTIC RECORDING",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        "Captured on this phone. Marked as their real voice, and it stays marked that way for good.",
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        item {
            Button(
                onClick = { viewModel.save(localAudioPath, durationMs, nowMillis) },
                enabled = !state.saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(if (state.saving) "Saving" else "Save story")
            }
        }

        item {
            Text(
                "Saved to this phone first. It uploads when you're on Wi-Fi, and nothing is lost if you never are.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun VisibilityChip(
    state: ReviewSaveUiState,
    vm: ReviewSaveViewModel,
    value: Visibility,
    label: String
) {
    FilterChip(
        selected = state.visibility == value,
        onClick = { vm.onVisibility(value) },
        label = { Text(label) }
    )
}

@Composable
private fun PolicyChip(
    state: ReviewSaveUiState,
    vm: ReviewSaveViewModel,
    value: AiUsePolicy,
    label: String
) {
    FilterChip(
        selected = state.aiUsePolicy == value,
        onClick = { vm.onAiUsePolicy(value) },
        label = { Text(label) }
    )
}
