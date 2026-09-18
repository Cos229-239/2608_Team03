package com.arv.app.feature.settings

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arv.app.core.data.StoryRepository
import com.arv.app.core.di.ServiceLocator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecentlyDeletedViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.storyRepository(app)

    val deleted: StateFlow<List<StoryRepository.DeletedStory>> =
        repo.observeRecentlyDeleted(ServiceLocator.familyId, ServiceLocator.viewer)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun restore(storyId: String) {
        viewModelScope.launch {
            repo.restoreStory(storyId, ServiceLocator.viewer, System.currentTimeMillis())
        }
    }

    /** The same act the thirty days perform on their own, asked for early. */
    fun erase(storyId: String) {
        viewModelScope.launch {
            repo.eraseStory(storyId, ServiceLocator.viewer)
        }
    }
}

/**
 * Deleted stories this person could bring back, and the one place they are erased for good.
 *
 * Draws nothing at all, heading included, when there is nothing to bring back, so an archive
 * nobody has deleted from does not carry an empty section.
 */
@Composable
fun RecentlyDeletedSection(
    modifier: Modifier = Modifier,
    viewModel: RecentlyDeletedViewModel = viewModel()
) {
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    if (deleted.isEmpty()) return

    var erasing by remember { mutableStateOf<StoryRepository.DeletedStory?>(null) }

    erasing?.let { story ->
        AlertDialog(
            onDismissRequest = { erasing = null },
            title = { Text("Erase this story for good?") },
            text = {
                Text(
                    "The recording, the transcript and everything about it go, on this phone " +
                        "and on every phone the archive is shared with. This one cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.erase(story.storyId)
                        erasing = null
                    }
                ) { Text("Erase", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { erasing = null }) { Text("Keep it") }
            }
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "RECENTLY DELETED",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "Bringing a story back returns it to every phone it was shared with. Anything left " +
                "here is erased for good thirty days after it was deleted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        deleted.forEach { story ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        story.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "Deleted " + dayOf(story.deletedAt) +
                            (story.deletedByName?.let { " by $it" } ?: "") +
                            ". Erased " + dayOf(story.erasedAt) + ".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { viewModel.restore(story.storyId) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("Bring back") }
                        TextButton(
                            onClick = { erasing = story },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("Delete forever", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }

        HorizontalDivider()
    }
}

private fun dayOf(millis: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(millis))
