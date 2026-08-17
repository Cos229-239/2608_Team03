package com.arv.app.feature.people

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arv.app.core.ai.MemoryAccess
import com.arv.app.core.ai.Viewer
import com.arv.app.core.di.ServiceLocator
import com.arv.app.core.model.MemberRole
import com.arv.app.core.model.Person
import com.arv.app.core.model.Story
import com.arv.app.core.model.StoryKind
import com.arv.app.ui.theme.BrassDark
import com.arv.app.ui.theme.ForestLight
import com.arv.app.ui.theme.PaperLight
import com.arv.app.ui.theme.TerracottaLight
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class PersonDetailViewModel(
    app: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(app) {

    private val personId: String = savedStateHandle["personId"] ?: ""
    private val repo = ServiceLocator.storyRepository(app)
    private val familyId = ServiceLocator.DEMO_FAMILY_ID

    // TODO(DAT-1): the real signed-in member.
    private val viewer = Viewer(
        userId = ServiceLocator.DEMO_USER_ID,
        role = MemberRole.OWNER,
        branchRootPersonId = null
    )

    val person: StateFlow<Person?> =
        repo.observePeople(familyId)
            .map { people -> people.firstOrNull { it.personId == personId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val recordedMs: StateFlow<Long> =
        repo.observeRecordedMsFor(familyId, personId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /**
     * Everything they told and everything told about them, under the same permission
     * filter as every other surface. Being on someone's profile grants nothing extra.
     */
    val stories: StateFlow<List<Story>> =
        repo.observeRecent(familyId)
            .map { stories ->
                stories.filter { story ->
                    (personId in story.narratorIds || personId in story.subjectPersonIds) &&
                        MemoryAccess.canRead(story, viewer)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/**
 * Screen 19, the living version. A memorial profile becomes this without losing a single
 * choice the person made. Things in their voice and things written about them are visibly
 * different objects; that distinction never collapses.
 */
@Composable
fun PersonDetailScreen(
    onOpenStory: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PersonDetailViewModel = viewModel()
) {
    val person by viewModel.person.collectAsStateWithLifecycle()
    val recordedMs by viewModel.recordedMs.collectAsStateWithLifecycle()
    val stories by viewModel.stories.collectAsStateWithLifecycle()

    val p = person ?: return

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
                    .padding(horizontal = 16.dp, vertical = 20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(PaperLight.copy(alpha = 0.12f))
                            .border(2.dp, BrassDark, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            p.displayName.split(" ")
                                .mapNotNull { it.firstOrNull()?.uppercase() }
                                .take(2).joinToString(""),
                            style = MaterialTheme.typography.titleLarge,
                            color = PaperLight
                        )
                    }
                    Spacer(Modifier.size(16.dp))
                    Column {
                        Text(
                            p.displayName,
                            style = MaterialTheme.typography.headlineMedium,
                            color = PaperLight
                        )
                        val line = buildString {
                            p.birthYear?.let { append(it) }
                            p.deathYear?.let { append(" – $it") }
                            p.alsoKnownAs.firstOrNull()?.let {
                                if (isNotEmpty()) append("  ·  ")
                                append("“$it”")
                            }
                            p.relationLabel?.let {
                                if (isNotEmpty()) append("  ·  ")
                                append(it)
                            }
                        }
                        if (line.isNotEmpty()) {
                            Text(
                                line,
                                style = MaterialTheme.typography.bodyMedium,
                                color = PaperLight.copy(alpha = 0.85f)
                            )
                        }
                        if (p.isDeceased) {
                            Spacer(Modifier.height(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = TerracottaLight
                            ) {
                                Text(
                                    "MEMORIAL",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = PaperLight,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                if (recordedMs > 0) {
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Transparent,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, PaperLight.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(shape = RoundedCornerShape(4.dp), color = BrassDark) {
                                Text(
                                    "THEIR VOICE",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ForestLight,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                "${formatPreserved(recordedMs)} preserved",
                                style = MaterialTheme.typography.bodyMedium,
                                color = PaperLight
                            )
                        }
                    }
                }
            }
        }

        if (!p.consentGranted) {
            item {
                Text(
                    "No consent record on file. Their memories stay restricted until one exists.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }

        if (stories.isNotEmpty()) {
            item {
                Text(
                    "In the archive",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            items(stories, key = { it.storyId }) { story ->
                PersonStoryCard(
                    story = story,
                    personId = p.personId,
                    onClick = { onOpenStory(story.storyId) },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        } else {
            item {
                Text(
                    "Nothing recorded with ${p.displayName} yet. The first question is the hardest one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun PersonStoryCard(
    story: Story,
    personId: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (story.kind == StoryKind.AUDIO) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(ForestLight),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Play",
                        tint = PaperLight,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    story.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    story.eraLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Their voice and words about them never collapse into one another.
            Text(
                if (personId in story.narratorIds) "their voice" else "about them",
                style = MaterialTheme.typography.labelMedium,
                color = if (personId in story.narratorIds) BrassDark
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatPreserved(ms: Long): String {
    val totalMinutes = ms / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "less than a minute"
    }
}
