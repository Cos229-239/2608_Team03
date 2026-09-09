package com.arv.app.feature.people

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import com.arv.app.ui.components.PersonAvatar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.arv.app.core.model.FamilyLens
import com.arv.app.core.model.matchesSearch
import com.arv.app.core.model.underLens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arv.app.core.di.ServiceLocator
import com.arv.app.core.model.Person
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

data class PeopleUiState(
    val people: List<Person> = emptyList(),
    /** Faces this viewer may see, filtered in the repository. Absent means initials. */
    val portraits: Map<String, String> = emptyMap(),
    val lenses: List<FamilyLens> = listOf(FamilyLens.Whole),
    val lens: FamilyLens = FamilyLens.Whole,
    val query: String = "",
    /**
     * Everyone in the archive, before the lens and the search narrowed it.
     *
     * Kept so the screen can decide whether to offer the controls at all. A family of
     * four does not need a search field, and showing one implies the list is longer than
     * it is.
     */
    val total: Int = 0
)

class PeopleViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ServiceLocator.storyRepository(app)
    private val familyId = ServiceLocator.familyId
    private val viewer = ServiceLocator.viewer

    private val query = MutableStateFlow("")
    private val lens = MutableStateFlow(FamilyLens.Whole)

    fun onQuery(v: String) { query.value = v }
    fun chooseLens(choice: FamilyLens) { lens.value = choice }

    /**
     * The list, narrowed twice: by which side of the family, then by what was typed.
     *
     * Both filters are the shared pure ones the feed uses, so the two screens cannot come
     * to different conclusions about what a side of the family contains.
     */
    val uiState: StateFlow<PeopleUiState> =
        combine(
            repo.observePeople(familyId),
            repo.observeRelationships(familyId),
            repo.observePortraits(familyId, viewer),
            query,
            lens
        ) { people, edges, portraits, typed, chosen ->
            val meId = people.firstOrNull { it.linkedUserId == viewer.userId }?.personId
            val options = FamilyLens.optionsFor(meId, people, edges)
            val active = FamilyLens.resolve(chosen, options)
            PeopleUiState(
                people = people.underLens(active, meId, edges).filter { it.matchesSearch(typed) },
                portraits = portraits,
                lenses = options,
                lens = active,
                query = typed,
                total = people.size
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PeopleUiState())
}

/** Screen 11 list. UX-6 builds the detail view with the hours-preserved meter. */
@Composable
fun PeopleScreen(
    modifier: Modifier = Modifier,
    onOpenDocuments: () -> Unit = {},
    onOpenPerson: (String) -> Unit = {},
    onAddPerson: () -> Unit = {},
    onPlacePeople: () -> Unit = {},
    viewModel: PeopleViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var lensMenuOpen by remember { mutableStateOf(false) }

    LazyColumn(
        // Below the clock, not under it. The first card here sat half beneath the
        // status bar and taps on that half never reached the app, so a wired button
        // read as dead: the screen was simply standing in the wrong place.
        modifier = modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Offered only once the list is long enough to need them. At four people a search
        // field is furniture; the importer can drop a compiled history in and make it the
        // only way to find anybody, so the threshold rather than a preference.
        if (state.total >= 8) {
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::onQuery,
                    label = { Text("Find someone") },
                    placeholder = { Text("A name, a nickname, a birthplace") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        TextButton(onClick = { lensMenuOpen = true }) {
                            Text(state.lens.label)
                        }
                        DropdownMenu(
                            expanded = lensMenuOpen,
                            onDismissRequest = { lensMenuOpen = false }
                        ) {
                            state.lenses.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        viewModel.chooseLens(option)
                                        lensMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    // Says how much of the archive is being hidden, so a filter can never
                    // quietly look like an empty family.
                    Text(
                        if (state.people.size == state.total) {
                            "${state.total} people"
                        } else {
                            "${state.people.size} of ${state.total}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (state.people.isEmpty()) {
                item {
                    Text(
                        "Nobody here matches that. The archive still holds " +
                            "${state.total} people.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // While somebody is searching, the three navigation cards are furniture standing
        // between them and the answer. A single match rendered under a wall of buttons
        // reads as no match at all.
        if (state.query.isBlank()) {
        item {
            // First card, above Documents: an archive whose people list cannot grow is
            // a viewer, not an archive.
            Card(onClick = onAddPerson, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Outlined.PersonAdd, contentDescription = null)
                    Column(Modifier.weight(1f)) {
                        Text("Add someone", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "A name is enough to start",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null
                    )
                }
            }
        }

        item {
            // The worklist. Sits high because it is where an imported history actually
            // gets turned into a tree, and because the answers live in people who are
            // still here to be asked.
            Card(onClick = onPlacePeople, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Outlined.HelpOutline, contentDescription = null)
                    Column(Modifier.weight(1f)) {
                        Text("Still to place", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "People in the archive with no place in the tree yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null
                    )
                }
            }
        }

        item {
            // Documents sit next to people because that is how families think about
            // them: the certificate belongs to whoever it names.
            Card(onClick = onOpenDocuments, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Outlined.Description, contentDescription = null)
                    Column(Modifier.weight(1f)) {
                        Text("Documents", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Records and certificates, linked to people",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null
                    )
                }
            }
        }

        }

        items(state.people, key = { it.personId }) { person ->
            Card(
                onClick = { onOpenPerson(person.personId) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                PersonAvatar(
                    displayName = person.displayName,
                    localPath = state.portraits[person.personId],
                    size = 44.dp
                )
                Spacer(Modifier.width(14.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(person.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        buildString {
                            person.relationLabel?.let { append(it) }
                            person.birthYear?.let {
                                if (isNotEmpty()) append("  ·  ")
                                append("b. $it")
                            }
                            person.deathYear?.let { append("  ·  d. $it") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Asking for consent from someone on public record would be theatre,
                    // and marking their entry in red implies a problem that is not there.
                    // What their entry should say is where the facts came from.
                    when {
                        person.isPublicRecord -> Text(
                            "From public record",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        person.consentDeclined -> Text(
                            "Asked not to be shared",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        person.needsAConsentDecision -> Text(
                            if (person.isDeceased) "Nobody has said what they would have wanted"
                            else "No consent record on file",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                }
            }
        }
    }
}
