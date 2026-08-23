package com.arv.app.feature.documents

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arv.app.core.ai.MemoryAccess
import com.arv.app.core.ai.Viewer
import com.arv.app.core.di.ServiceLocator
import com.arv.app.core.model.MemberRole
import com.arv.app.core.model.Person
import com.arv.app.core.model.Story
import com.arv.app.ui.theme.BrassDark
import com.arv.app.ui.theme.ForestLight
import com.arv.app.ui.theme.PaperLight
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class DocumentsUiState(
    val documents: List<Story> = emptyList(),
    val people: List<Person> = emptyList()
) {
    /** Records the family knows about but nobody has found or scanned yet. */
    val wanted: List<Story> get() = documents.filter { it.assetCount == 0 }
    val held: List<Story> get() = documents.filter { it.assetCount > 0 }

    fun namesFor(story: Story): String =
        story.subjectPersonIds
            .mapNotNull { id -> people.firstOrNull { it.personId == id }?.displayName }
            .joinToString(", ")
}

class DocumentsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.storyRepository(app)
    private val familyId = ServiceLocator.DEMO_FAMILY_ID

    // TODO(DAT-1): the real signed-in member.
    private val viewer = Viewer(
        userId = ServiceLocator.DEMO_USER_ID,
        role = MemberRole.OWNER,
        branchRootPersonId = null
    )

    val uiState: StateFlow<DocumentsUiState> =
        combine(
            // Documents run through the same permission filter as everything else. A
            // death record is not automatically public just because it is paperwork.
            repo.observeDocuments(familyId).map { docs ->
                docs.filter { MemoryAccess.canRead(it, viewer) }
            },
            repo.observePeople(familyId)
        ) { docs, people ->
            DocumentsUiState(documents = docs, people = people)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DocumentsUiState())
}

/**
 * Records rather than recordings: marriage and death records, ship manifests, the
 * postcard in somebody's box.
 *
 * The section that matters most is the one at the bottom. A document the family knows
 * exists but nobody has found is still part of the archive, and naming it is how the box
 * in the garage eventually gets opened. An archive that only lists what has already been
 * scanned quietly pretends the rest was never there.
 */
@Composable
fun DocumentsScreen(
    onOpenStory: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DocumentsViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var personFilter by remember { mutableStateOf<String?>(null) }

    fun matches(story: Story) =
        personFilter == null || personFilter in story.subjectPersonIds

    val held = state.held.filter(::matches)
    val wanted = state.wanted.filter(::matches)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(ForestLight)
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
            ) {
                Text(
                    "Documents",
                    style = MaterialTheme.typography.displaySmall,
                    color = PaperLight
                )
                Text(
                    "Records, certificates, and papers, linked to the people they belong to.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PaperLight.copy(alpha = 0.85f)
                )
            }
        }

        if (state.people.isNotEmpty()) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = personFilter == null,
                            onClick = { personFilter = null },
                            label = { Text("Everyone") }
                        )
                    }
                    items(state.people, key = { it.personId }) { person ->
                        FilterChip(
                            selected = personFilter == person.personId,
                            onClick = {
                                personFilter =
                                    if (personFilter == person.personId) null else person.personId
                            },
                            label = {
                                Text(person.displayName.split(" ").firstOrNull() ?: person.displayName)
                            }
                        )
                    }
                }
            }
        }

        if (held.isEmpty() && wanted.isEmpty()) {
            item { EmptyDocuments() }
        }

        if (held.isNotEmpty()) {
            item { SectionHeader("In the archive") }
            items(held, key = { it.storyId }) { doc ->
                DocumentCard(
                    story = doc,
                    people = state.namesFor(doc),
                    onClick = { onOpenStory(doc.storyId) }
                )
            }
        }

        if (wanted.isNotEmpty()) {
            item { SectionHeader("Known to exist, not found yet") }
            item {
                Text(
                    "Somebody has these in a box. Naming them here is how they get found.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            items(wanted, key = { it.storyId }) { doc ->
                DocumentCard(
                    story = doc,
                    people = state.namesFor(doc),
                    onClick = { onOpenStory(doc.storyId) }
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
    )
}

@Composable
private fun DocumentCard(story: Story, people: String, onClick: () -> Unit) {
    val found = story.assetCount > 0

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(
                        if (found) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (found) Icons.Outlined.Description else Icons.Outlined.HelpOutline,
                    contentDescription = null,
                    tint = if (found) BrassDark else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    story.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        if (people.isNotEmpty()) append(people)
                        if (isNotEmpty()) append("  ·  ")
                        append(story.eraLabel)
                        story.placeLabel?.let { append("  ·  $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!found) {
                    Spacer(Modifier.height(2.dp))
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            "No copy yet",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyDocuments() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("No documents yet", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Certificates, records, and papers go here, attached to the people they name.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
