package com.arv.app.feature.librarian

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arv.app.core.ai.LibrarianOutcome
import com.arv.app.core.ai.Viewer
import com.arv.app.core.di.ServiceLocator
import com.arv.app.core.model.LibrarianScope
import com.arv.app.core.model.MemberRole
import com.arv.app.core.model.Provenance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LibrarianUiState(
    val question: String = "",
    val scope: LibrarianScope = LibrarianScope.FAMILY,
    val asking: Boolean = false,
    val outcome: LibrarianOutcome? = null
)

class LibrarianViewModel(app: Application) : AndroidViewModel(app) {

    private val service = ServiceLocator.librarianService(app)
    private val familyId = ServiceLocator.familyId

    // One definition, in ServiceLocator. Four screens each building their own
    // Viewer is four chances to disagree about what someone may read.
    private val viewer = ServiceLocator.viewer

    private val _state = MutableStateFlow(LibrarianUiState())
    val state: StateFlow<LibrarianUiState> = _state.asStateFlow()

    fun onQuestionChange(value: String) {
        _state.value = _state.value.copy(question = value)
    }

    fun onScopeChange(scope: LibrarianScope) {
        // Changing scope invalidates the answer. An answer built from the personal vault
        // must never linger on screen after the user switches to the family view.
        _state.value = _state.value.copy(scope = scope, outcome = null)
    }

    fun ask() {
        val current = _state.value
        if (current.question.isBlank() || current.asking) return
        _state.value = current.copy(asking = true, outcome = null)
        viewModelScope.launch {
            val outcome = service.ask(current.question, current.scope, viewer, familyId)
            _state.value = _state.value.copy(asking = false, outcome = outcome)
        }
    }
}

/** Screen 21. */
@Composable
fun LibrarianScreen(
    onOpenStory: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibrarianViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.scope == LibrarianScope.PERSONAL,
                    onClick = { viewModel.onScopeChange(LibrarianScope.PERSONAL) },
                    label = { Text("My librarian") }
                )
                FilterChip(
                    selected = state.scope == LibrarianScope.FAMILY,
                    onClick = { viewModel.onScopeChange(LibrarianScope.FAMILY) },
                    label = { Text("Our family librarian") }
                )
            }
        }

        item {
            Text(
                when (state.scope) {
                    LibrarianScope.PERSONAL ->
                        "Reading everything you own, including your private memories."
                    LibrarianScope.FAMILY ->
                        "Reading only what family members chose to share. Private vaults are not readable here."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            OutlinedTextField(
                value = state.question,
                onValueChange = viewModel::onQuestionChange,
                label = { Text("Ask about your family") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            AssistChip(onClick = viewModel::ask, label = { Text("Ask") })
        }

        if (state.asking) {
            item { CircularProgressIndicator() }
        }

        when (val outcome = state.outcome) {
            is LibrarianOutcome.Answered -> {
                val answer = outcome.answer
                item {
                    Card {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(answer.text, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Written by the librarian, not by a family member.",
                                style = MaterialTheme.typography.bodySmall,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (answer.routedThrough.isNotEmpty()) {
                                Text(
                                    "Shelves consulted: ${answer.routedThrough.joinToString(", ")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            HorizontalDivider()
                            Text(
                                "WHERE THIS CAME FROM",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            answer.sources.forEach { source ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    AssistChip(
                                        onClick = { onOpenStory(source.storyId) },
                                        label = { Text(source.provenance.label()) }
                                    )
                                    Text(
                                        source.quote,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
                if (answer.withheldCount > 0) {
                    item { WithheldCard(answer.withheldCount) }
                }
            }

            is LibrarianOutcome.AllWithheld -> item { WithheldCard(outcome.withheldCount) }

            LibrarianOutcome.NoMatches -> item {
                Text(
                    "Nothing in the archive speaks to that yet. That is a gap, not an error. Consider recording it while you can.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            is LibrarianOutcome.Failed -> item {
                Text(
                    outcome.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            null -> Unit
        }
    }
}

@Composable
private fun WithheldCard(count: Int) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("WITHHELD", style = MaterialTheme.typography.labelMedium)
            Text(
                if (count == 1) {
                    "1 memory matched but is private to its owner. It was not read."
                } else {
                    "$count memories matched but are private to their owners. They were not read."
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** Never show a raw enum name to a grieving family. */
private fun Provenance.label(): String = when (this) {
    Provenance.AUTHENTIC_RECORDING -> "THEIR VOICE"
    Provenance.AUTHENTIC_DOCUMENT -> "THEIR HAND"
    Provenance.HUMAN_WRITTEN -> "WRITTEN"
    Provenance.AI_TRANSCRIBED -> "TRANSCRIPT"
    Provenance.AI_ORGANIZED -> "MACHINE"
}
