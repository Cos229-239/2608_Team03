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

class PeopleViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ServiceLocator.storyRepository(app)

    val people: StateFlow<List<Person>> =
        repo.observePeople(ServiceLocator.familyId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** Screen 11 list. UX-6 builds the detail view with the hours-preserved meter. */
@Composable
fun PeopleScreen(
    onOpenDocuments: () -> Unit = {},
    onOpenPerson: (String) -> Unit = {},
    onAddPerson: () -> Unit = {},
    onPlacePeople: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: PeopleViewModel = viewModel()
) {
    val people by viewModel.people.collectAsStateWithLifecycle()

    LazyColumn(
        // Below the clock, not under it. The first card here sat half beneath the
        // status bar and taps on that half never reached the app, so a wired button
        // read as dead: the screen was simply standing in the wrong place.
        modifier = modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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

        items(people, key = { it.personId }) { person ->
            Card(
                onClick = { onOpenPerson(person.personId) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(14.dp),
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
