package com.arv.app.feature.people

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.material3.AssistChip
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
import com.arv.app.core.ai.Lineage
import com.arv.app.core.ai.TreeFrame
import com.arv.app.ui.theme.ArvHero
import com.arv.app.core.di.ServiceLocator
import com.arv.app.core.model.MemberRole
import com.arv.app.core.model.Person
import com.arv.app.core.model.Story
import com.arv.app.core.model.StoryKind
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class PersonDetailViewModel(
    app: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(app) {

    private val personId: String = savedStateHandle["personId"] ?: ""
    private val repo = ServiceLocator.storyRepository(app)
    private val familyId = ServiceLocator.familyId

    // One definition, in ServiceLocator. Four screens each building their own
    // Viewer is four chances to disagree about what someone may read.
    private val viewer = ServiceLocator.viewer

    val person: StateFlow<Person?> =
        repo.observePeople(familyId)
            .map { people -> people.firstOrNull { it.personId == personId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The family standing around this person: parents above, children below, siblings
     * beside. Recentring on somebody is this same function with a different argument,
     * which is what makes every person a tree as well as a leaf in everyone else's.
     */
    val edges: StateFlow<List<com.arv.app.core.model.Relationship>> =
        repo.observeRelationships(familyId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val frame: StateFlow<TreeFrame.Frame?> =
        repo.observeRelationships(familyId)
            .map { edges -> TreeFrame.frameFor(personId, edges) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val everyone: StateFlow<List<Person>> =
        repo.observePeople(familyId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recordedMs: StateFlow<Long> =
        repo.observeRecordedMsFor(familyId, personId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /**
     * Everything they told and everything told about them, under the same permission
     * filter as every other surface. Being on someone's profile grants nothing extra.
     */
    val stories: StateFlow<List<Story>> =
        repo.observeRecent(familyId)
            .combine(repo.observePeople(familyId)) { stories, people ->
                stories.filter { story ->
                    (personId in story.narratorIds || personId in story.subjectPersonIds) &&
                        MemoryAccess.canRead(story, viewer, people)
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
    onOpenPerson: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: PersonDetailViewModel = viewModel()
) {
    val person by viewModel.person.collectAsStateWithLifecycle()
    val recordedMs by viewModel.recordedMs.collectAsStateWithLifecycle()
    val stories by viewModel.stories.collectAsStateWithLifecycle()
    val frame by viewModel.frame.collectAsStateWithLifecycle()
    val everyone by viewModel.everyone.collectAsStateWithLifecycle()
    val edges by viewModel.edges.collectAsStateWithLifecycle()

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
                    .background(ArvHero.container)
                    .padding(horizontal = 16.dp, vertical = 20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(ArvHero.on.copy(alpha = 0.12f))
                            .border(2.dp, ArvHero.accent, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            p.displayName.split(" ")
                                .mapNotNull { it.firstOrNull()?.uppercase() }
                                .take(2).joinToString(""),
                            style = MaterialTheme.typography.titleLarge,
                            color = ArvHero.on
                        )
                    }
                    Spacer(Modifier.size(16.dp))
                    Column {
                        Text(
                            p.displayName,
                            style = MaterialTheme.typography.headlineMedium,
                            color = ArvHero.on
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
                                color = ArvHero.on.copy(alpha = 0.85f)
                            )
                        }
                        if (p.isDeceased) {
                            Spacer(Modifier.height(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = ArvHero.cta
                            ) {
                                Text(
                                    "MEMORIAL",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ArvHero.on,
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
                            1.dp, ArvHero.on.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(shape = RoundedCornerShape(4.dp), color = ArvHero.accent) {
                                Text(
                                    "THEIR VOICE",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ArvHero.container,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                "${formatPreserved(recordedMs)} preserved",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ArvHero.on
                            )
                        }
                    }
                }
            }
        }

        frame?.let { f ->
            if (f.nodes.size > 1) {
                item {
                    FamilyAround(
                        frame = f,
                        everyone = everyone,
                        edges = edges,
                        onOpenPerson = onOpenPerson
                    )
                }
            }
        }

        if (p.isPublicRecord) {
            item {
                Text(
                    "What this archive knows about them came from published record. " +
                        "Nobody's permission is needed for that, and they cannot be asked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        } else if (p.needsAConsentDecision) {
            item {
                Text(
                    if (p.isDeceased) {
                        // They cannot grant one. Demanding it in red implies they refused.
                        "Nobody has recorded what they would have wanted. Their memories " +
                            "stay restricted until somebody does."
                    } else {
                        "No consent record on file. Their memories stay restricted until one exists."
                    },
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
                        .background(ArvHero.container),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Play",
                        tint = ArvHero.on,
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
                color = if (personId in story.narratorIds) ArvHero.accent
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

/**
 * The family standing around one person, oldest at the top.
 *
 * Tapping anybody recentres on them, which is the whole idea: the same edges seen from
 * somewhere else. Open your father and you are a name under him; open yourself and he is a
 * name above you. Nothing is recomputed but the point of view.
 */
@Composable
private fun FamilyAround(
    frame: TreeFrame.Frame,
    everyone: List<Person>,
    edges: List<com.arv.app.core.model.Relationship>,
    onOpenPerson: (String) -> Unit
) {
    val nameOf = { id: String ->
        everyone.firstOrNull { it.personId == id }?.displayName ?: "Unknown"
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Family around them", style = MaterialTheme.typography.titleMedium)

        // Read once per drawing so every age on the page counts from the same year.
        val thisYear = remember { java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) }

        // Marriages and partnerships, drawn where a family expects them: with the person,
        // not on a line of descent, because they are not one. Derived co-parents get a
        // label that says what the archive actually knows.
        val partners = Lineage.partnersOf(frame.centrePersonId, edges)
        listOf(
            "Married to" to partners.married,
            "Partner" to partners.partnered,
            "Children together" to partners.coParents
        ).forEach { (label, ids) ->
            if (ids.isEmpty()) return@forEach
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ids.forEach { id ->
                    AssistChip(
                        onClick = { onOpenPerson(id) },
                        label = {
                            val who = everyone.firstOrNull { it.personId == id }
                            Text(
                                buildString {
                                    append(nameOf(id))
                                    lifespan(who, thisYear)?.let { append("  ").append(it) }
                                },
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    )
                }
            }
        }

        // Everyone has parents, so an empty Parents row is always missing data and gets
        // said out loud. -1 and 0 are forced in because a person with nothing recorded
        // upward has no -1 generation in the frame at all, and the gap most worth naming
        // is exactly the one the data cannot draw.
        (frame.generations + listOf(-1)).distinct().sorted().forEach { g ->
            // The direct line and the branches off it share a row but are not the same
            // relationship, so each gets its own heading rather than one mixed list.
            listOf(false, true).forEach { off ->
                // The page is about this person, so they are not one of the names on it.
                // Guarding on the row's size instead only caught people who had no siblings
                // recorded; everyone else was listed as their own sibling.
                val row = (if (off) frame.sideways(g) else frame.direct(g))
                    .filter { it.personId != frame.centrePersonId }
                if (row.isEmpty() && g == -1 && !off) {
                    Text(
                        "Parents",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Nobody recorded yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    return@forEach
                }
                if (row.isEmpty()) return@forEach

                // Split by side of the family, worked out from whoever the page is centred
                // on. The same grandmother is paternal to one grandchild and maternal to
                // another, so this is derived per page and never stored.
                val groups: List<Pair<String?, List<TreeFrame.Node>>> =
                    // A parent is the side, so that row is not split. Everything above the
                    // parents is, and so are the aunts and uncles beside them.
                    if (g <= -2 || (g == -1 && off)) {
                        val bySide = row.groupBy {
                            Lineage.sideOf(it.personId, frame.centrePersonId, edges)
                        }
                        val covered = bySide.keys.flatten().toSet()

                        // A side with nobody on it keeps its heading, so an unentered
                        // branch reads as missing data rather than a missing feature. Only
                        // once some side of this row is known, so a page with no
                        // grandparents at all stays quiet.
                        val empty =
                            if (covered.isEmpty()) emptyList()
                            else Lineage.immediateParents(frame.centrePersonId, edges)
                                .filter { it !in covered }
                                .map { setOf(it) to emptyList<TreeFrame.Node>() }

                        (bySide.toList() + empty)
                            .map { (ids, people) ->
                                // First names. Families say "dad's side", not a surname.
                                ids.mapNotNull { id ->
                                    everyone.firstOrNull { it.personId == id }
                                        ?.displayName?.substringBefore(' ')
                                }.sorted() to people
                            }
                            // Stable order, so a side keeps its position down the page and
                            // anyone unplaced falls to the bottom.
                            .sortedBy { (names, _) ->
                                names.joinToString(" ").ifBlank { "￿" }
                            }
                            .map { (names, people) ->
                                names.joinToString(" and ").ifBlank { null } to people
                            }
                    } else {
                        listOf(null to row)
                    }

                groups.forEach { (side, people) ->
                Text(
                    buildString {
                        append(if (off) sidewaysLabel(g) else generationLabel(g))
                        side?.let { append(", ").append(it).append("'s side") }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (people.isEmpty()) {
                    // Names the gap rather than dropping the heading.
                    Text(
                        "Nobody recorded yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Wraps rather than scrolling sideways. A horizontal scroll hid relatives
                // off the edge behind a gesture nothing on the page suggested.
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    people.forEach { node ->
                        val isCentre = node.personId == frame.centrePersonId
                        AssistChip(
                            onClick = { if (!isCentre) onOpenPerson(node.personId) },
                            label = {
                                val who = everyone.firstOrNull { it.personId == node.personId }
                                Text(
                                    buildString {
                                        append(nameOf(node.personId))
                                        lifespan(who, thisYear)?.let { append("  ").append(it) }
                                        if (node.viaUncertain) append("  ?")
                                    },
                                    style = if (isCentre) MaterialTheme.typography.labelLarge
                                    else MaterialTheme.typography.labelMedium
                                )
                            }
                        )
                    }
                }
                }
            }
        }
    }
}

/**
 * What a family would actually call that row.
 *
 * "Great-great-great-grandparents" stops being readable somewhere around the fourth great,
 * so past that it counts them instead, the same way the imported records already do with
 * "3x great-grandmother".
 */
private fun generationLabel(g: Int): String = when (g) {
    0 -> "Siblings"
    -1 -> "Parents"
    -2 -> "Grandparents"
    -3 -> "Great-grandparents"
    -4 -> "Great-great-grandparents"
    1 -> "Children"
    2 -> "Grandchildren"
    3 -> "Great-grandchildren"
    else -> if (g < 0) "${-g - 2}x great-grandparents" else "${g - 2}x great-grandchildren"
}

/** What a family calls the people one step off the direct line. */
private fun sidewaysLabel(g: Int): String = when (g) {
    0 -> "Cousins"
    -1 -> "Aunts and uncles"
    -2 -> "Great-aunts and great-uncles"
    1 -> "Nieces and nephews"
    else -> if (g < 0) "Further back, off the direct line" else "Further on, off the direct line"
}

/**
 * Years, and whether the archive records a death.
 *
 * Nobody is labelled living. A profile is marked living by default when it is created, so
 * the word would report a default as a finding. Someone with no dates gets nothing.
 */
private fun lifespan(person: Person?, thisYear: Int): String? {
    if (person == null) return null
    val born = person.birthYear
    // A death the family could only place within a year or two prints as both years. The
    // alternative is choosing one, which reads as a date the archive is standing behind.
    val died = person.deathYear?.let { year ->
        person.deathYearEnd?.let { "$year or $it" } ?: "$year"
    }
    return when {
        born != null && died != null -> "$born to $died"
        person.isDeceased && born != null && died == null -> "born $born, died"
        person.isDeceased && died != null -> "died $died"
        person.isDeceased -> "died"
        // An age prints only while it is believable. Past that the record is not a living
        // person, it is a death nobody entered.
        born != null && thisYear - born in 0..MAX_BELIEVABLE_AGE -> "born $born, ${thisYear - born}"
        born != null -> "born $born"
        else -> null
    }
}

private const val MAX_BELIEVABLE_AGE = 110
