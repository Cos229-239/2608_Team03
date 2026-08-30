package com.arv.app.feature.story

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arv.app.core.ai.MemoryAccess
import com.arv.app.core.di.ServiceLocator
import com.arv.app.core.model.AiUsePolicy
import com.arv.app.core.model.Person
import com.arv.app.core.model.Visibility
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class EditStoryUiState(
    val loaded: Boolean = false,
    val allowed: Boolean = true,
    val title: String = "",
    val eraText: String = "",
    val eraUnknown: Boolean = false,
    val place: String = "",
    val tagText: String = "",
    val visibility: Visibility = Visibility.FAMILY,
    val branchRootPersonId: String? = null,
    val aiUsePolicy: AiUsePolicy = AiUsePolicy.SUMMARY_OK,
    val saving: Boolean = false,
    val done: Boolean = false
) {
    /** BRANCH with no line named is readable by nobody. Block that save, never make it. */
    val canSave: Boolean
        get() = !saving && !(visibility == Visibility.BRANCH && branchRootPersonId == null)
}

class EditStoryViewModel(
    app: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(app) {

    private val storyId: String = savedStateHandle["storyId"] ?: ""
    private val repo = ServiceLocator.storyRepository(app)
    private val familyId = ServiceLocator.familyId

    private val _state = MutableStateFlow(EditStoryUiState())
    val state: StateFlow<EditStoryUiState> = _state.asStateFlow()

    val branchChoices: StateFlow<List<Person>> =
        flow { emit(repo.branchChoicesFor(familyId, ServiceLocator.userId)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val story = repo.observeById(storyId).first()
            if (story == null) {
                _state.value = EditStoryUiState(loaded = true, allowed = false)
            } else {
                _state.value = EditStoryUiState(
                    loaded = true,
                    allowed = MemoryAccess.canEdit(story, ServiceLocator.viewer),
                    title = story.title,
                    eraText = when {
                        story.eraStart == null -> ""
                        story.eraEnd != null && story.eraEnd != story.eraStart ->
                            "${story.eraStart} to ${story.eraEnd}"
                        else -> "${story.eraStart}"
                    },
                    eraUnknown = story.eraStart == null,
                    place = story.placeLabel.orEmpty(),
                    tagText = story.tags.joinToString(", "),
                    visibility = story.visibility,
                    branchRootPersonId = story.branchRootPersonId,
                    aiUsePolicy = story.aiUsePolicy
                )
            }
        }
    }

    fun onTitle(v: String) = update { it.copy(title = v) }
    fun onEra(v: String) = update { it.copy(eraText = v, eraUnknown = false) }
    fun onPlace(v: String) = update { it.copy(place = v) }
    fun onTags(v: String) = update { it.copy(tagText = v) }
    fun onAiUsePolicy(v: AiUsePolicy) = update { it.copy(aiUsePolicy = v) }
    fun onBranchRoot(id: String) =
        update { it.copy(visibility = Visibility.BRANCH, branchRootPersonId = id) }

    fun onVisibility(v: Visibility) = update {
        it.copy(
            visibility = v,
            branchRootPersonId = if (v == Visibility.BRANCH) it.branchRootPersonId else null
        )
    }

    fun toggleEraUnknown() = update {
        it.copy(eraUnknown = !it.eraUnknown, eraText = if (!it.eraUnknown) "" else it.eraText)
    }

    private fun update(block: (EditStoryUiState) -> EditStoryUiState) {
        _state.value = block(_state.value)
    }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        _state.value = s.copy(saving = true)
        viewModelScope.launch {
            val ok = repo.updateStoryDetails(
                storyId = storyId,
                viewer = ServiceLocator.viewer,
                title = s.title,
                eraText = s.eraText,
                eraUnknown = s.eraUnknown,
                placeLabel = s.place,
                tags = s.tagText.split(","),
                visibility = s.visibility,
                branchRootPersonId = s.branchRootPersonId,
                aiUsePolicy = s.aiUsePolicy,
                nowMillis = System.currentTimeMillis()
            )
            _state.value = _state.value.copy(saving = false, done = ok, allowed = ok || _state.value.allowed)
        }
    }
}

/**
 * Fixing what was typed, not what was said.
 *
 * Titles, years, places, tags, who may see it and what the librarian may do with it:
 * every one of these is metadata somebody entered in a kitchen with a phone in one hand,
 * and QA rightly called it a defect that a typo in any of them was permanent. What is
 * deliberately not editable here is the recording and its transcript: the voice is the
 * artifact, and corrections to the words go through the transcript screen, where the
 * machine's original is kept under every change.
 *
 * Privacy is editable in both directions on purpose. Someone may share a story they once
 * kept close, or pull back one they shared; both are their decision to keep making, not
 * a checkbox frozen at the moment of saving.
 */
@Composable
fun EditStoryScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EditStoryViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val branchChoices by viewModel.branchChoices.collectAsStateWithLifecycle()

    LaunchedEffect(state.done) { if (state.done) onDone() }

    if (state.loaded && !state.allowed) {
        Text(
            "This one is not yours to change. Health records answer to their subject, " +
                "and everything else to whoever recorded it.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier.fillMaxWidth()
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("Edit story", style = MaterialTheme.typography.headlineMedium) }

        item {
            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::onTitle,
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            val noYear = state.eraText.isNotBlank() &&
                !Regex("\\d{4}").containsMatchIn(state.eraText)
            OutlinedTextField(
                value = state.eraText,
                onValueChange = viewModel::onEra,
                enabled = !state.eraUnknown,
                isError = noYear,
                label = { Text("Year or range") },
                supportingText = if (noYear) {
                    { Text("No year found here. It will save as undated unless one is added, like 1958.") }
                } else null,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
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

        item {
            Text(
                "Who can see this",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.visibility == Visibility.FAMILY,
                    onClick = { viewModel.onVisibility(Visibility.FAMILY) },
                    label = { Text("Whole family") }
                )
                if (branchChoices.isNotEmpty()) {
                    FilterChip(
                        selected = state.visibility == Visibility.BRANCH,
                        onClick = { viewModel.onVisibility(Visibility.BRANCH) },
                        label = { Text("One side") }
                    )
                }
                FilterChip(
                    selected = state.visibility == Visibility.PRIVATE,
                    onClick = { viewModel.onVisibility(Visibility.PRIVATE) },
                    label = { Text("Only me") }
                )
            }
        }

        if (state.visibility == Visibility.BRANCH) {
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
            if (state.branchRootPersonId == null) {
                item {
                    Text(
                        "Pick whose line this belongs to, or it cannot be saved.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        item {
            Text(
                "What the librarian may do with it",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.aiUsePolicy == AiUsePolicy.SUMMARY_OK,
                    onClick = { viewModel.onAiUsePolicy(AiUsePolicy.SUMMARY_OK) },
                    label = { Text("Summarize") }
                )
                FilterChip(
                    selected = state.aiUsePolicy == AiUsePolicy.QUOTE_ONLY,
                    onClick = { viewModel.onAiUsePolicy(AiUsePolicy.QUOTE_ONLY) },
                    label = { Text("Quote only") }
                )
                FilterChip(
                    selected = state.aiUsePolicy == AiUsePolicy.NONE,
                    onClick = { viewModel.onAiUsePolicy(AiUsePolicy.NONE) },
                    label = { Text("Never use it") }
                )
            }
        }

        item {
            Button(
                onClick = viewModel::save,
                enabled = state.canSave && state.loaded,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (state.saving) "Saving" else "Save changes") }
        }
    }
}
