package com.arv.app.feature.people

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arv.app.core.di.ServiceLocator
import com.arv.app.core.model.Confidence
import com.arv.app.core.model.Person
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlacePeopleViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.storyRepository(app)
    private val familyId = ServiceLocator.familyId
    private val userId = ServiceLocator.userId

    val unplaced: StateFlow<List<Person>> =
        repo.observeUnplaced(familyId, userId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val everyone: StateFlow<List<Person>> =
        repo.observePeople(familyId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** [parent] is the parent of [child]. Placing one link can place a whole line. */
    fun connect(parent: Person, child: Person) {
        viewModelScope.launch {
            repo.connectParent(
                familyId = familyId,
                parentPersonId = parent.personId,
                childPersonId = child.personId,
                userId = userId,
                nowMillis = System.currentTimeMillis()
            )
        }
    }
}

/**
 * The people the archive is holding but cannot place.
 *
 * An imported family history arrives full of them, because "3x great-grandmother" says how
 * far up somebody sits and never says through whom. The app will not guess, so they sit
 * here as a list of questions instead of being quietly attached to a line they might not
 * belong to.
 *
 * Which makes this the most useful screen in the app for a while: it is a list of things
 * somebody alive probably still knows, and every answer places not just one person but
 * everyone standing behind them.
 */
@Composable
fun PlacePeopleScreen(
    modifier: Modifier = Modifier,
    viewModel: PlacePeopleViewModel = viewModel()
) {
    val unplaced by viewModel.unplaced.collectAsStateWithLifecycle()
    val everyone by viewModel.everyone.collectAsStateWithLifecycle()
    var placing by remember { mutableStateOf<Person?>(null) }

    placing?.let { person ->
        AlertDialog(
            onDismissRequest = { placing = null },
            title = { Text("Whose parent was ${person.displayName}?") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(everyone.filter { it.personId != person.personId }) { child ->
                        TextButton(
                            onClick = {
                                viewModel.connect(parent = person, child = child)
                                placing = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                buildString {
                                    append(child.displayName)
                                    child.birthYear?.let { append("  b. $it") }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { placing = null }) { Text("Cancel") }
            }
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Still to place", style = MaterialTheme.typography.headlineMedium)
        }
        item {
            Text(
                if (unplaced.isEmpty()) {
                    "Everyone in the archive is connected."
                } else {
                    "${unplaced.size} people are in the archive without a place in the " +
                        "tree. The record says roughly how far back they are, not which " +
                        "side of the family they came down. Naming one link places " +
                        "everyone standing behind it."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        items(unplaced, key = { it.personId }) { person ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(person.displayName, style = MaterialTheme.typography.titleMedium)

                    val life = buildString {
                        person.relationLabel?.let { append(it) }
                        val years = listOfNotNull(person.birthYear, person.deathYear)
                        if (years.isNotEmpty()) {
                            if (isNotEmpty()) append("  ·  ")
                            append(person.birthYear?.toString() ?: "?")
                            append(" to ")
                            append(person.deathYear?.toString() ?: "?")
                        }
                        person.birthPlace?.let {
                            if (isNotEmpty()) append("  ·  ")
                            append(it)
                        }
                    }
                    if (life.isNotBlank()) {
                        Text(
                            life,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { placing = person }) {
                            Text("Place them")
                        }
                    }
                }
            }
        }
    }
}
