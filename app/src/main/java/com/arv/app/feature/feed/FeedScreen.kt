package com.arv.app.feature.feed

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontStyle
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
import com.arv.app.core.model.StoryKind
import com.arv.app.ui.components.formatElapsed
import com.arv.app.ui.theme.BrassDark
import com.arv.app.ui.theme.ForestLight
import com.arv.app.ui.theme.InkLight
import com.arv.app.ui.theme.PaperLight
import com.arv.app.ui.theme.TerracottaLight
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs

data class FeedUiState(
    val posts: List<Story> = emptyList(),
    val people: List<Person> = emptyList(),
    val pendingSyncCount: Int = 0,
    val loading: Boolean = true
) {
    val isEmpty: Boolean get() = !loading && posts.isEmpty()

    /** The card at the top of Home. Most recent recorded story wins. */
    val featured: Story? get() = posts.firstOrNull()
    val recent: List<Story> get() = posts.drop(1)
}

class FeedViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.storyRepository(app)
    private val familyId = ServiceLocator.DEMO_FAMILY_ID

    // TODO(DAT-1): the real signed-in member.
    private val viewer = Viewer(
        userId = ServiceLocator.DEMO_USER_ID,
        role = MemberRole.OWNER,
        branchRootPersonId = null
    )

    val uiState: StateFlow<FeedUiState> =
        combine(
            repo.observeRecent(familyId).map { stories ->
                // The same filter the librarian uses. The feed is not a separate
                // permission surface, it is the same one rendered differently.
                stories.filter { MemoryAccess.canRead(it, viewer) }
            },
            repo.observePeople(familyId),
            repo.observePendingSyncCount()
        ) { posts, people, pending ->
            FeedUiState(posts = posts, people = people, pendingSyncCount = pending, loading = false)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedUiState())

    init {
        // TODO(DAT-2): remove once real sync populates the local database.
        viewModelScope.launch { repo.seedDemoDataIfEmpty(familyId) }
    }
}

/**
 * Screen 17, built to the hi-fi comp: dark forest header with the family row, the offline
 * promise, one featured story, one prompt, and a shelf of recent memories.
 *
 * A recorded story and someone's photo of a lost tooth render as the same kind of object
 * on purpose. The everyday post is what keeps people coming back, and in thirty years it
 * is archive material too. Nothing here is second-class.
 */
@Composable
fun FeedScreen(
    onOpenStory: (String) -> Unit,
    onRecord: () -> Unit,
    onOpenPerson: (String) -> Unit = {},
    onViewAll: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: FeedViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)
    ) {
        item {
            HomeHeader(
                people = state.people,
                pendingSyncCount = state.pendingSyncCount,
                onOpenPerson = onOpenPerson
            )
        }

        if (state.isEmpty) {
            item { EmptyFeed(onRecord = onRecord) }
        } else {
            state.featured?.let { story ->
                item {
                    FeaturedStoryCard(
                        story = story,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        onClick = { onOpenStory(story.storyId) }
                    )
                }
            }

            item {
                // TODO(AI-8): generated from gaps in this person's own transcripts.
                // Until then the prompt targets the eldest living storyteller, which is
                // also who the product is racing the clock for.
                val target = state.people
                    .filter { !it.isDeceased }
                    .minByOrNull { it.birthYear ?: Int.MAX_VALUE }
                PromptCard(
                    askName = target?.shortName() ?: "them",
                    question = "What did your mother's kitchen smell like on a Sunday?",
                    onRecord = onRecord,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            if (state.recent.isNotEmpty()) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Recent memories",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Spacer(Modifier.weight(1f))
                        Row(
                            Modifier.clickable(onClick = onViewAll),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "View all",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                item {
                    LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(state.recent, key = { it.storyId }) { post ->
                            RecentMemoryCard(post = post, onClick = { onOpenStory(post.storyId) })
                        }
                    }
                }
            }
        }
    }
}

/**
 * The dark forest block from the comp. Deliberately the same deep green in light and dark
 * theme; it is the brand roof over the page, not a surface that inverts.
 */
@Composable
private fun HomeHeader(
    people: List<Person>,
    pendingSyncCount: Int,
    onOpenPerson: (String) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(ForestLight)
            .padding(top = 8.dp, bottom = 18.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Our Family",
                style = MaterialTheme.typography.displaySmall,
                color = PaperLight
            )
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Outlined.PersonAddAlt,
                contentDescription = "Invite family",
                tint = PaperLight
            )
        }

        Spacer(Modifier.height(4.dp))

        Row(
            Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.Transparent,
                border = androidx.compose.foundation.BorderStroke(1.dp, PaperLight.copy(alpha = 0.55f))
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Whole family",
                        style = MaterialTheme.typography.labelLarge,
                        color = PaperLight
                    )
                    Icon(
                        Icons.Outlined.KeyboardArrowDown,
                        contentDescription = null,
                        tint = PaperLight,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            items(people, key = { it.personId }) { person ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { onOpenPerson(person.personId) }
                ) {
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(PaperLight.copy(alpha = 0.12f))
                            .border(2.dp, BrassDark, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            person.displayName.split(" ")
                                .mapNotNull { it.firstOrNull()?.uppercase() }
                                .take(2).joinToString(""),
                            style = MaterialTheme.typography.titleLarge,
                            color = PaperLight
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        person.shortName(),
                        style = MaterialTheme.typography.labelLarge,
                        color = PaperLight,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (pendingSyncCount > 0) {
            Spacer(Modifier.height(14.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.Transparent,
                border = androidx.compose.foundation.BorderStroke(1.dp, PaperLight.copy(alpha = 0.35f)),
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = PaperLight)
                    Column(Modifier.weight(1f)) {
                        val label =
                            if (pendingSyncCount == 1) "1 memory waiting to upload"
                            else "$pendingSyncCount memories waiting to upload"
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = PaperLight
                        )
                        // The sentence the whole offline design exists to earn.
                        Text(
                            "Nothing is lost.",
                            style = MaterialTheme.typography.bodySmall,
                            color = PaperLight.copy(alpha = 0.8f)
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = PaperLight
                    )
                }
            }
        }
    }
}

