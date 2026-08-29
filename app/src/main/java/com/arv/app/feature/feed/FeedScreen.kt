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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.arv.app.core.ai.Lineage
import com.arv.app.core.ai.MemoryAccess
import com.arv.app.core.ai.Viewer
import com.arv.app.ui.theme.ArvHero
import com.arv.app.core.di.ServiceLocator
import com.arv.app.core.session.ActiveSession
import com.arv.app.core.model.MemberRole
import com.arv.app.core.model.Person
import com.arv.app.core.model.Story
import com.arv.app.core.model.StoryKind
import com.arv.app.ui.components.formatElapsed
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * One way of looking at the feed: the whole family, one side of it, or only yourself.
 *
 * Sides are the viewer's own parents, derived from the graph, so the menu offers exactly
 * the sides this person's family actually has and nothing invented.
 */
data class FeedLens(
    val label: String,
    /** The parent whose side this is, null for whole-family and just-me. */
    val parentId: String? = null,
    val mine: Boolean = false
) {
    companion object {
        val Whole = FeedLens("Whole family")
    }
}

data class FeedUiState(
    val posts: List<Story> = emptyList(),
    val people: List<Person> = emptyList(),
    val pendingSyncCount: Int = 0,
    val loading: Boolean = true,
    /** storyId to its audio file, present only for stories that can actually be played. */
    val audioPaths: Map<String, String> = emptyMap(),
    val lenses: List<FeedLens> = listOf(FeedLens.Whole),
    val lens: FeedLens = FeedLens.Whole
) {
    val isEmpty: Boolean get() = !loading && posts.isEmpty()

    /** The card at the top of Home. Most recent recorded story wins. */
    val featured: Story? get() = posts.firstOrNull()
    val recent: List<Story> get() = posts.drop(1)
}

class FeedViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.storyRepository(app)
    private val familyId = ServiceLocator.familyId

    // One definition, in ServiceLocator. Four screens each building their own
    // Viewer is four chances to disagree about what someone may read.
    private val viewer = ServiceLocator.viewer

    /** Home plays through the same controller as every other screen. One voice at a time. */
    val playback = ServiceLocator.playback

    /** Whose archive this is, so the header can say so instead of guessing. */
    val familyName: String get() = ActiveSession.familyName ?: "Our Family"

    private val lens = kotlinx.coroutines.flow.MutableStateFlow(FeedLens.Whole)

    fun chooseLens(choice: FeedLens) { lens.value = choice }

    val uiState: StateFlow<FeedUiState> =
        combine(
            combine(
                repo.observeRecent(familyId),
                repo.observePeople(familyId),
                repo.observeRelationships(familyId),
                lens
            ) { all, people, edges, chosen ->
                // The same filter the librarian uses, consent included. The feed is not a
                // separate permission surface, it is the same one rendered differently.
                val readable = all.filter { MemoryAccess.canRead(it, viewer, people) }

                val meId = people.firstOrNull { it.linkedUserId == viewer.userId }?.personId
                val lenses = feedLenses(meId, people, edges)
                // A lens that stopped existing (a parent edge was removed) falls back to
                // the whole family rather than filtering by a ghost.
                val active = lenses.firstOrNull {
                    it.parentId == chosen.parentId && it.mine == chosen.mine
                } ?: FeedLens.Whole

                Triple(
                    filterByLens(readable, active, meId, edges),
                    peopleForLens(people, active, meId, edges),
                    lenses to active
                )
            },
            repo.observePendingSyncCount(),
            repo.observeAudioPaths(familyId)
        ) { (posts, people, lensPair), pending, paths ->
            FeedUiState(
                posts = posts,
                people = people,
                pendingSyncCount = pending,
                loading = false,
                audioPaths = paths,
                lenses = lensPair.first,
                lens = lensPair.second
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedUiState())

    /** Whole family, one entry per parent the viewer actually has, and just-me. */
    private fun feedLenses(
        meId: String?,
        people: List<Person>,
        edges: List<com.arv.app.core.model.Relationship>
    ): List<FeedLens> {
        if (meId == null) return listOf(FeedLens.Whole)
        val sides = Lineage.immediateParents(meId, edges).mapNotNull { parentId ->
            people.firstOrNull { it.personId == parentId }?.let { parent ->
                FeedLens("${parent.shortName()}'s side", parentId = parentId)
            }
        }
        return listOf(FeedLens.Whole) + sides + FeedLens("Just me", mine = true)
    }

    /**
     * A story belongs to a side when somebody who told it is on that side of the
     * viewer's family, or the memory was scoped to a branch on that side. Derived from
     * the same walk the person pages use, so the feed and the tree never disagree about
     * where somebody stands.
     */
    private fun filterByLens(
        posts: List<Story>,
        lens: FeedLens,
        meId: String?,
        edges: List<com.arv.app.core.model.Relationship>
    ): List<Story> = when {
        lens.mine -> posts.filter { story ->
            story.createdBy == viewer.userId || (meId != null && meId in story.narratorIds)
        }
        lens.parentId == null || meId == null -> posts
        else -> posts.filter { story ->
            val onSide = { id: String ->
                id == lens.parentId || lens.parentId in Lineage.sideOf(id, meId, edges)
            }
            story.narratorIds.any(onSide) || story.branchRootPersonId?.let(onSide) == true
        }
    }

    /** The avatar strip narrows with the lens, so the row shows who the feed shows. */
    private fun peopleForLens(
        people: List<Person>,
        lens: FeedLens,
        meId: String?,
        edges: List<com.arv.app.core.model.Relationship>
    ): List<Person> = when {
        lens.mine -> people.filter { it.personId == meId }
        lens.parentId == null || meId == null -> people
        else -> people.filter {
            it.personId == meId || it.personId == lens.parentId ||
                lens.parentId in Lineage.sideOf(it.personId, meId, edges)
        }
    }

    init {
        // Only the sample family gets sample data. A real family's archive starts empty
        // and stays that way until someone in it records something, because an archive
        // that invents relatives is not an archive.
        // TODO(DAT-2): remove once real sync populates the local database.
        if (familyId == ServiceLocator.DEMO_FAMILY_ID) {
            viewModelScope.launch { repo.seedDemoDataIfEmpty(familyId) }
        }
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
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: FeedViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val playState by viewModel.playback.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)
    ) {
        item {
            HomeHeader(
                familyName = viewModel.familyName,
                people = state.people,
                pendingSyncCount = state.pendingSyncCount,
                lenses = state.lenses,
                lens = state.lens,
                onChooseLens = viewModel::chooseLens,
                onOpenPerson = onOpenPerson,
                onOpenSettings = onOpenSettings
            )
        }

        if (state.isEmpty) {
            item { EmptyFeed(onRecord = onRecord) }
        } else {
            state.featured?.let { story ->
                item {
                    val path = state.audioPaths[story.storyId]
                    FeaturedStoryCard(
                        story = story,
                        audioPath = path,
                        isPlaying = playState.isPlaying && playState.storyId == story.storyId,
                        onTogglePlay = {
                            path?.let { viewModel.playback.toggle(story.storyId, it) }
                        },
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
                            val path = state.audioPaths[post.storyId]
                            RecentMemoryCard(
                                post = post,
                                audioPath = path,
                                isPlaying = playState.isPlaying &&
                                    playState.storyId == post.storyId,
                                onTogglePlay = {
                                    path?.let { viewModel.playback.toggle(post.storyId, it) }
                                },
                                onClick = { onOpenStory(post.storyId) }
                            )
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
    familyName: String,
    pendingSyncCount: Int,
    lenses: List<FeedLens>,
    lens: FeedLens,
    onChooseLens: (FeedLens) -> Unit,
    onOpenPerson: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(ArvHero.container)
            .padding(top = 8.dp, bottom = 18.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                // The archive says whose it is. ActiveSession has carried familyName since
                // onboarding and it was rendered nowhere, while every family saw the same
                // hardcoded words on the first screen of their own archive.
                familyName,
                style = MaterialTheme.typography.displaySmall,
                color = ArvHero.on,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.weight(1f))
            // Was a painted "Invite family" icon with no onClick. Inviting is not built,
            // and a control that does nothing is worse than one fewer control, so the
            // space now goes to something that works.
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = "Settings",
                    tint = ArvHero.on
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        Row(
            Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Was a painted pill that did nothing. It now chooses whose stories the
            // page shows: the whole family, one side of it, or only your own.
            var lensMenuOpen by remember { mutableStateOf(false) }
            Box {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.Transparent,
                    border = androidx.compose.foundation.BorderStroke(1.dp, ArvHero.on.copy(alpha = 0.55f)),
                    onClick = { lensMenuOpen = true }
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            lens.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = ArvHero.on
                        )
                        Icon(
                            Icons.Outlined.KeyboardArrowDown,
                            contentDescription = "Choose whose stories to show",
                            tint = ArvHero.on,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                DropdownMenu(
                    expanded = lensMenuOpen,
                    onDismissRequest = { lensMenuOpen = false }
                ) {
                    lenses.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                onChooseLens(option)
                                lensMenuOpen = false
                            }
                        )
                    }
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
                            .background(ArvHero.on.copy(alpha = 0.12f))
                            .border(2.dp, ArvHero.accent, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            person.displayName.split(" ")
                                .mapNotNull { it.firstOrNull()?.uppercase() }
                                .take(2).joinToString(""),
                            style = MaterialTheme.typography.titleLarge,
                            color = ArvHero.on
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        person.shortName(),
                        style = MaterialTheme.typography.labelLarge,
                        color = ArvHero.on,
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
                border = androidx.compose.foundation.BorderStroke(1.dp, ArvHero.on.copy(alpha = 0.35f)),
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Outlined.CloudOff,
                        contentDescription = null,
                        tint = ArvHero.on
                    )
                    Column(Modifier.weight(1f)) {
                        // Says what is true today. Nothing drains the outbox yet, so
                        // "waiting to upload" promised a queue that is moving toward
                        // somewhere, and after ten recordings the home screen claimed
                        // twenty memories were pending on a phone with no upload path.
                        // This screen exists to build trust; the old wording spent it.
                        //
                        // TODO(DAT-2): once a sync worker exists, this goes back to
                        // counting genuinely pending uploads.
                        val label =
                            if (pendingSyncCount == 1) "1 memory saved on this phone"
                            else "$pendingSyncCount memories saved on this phone"
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ArvHero.on
                        )
                        // The sentence the whole offline design exists to earn.
                        Text(
                            "Nothing is lost.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ArvHero.on.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
    }
}

/** The big story card: serif title over deep green, play row, star ribbon. */
@Composable
private fun FeaturedStoryCard(
    story: Story,
    audioPath: String?,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                        listOf(ArvHero.containerBright, ArvHero.container)
                    )
                )
        ) {
            // Star ribbon, top right. Featured means a keeper pinned it.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 0.dp, end = 20.dp)
                    .background(
                        ArvHero.cta,
                        RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 10.dp)
            ) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "Featured",
                    tint = ArvHero.on,
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
                    color = ArvHero.on
                )
                Text(
                    buildString {
                        append(story.eraLabel)
                        story.placeLabel?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = ArvHero.on.copy(alpha = 0.85f)
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // A play button on the card people see first has to actually play.
                    // Dimmed rather than hidden when there is no file, so the card keeps
                    // its shape and the reason is legible instead of mysterious.
                    val playable = audioPath != null
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (playable) ArvHero.on else ArvHero.on.copy(alpha = 0.4f))
                            .clickable(enabled = playable, onClick = onTogglePlay),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = when {
                                !playable -> "No audio for this story"
                                isPlaying -> "Pause"
                                else -> "Play"
                            },
                            tint = ArvHero.ink
                        )
                    }
                    StaticWaveform(
                        seed = story.storyId,
                        color = ArvHero.on.copy(alpha = 0.75f),
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp)
                    )
                    if (story.durationMs > 0) {
                        Text(
                            formatElapsed(story.durationMs),
                            style = MaterialTheme.typography.labelLarge,
                            color = ArvHero.on
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
        colors = CardDefaults.cardColors(containerColor = ArvHero.container)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                Icons.Outlined.Mic,
                contentDescription = null,
                tint = ArvHero.accent,
                modifier = Modifier.size(44.dp)
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Ask $askName:",
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = ArvHero.accent
                )
                Text(
                    question,
                    style = MaterialTheme.typography.headlineSmall,
                    color = ArvHero.on
                )
                Button(
                    onClick = onRecord,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ArvHero.cta,
                        contentColor = ArvHero.on
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
private fun RecentMemoryCard(
    post: Story,
    audioPath: String?,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onClick: () -> Unit
) {
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
                    color = ArvHero.ink.copy(alpha = 0.75f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                ) {
                    Text(
                        post.eraLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = ArvHero.on,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                if (post.kind == StoryKind.AUDIO) {
                    val playable = audioPath != null
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(ArvHero.ink.copy(alpha = if (playable) 0.75f else 0.35f))
                            .clickable(enabled = playable, onClick = onTogglePlay),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = when {
                                !playable -> "No audio for this story"
                                isPlaying -> "Pause"
                                else -> "Play"
                            },
                            tint = ArvHero.on,
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
