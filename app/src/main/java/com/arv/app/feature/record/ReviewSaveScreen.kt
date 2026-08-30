package com.arv.app.feature.record

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.flow.flow
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
    val eraError: String? = null,
    val place: String = "",
    val tagText: String = "",
    val visibility: Visibility = Visibility.FAMILY,
    val aiUsePolicy: AiUsePolicy = AiUsePolicy.SUMMARY_OK,
    val area: ArchiveArea = ArchiveArea.STORIES,
    val saving: Boolean = false,
    val savedStoryId: String? = null,
    /** Which ancestor's line, when visibility is BRANCH. */
    val branchRootPersonId: String? = null,
    /** Set when a save failed. The recording itself is never at risk from this path. */
    val error: String? = null
) {
    /**
     * BRANCH with no line named would store a story that fails closed for everyone,
     * including the person who just recorded it. Block the save rather than lose it.
     */
    val canSave: Boolean
        get() =
            !saving &&
                    eraError == null &&
                    !(visibility == Visibility.BRANCH && branchRootPersonId == null)
}

class ReviewSaveViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.storyRepository(app)
    private val familyId = ServiceLocator.familyId

    private val _state = MutableStateFlow(ReviewSaveUiState())
    val state: StateFlow<ReviewSaveUiState> = _state.asStateFlow()

    val people: StateFlow<List<Person>> = repo.observePeople(familyId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Ancestors this person could scope a memory to. Empty until the tree knows a parent,
     * and the BRANCH option stays hidden while it is empty.
     */
    val branchChoices: StateFlow<List<Person>> =
        flow { emit(repo.branchChoicesFor(familyId, ServiceLocator.userId)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onBranchRoot(personId: String) {
        _state.value = _state.value.copy(
            visibility = Visibility.BRANCH,
            branchRootPersonId = personId
        )
    }

    fun onTitle(v: String) { _state.value = _state.value.copy(title = v) }
    fun onEra(v: String) {
        val trimmed = v.trim()

        if (trimmed.isEmpty()) {
            _state.value = _state.value.copy(
                eraText = v,
                eraUnknown = false,
                eraError = null
            )
            return
        }

        val yearPattern = Regex("""\d{4}""")
        val years = yearPattern
            .findAll(trimmed)
            .map { it.value.toInt() }
            .toList()

        val validFormat = when {
            trimmed.matches(Regex("""\d{4}""")) -> true
            trimmed.matches(Regex("""\d{4}\s*(to|-|–)\s*\d{4}""")) -> true
            else -> false
        }

        val currentYear = java.time.Year.now().value

        val error = when {
            !validFormat ->
                "Enter a valid year, such as 1953, or a range such as 1953 to 1964."

            years.any { it > currentYear } ->
                "The year cannot be in the future."

            else -> null
        }

        _state.value = _state.value.copy(
            eraText = v,
            eraUnknown = false,
            eraError = error
        )
    }
    fun onPlace(v: String) { _state.value = _state.value.copy(place = v) }
    fun onTags(v: String) { _state.value = _state.value.copy(tagText = v) }
    fun onVisibility(v: Visibility) {
        // Changing away from BRANCH forgets the line, so a story cannot keep a scope its
        // owner backed out of.
        _state.value = _state.value.copy(
            visibility = v,
            branchRootPersonId = if (v == Visibility.BRANCH) _state.value.branchRootPersonId else null
        )
    }
    fun onAiUsePolicy(v: AiUsePolicy) { _state.value = _state.value.copy(aiUsePolicy = v) }

    fun toggleEraUnknown() {
        val s = _state.value
        _state.value = s.copy(
            eraUnknown = !s.eraUnknown,
            eraText = if (!s.eraUnknown) "" else s.eraText,
            eraError = null
        )
    }

    fun toggleNarrator(personId: String) {
        val current = _state.value.narratorIds
        _state.value = _state.value.copy(
            narratorIds = if (personId in current) current - personId else current + personId
        )
    }

    // One parser for save and edit both, so the same text can never mean different
    // years depending on which screen it was typed into.
    private fun parseEra(text: String): Triple<Int?, Int?, EraPrecision> {
        val era = com.arv.app.core.data.EraText.parse(text)
        return Triple(era.start, era.end, era.precision)
    }

    fun save(localAudioPath: String, durationMs: Long, nowMillis: Long) {
        val s = _state.value
        if (s.saving) return

        if (!s.eraUnknown) {
            val trimmedEra = s.eraText.trim()

            val validFormat =
                trimmedEra.matches(Regex("""\d{4}""")) ||
                        trimmedEra.matches(Regex("""\d{4}\s*(to|-|–)\s*\d{4}"""))

            if (!validFormat) {
                _state.value = s.copy(
                    eraError = "Enter a valid year, such as 1953, or a range such as 1953 to 1964."
                )
                return
            }

            val years = Regex("""\d{4}""")
                .findAll(trimmedEra)
                .map { it.value.toInt() }
                .toList()

            val currentYear = java.time.Year.now().value

            if (years.any { it > currentYear }) {
                _state.value = s.copy(
                    eraError = "The year cannot be in the future."
                )
                return
            }
        }

        _state.value = s.copy(saving = true)

        val (start, end, precision) =
            if (s.eraUnknown) Triple(null, null, EraPrecision.UNKNOWN) else parseEra(s.eraText)

        viewModelScope.launch {
            val id = try {
                repo.saveRecording(
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
                    branchRootPersonId = s.branchRootPersonId,
                    now = nowMillis
                )
            } catch (t: Throwable) {
                // Without this the coroutine dies, saving stays true, and the button is
                // dead for good while someone is holding an unsaved interview. The audio
                // file is untouched on disk, so say that and let them try again.
                _state.value = _state.value.copy(
                    saving = false,
                    error = "Could not save this story. The recording is still on this phone. Try again."
                )
                return@launch
            }

            // Transcription is started on a scope that outlives this screen, and started
            // BEFORE the navigation trigger below. Setting savedStoryId fires the
            // LaunchedEffect that navigates, which pops this back stack entry and cancels
            // viewModelScope; anything launched there would be killed within milliseconds
            // of starting, leaving every saved story on "Transcribing" forever.
            // No model installed means the story simply stays PENDING; Settings kicks
            // every pending story through the real model the moment it is downloaded.
            ServiceLocator.transcriptionService(getApplication())?.let { service ->
                ServiceLocator.appScope.launch {
                    runCatching { repo.transcribeStory(id, service) }
                }
            }

            _state.value = _state.value.copy(saving = false, savedStoryId = id)
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
    val branchChoices by viewModel.branchChoices.collectAsStateWithLifecycle()

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

                        // Seeking happens on release, not on every touch sample. Seeking
                        // while dragging starts audible playback the moment you touch the
                        // bar to check the length, and each seek on an inactive draft
                        // builds a MediaPlayer and calls prepare() on the main thread,
                        // so the thumb also fights the position ticker.
                        var scrub by remember { mutableStateOf<Float?>(null) }
                        Slider(
                            value = scrub
                                ?: if (totalMs > 0L) positionMs.toFloat() / totalMs else 0f,
                            onValueChange = { scrub = it },
                            onValueChangeFinished = {
                                scrub?.let { fraction ->
                                    ServiceLocator.playback.seekTo(
                                        DRAFT_PLAYBACK_KEY,
                                        localAudioPath,
                                        (fraction * totalMs).toLong()
                                    )
                                }
                                scrub = null
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
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.eraText,
                    onValueChange = viewModel::onEra,
                    enabled = !state.eraUnknown,
                    isError = state.eraError != null,
                    label = { Text("Year or range") },
                    placeholder = { Text("1958 to 1964") },
                    modifier = Modifier.weight(1f)
                )
            }

            state.eraError?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
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
                // Free text on purpose: "summer 1953" is how a family answers. But the
                // parse happens silently at save, so a typo like "-953" or "!!!" quietly
                // became an undated story with nobody told. The field now says what it
                // will actually do while there is still time to fix it.
                val noYear = state.eraText.isNotBlank() &&
                    !Regex("\\d{4}").containsMatchIn(state.eraText)
                OutlinedTextField(
                    value = state.eraText,
                    onValueChange = viewModel::onEra,
                    enabled = !state.eraUnknown,
                    isError = noYear,
                    label = { Text("Year or range") },
                    placeholder = { Text("1958 to 1964") },
                    supportingText = if (noYear) {
                        { Text("No year found here. It will save as undated unless one is added, like 1958.") }
                    } else null,
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
                if (branchChoices.isNotEmpty()) {
                    VisibilityChip(state, viewModel, Visibility.BRANCH, "One side")
                }
                // "My branch" is deliberately absent until branch roots actually exist.
                //
                // Visibility.BRANCH is real and MemoryAccess handles it correctly, but a
                // story carries no branch root yet (Story.branchRootPersonIdOrNull is a
                // stub returning null) and no Viewer has one either, so canRead's BRANCH
                // arm is false for everyone. Unlike PRIVATE and SELECTED that arm has no
                // creator escape, which means choosing this chip would hide a recording
                // from the whole family AND from the person who just made it, silently
                // and permanently. Offering an option that can only lose someone's
                // interview is worse than offering fewer options.
                //
                // Restore this the same commit the branch root column lands, not before.
                VisibilityChip(state, viewModel, Visibility.PRIVATE, "Only me")
            }
        }

        if (state.visibility == Visibility.BRANCH) {
            item {
                Text(
                    "Whose line?",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    branchChoices.forEach { ancestor ->
                        FilterChip(
                            selected = state.branchRootPersonId == ancestor.personId,
                            onClick = { viewModel.onBranchRoot(ancestor.personId) },
                            label = { Text(ancestor.displayName) }
                        )
                    }
                }
            }
            item {
                Text(
                    if (state.branchRootPersonId == null) {
                        // Saving now would store a branch nobody can resolve, and BRANCH
                        // fails closed, so the recording would be readable by no one.
                        "Pick whose line this belongs to, or it will not be readable."
                    } else {
                        "Anyone descended from them can hear this. Nobody else in the family can."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.branchRootPersonId == null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
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

        state.error?.let { message ->
            item {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        item {
            Button(
                onClick = { viewModel.save(localAudioPath, durationMs, nowMillis) },
                enabled = state.canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
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