/** The big story card: serif title over deep green, play row, star ribbon. */
@Composable
private fun FeaturedStoryCard(story: Story, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF3A5741), ForestLight)
                    )
                )
        ) {
            // Star ribbon, top right. Featured means a keeper pinned it.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 0.dp, end = 20.dp)
                    .background(
                        TerracottaLight,
                        RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 10.dp)
            ) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "Featured",
                    tint = PaperLight,
                    modifier = Modifier.size(18.dp)
                )
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 46.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    story.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = PaperLight
                )
                Text(
                    buildString {
                        append(story.eraLabel)
                        story.placeLabel?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = PaperLight.copy(alpha = 0.85f)
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(PaperLight),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Play",
                            tint = InkLight
                        )
                    }
                    StaticWaveform(
                        seed = story.storyId,
                        color = PaperLight.copy(alpha = 0.75f),
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp)
                    )
                    if (story.durationMs > 0) {
                        Text(
                            formatElapsed(story.durationMs),
                            style = MaterialTheme.typography.labelLarge,
                            color = PaperLight
                        )
                    }
                }
            }
        }
    }
}

/**
 * Decorative playback bars for a story that is not currently playing. Heights derive from
 * the story id so the card is stable frame to frame; this is texture, not a meter.
 */
@Composable
private fun StaticWaveform(seed: String, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val barCount = 36
        val slot = size.width / barCount
        val barWidth = (slot * 0.5f).coerceAtLeast(2f)
        val centerY = size.height / 2f
        var h = seed.hashCode()
        repeat(barCount) { i ->
            h = h * 31 + i
            val t = (abs(h) % 100) / 100f
            val half = (0.2f + 0.8f * t) * (size.height / 2f)
            val x = i * slot + slot / 2f
            drawLine(
                color = color,
                start = Offset(x, centerY - half),
                end = Offset(x, centerY + half),
                strokeWidth = barWidth,
                cap = StrokeCap.Round
            )
        }
    }
}

/** "Ask Marianne" from the comp, wired to the real prompt engine's slot. */
@Composable
private fun PromptCard(
    askName: String,
    question: String,
    onRecord: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onRecord,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ForestLight)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                Icons.Outlined.Mic,
                contentDescription = null,
                tint = BrassDark,
                modifier = Modifier.size(44.dp)
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Ask $askName:",
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = BrassDark
                )
                Text(
                    question,
                    style = MaterialTheme.typography.headlineSmall,
                    color = PaperLight
                )
                Button(
                    onClick = onRecord,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TerracottaLight,
                        contentColor = PaperLight
                    ),
                    shape = RoundedCornerShape(50)
                ) {
                    Text("Record the answer")
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/** One shelf card under Recent memories. */
@Composable
private fun RecentMemoryCard(post: Story, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.width(190.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(92.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.secondaryContainer,
                                MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    )
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = InkLight.copy(alpha = 0.75f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                ) {
                    Text(
                        post.eraLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = PaperLight,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                if (post.kind == StoryKind.AUDIO) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(InkLight.copy(alpha = 0.75f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Play",
                            tint = PaperLight,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    post.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    post.kind.label(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyFeed(onRecord: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Nothing here yet", style = MaterialTheme.typography.headlineSmall)
        Text(
            "The first recording is the hardest one. Ask a single question and let them talk.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onRecord) { Text("Record the first story") }
    }
}

/**
 * "Ruth Delaney" is Ruth, but "Miss Opal" is never just "Miss". Names carry respect;
 * truncation is not allowed to strip it.
 */
private val honorifics = setOf("Miss", "Mr", "Mr.", "Mrs", "Mrs.", "Ms", "Ms.", "Dr", "Dr.")
private fun Person.shortName(): String {
    val parts = displayName.split(" ")
    return when {
        parts.size <= 1 -> displayName
        parts.first() in honorifics -> parts.take(2).joinToString(" ")
        else -> parts.first()
    }
}

private fun StoryKind.label(): String = when (this) {
    StoryKind.AUDIO -> "Recorded story"
    StoryKind.PHOTO_SET -> "Photos"
    StoryKind.DOCUMENT -> "Document"
    StoryKind.COLLECTION -> "Collection"
    StoryKind.UPDATE -> "Update"
}
