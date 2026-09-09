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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.arv.app.core.model.ConsentMethod
import java.text.DateFormat
import java.util.Date
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
import kotlinx.coroutines.launch
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

    /**
     * The uncertain links touching this person: every question mark on the page, as a
     * list somebody can actually answer. Imported research arrives full of these, and
     * verifying them is meant to be a feature, not a chore the app never offers.
     */
    val unconfirmed: StateFlow<List<com.arv.app.core.model.Relationship>> =
        repo.observeRelationships(familyId)
            .map { edges ->
                edges.filter {
                    it.uncertain &&
                        (it.fromPersonId == personId || it.toPersonId == personId)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun confirm(edge: com.arv.app.core.model.Relationship) {
        viewModelScope.launch {
            repo.confirmRelationship(
                familyId, edge.fromPersonId, edge.toPersonId, edge.kind,
                ServiceLocator.viewer.userId, System.currentTimeMillis()
            )
        }
    }

    fun reject(edge: com.arv.app.core.model.Relationship) {
        viewModelScope.launch {
            repo.removeRelationship(
                familyId, edge.fromPersonId, edge.toPersonId, edge.kind,
                ServiceLocator.viewer.userId, System.currentTimeMillis()
            )
        }
    }

    /** Whether this account may write down what the person said. One rule, in MemoryAccess. */
    fun mayRecordConsent(p: Person): Boolean = MemoryAccess.canRecordConsent(p, viewer)

    fun recordConsent(granted: Boolean, postMortemOk: Boolean, method: ConsentMethod) {
        viewModelScope.launch {
            repo.recordConsent(
                personId, granted, postMortemOk, method,
                ServiceLocator.viewer, System.currentTimeMillis()
            )
        }
    }
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
    onOpenPerson: (String) -> Unit = {},
    viewModel: PersonDetailViewModel = viewModel()
) {
    val person by viewModel.person.collectAsStateWithLifecycle()
    val recordedMs by viewModel.recordedMs.collectAsStateWithLifecycle()
    val stories by viewModel.stories.collectAsStateWithLifecycle()
    val frame by viewModel.frame.collectAsStateWithLifecycle()
    val everyone by viewModel.everyone.collectAsStateWithLifecycle()
    val edges by viewModel.edges.collectAsStateWithLifecycle()
    val unconfirmed by viewModel.unconfirmed.collectAsStateWithLifecycle()

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
        } else {
            item {
                ConsentCard(
                    person = p,
                    everyone = everyone,
                    canRecord = viewModel.mayRecordConsent(p),
                    onRecord = viewModel::recordConsent,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }

        if (unconfirmed.isNotEmpty()) {
            item {
                UnconfirmedLinks(
                    person = p,
                    edges = unconfirmed,
                    everyone = everyone,
                    onConfirm = viewModel::confirm,
                    onReject = viewModel::reject,
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
                // The accent does the wayfinding. Small text, full strength: this is
                // where Aurora's pop lives without ever touching a whole surface.
                color = MaterialTheme.colorScheme.primary
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
                        border = androidx.compose.material3.AssistChipDefaults.assistChipBorder(
                            enabled = true,
                            borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                        ),
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
                        color = MaterialTheme.colorScheme.primary
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
                    color = MaterialTheme.colorScheme.primary
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
                            // A whisper of the theme on every edge, and the counter color
                            // on anything still carrying a question mark, so the unproven
                            // links are findable across a whole tree at a glance.
                            border = androidx.compose.material3.AssistChipDefaults.assistChipBorder(
                                enabled = true,
                                borderColor = if (node.viaUncertain)
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.65f)
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                            ),
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

/**
 * Every question mark on the page, asked as a question somebody can answer.
 *
 * The sentence reads the same from either end of the edge, so it is correct on both
 * people's pages. Confirming keeps the link and drops the doubt; "Not right" removes the
 * claim entirely, because a link the family has rejected is somebody else's mistake, not
 * a disputed fact worth drawing.
 */
@Composable
private fun UnconfirmedLinks(
    person: Person,
    edges: List<com.arv.app.core.model.Relationship>,
    everyone: List<Person>,
    onConfirm: (com.arv.app.core.model.Relationship) -> Unit,
    onReject: (com.arv.app.core.model.Relationship) -> Unit,
    modifier: Modifier = Modifier
) {
    fun name(id: String) =
        everyone.firstOrNull { it.personId == id }?.displayName ?: "Somebody"

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Worth confirming", style = MaterialTheme.typography.titleMedium)
        Text(
            "These came from records nobody has checked yet. If you know, say so.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        edges.forEach { edge ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        claimSentence(edge, ::name),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = { onConfirm(edge) },
                            border = androidx.compose.material3.AssistChipDefaults.assistChipBorder(
                                enabled = true,
                                borderColor = MaterialTheme.colorScheme.primary
                            ),
                            label = {
                                Text("That's right", color = MaterialTheme.colorScheme.primary)
                            }
                        )
                        AssistChip(
                            onClick = { onReject(edge) },
                            label = { Text("Not right") }
                        )
                    }
                }
            }
        }
    }
}

/** The claim in plain words. Edge direction reads "from is the kind of to". */
private fun claimSentence(
    edge: com.arv.app.core.model.Relationship,
    name: (String) -> String
): String {
    val from = name(edge.fromPersonId)
    val to = name(edge.toPersonId)
    return when (edge.kind) {
        com.arv.app.core.model.RelationshipKind.PARENT -> "$from is $to's parent?"
        com.arv.app.core.model.RelationshipKind.CHILD -> "$to is $from's parent?"
        com.arv.app.core.model.RelationshipKind.GRANDPARENT -> "$from is $to's grandparent?"
        com.arv.app.core.model.RelationshipKind.GRANDCHILD -> "$to is $from's grandparent?"
        com.arv.app.core.model.RelationshipKind.SIBLING -> "$from and $to are siblings?"
        com.arv.app.core.model.RelationshipKind.AUNT_UNCLE -> "$from is $to's aunt or uncle?"
        com.arv.app.core.model.RelationshipKind.NIECE_NEPHEW -> "$from is $to's niece or nephew?"
        com.arv.app.core.model.RelationshipKind.COUSIN -> "$from and $to are cousins?"
        com.arv.app.core.model.RelationshipKind.SPOUSE -> "$from and $to are married?"
        com.arv.app.core.model.RelationshipKind.PARTNER -> "$from and $to are partners?"
        com.arv.app.core.model.RelationshipKind.CHOSEN -> "$from is chosen family to $to?"
        com.arv.app.core.model.RelationshipKind.OTHER -> "$from and $to are connected?"
    }
}

/**
 * Screen 14, the part that lives on the person: what they said about their memories being
 * kept here, who wrote it down, and how. The label above this has promised since the flag
 * was added that memories stay restricted until a decision exists. This is where the
 * decision gets made, so the promise stops being a dead end.
 */
@Composable
private fun ConsentCard(
    person: Person,
    everyone: List<Person>,
    canRecord: Boolean,
    onRecord: (granted: Boolean, postMortemOk: Boolean, method: ConsentMethod) -> Unit,
    modifier: Modifier = Modifier
) {
    var asking by remember { mutableStateOf(false) }
    val decided = !person.needsAConsentDecision

    val headline: String
    val detail: String?
    val tone: Color
    when {
        person.consentDeclined -> {
            headline = if (person.isDeceased) {
                "The family decided their memories should not be shared. They stay restricted."
            } else {
                "They asked that their memories not be shared. They stay restricted."
            }
            detail = null
            tone = MaterialTheme.colorScheme.error
        }
        decided -> {
            headline = if (person.isDeceased) {
                "The family decided their memories may be kept and shared here."
            } else {
                "They agreed to their memories being kept and shared here."
            }
            detail = when {
                person.isDeceased -> null
                person.postMortemOk -> "Sharing continues after their death."
                else -> "Sharing stops at their death unless someone decides otherwise."
            }
            tone = MaterialTheme.colorScheme.onSurfaceVariant
        }
        else -> {
            headline = if (person.isDeceased) {
                // They cannot grant one. Demanding it in red implies they refused.
                "Nobody has recorded what they would have wanted. Their memories " +
                    "stay restricted until somebody does."
            } else {
                "No consent record on file. Their memories stay restricted until one exists."
            }
            detail = null
            tone = MaterialTheme.colorScheme.error
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(headline, style = MaterialTheme.typography.bodySmall, color = tone)
        detail?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        recordedLine(person, everyone)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (canRecord) {
            OutlinedButton(onClick = { asking = true }) {
                Text(
                    when {
                        decided -> "Change their answer"
                        person.isDeceased -> "Record what they would have wanted"
                        else -> "Record their answer"
                    }
                )
            }
        }
    }

    if (asking) {
        ConsentDialog(
            person = person,
            onDismiss = { asking = false },
            onSave = { granted, postMortemOk, method ->
                onRecord(granted, postMortemOk, method)
                asking = false
            }
        )
    }
}

/** "Recorded 5 Sep 2026, in person, by Dana." Only the parts the record actually holds. */
private fun recordedLine(person: Person, everyone: List<Person>): String? {
    val at = person.consentDecidedAt ?: return null
    val how = when (person.consentMethod) {
        ConsentMethod.IN_PERSON -> "in person"
        ConsentMethod.ON_RECORDING -> "on a recording"
        ConsentMethod.IN_WRITING -> "in writing"
        ConsentMethod.ON_THEIR_BEHALF -> "on their behalf"
        null -> null
    }
    val by = person.consentRecordedBy?.let { uid ->
        everyone.firstOrNull { it.linkedUserId == uid }?.displayName
    }
    return buildString {
        append("Recorded ").append(DateFormat.getDateInstance().format(Date(at)))
        how?.let { append(", ").append(it) }
        by?.let { append(", by ").append(it) }
        append(".")
    }
}

/**
 * The question, asked once, in the words the person would recognise. Yes or no, how it
 * reached the archive, and for the living whether sharing goes on after they die. For
 * someone who cannot be asked the method is fixed to ON_THEIR_BEHALF and says so.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConsentDialog(
    person: Person,
    onDismiss: () -> Unit,
    onSave: (granted: Boolean, postMortemOk: Boolean, method: ConsentMethod) -> Unit
) {
    var answer by remember { mutableStateOf<Boolean?>(null) }
    var method by remember {
        mutableStateOf(if (person.isDeceased) ConsentMethod.ON_THEIR_BEHALF else null)
    }
    var afterDeath by remember { mutableStateOf(person.postMortemOk) }
    val first = person.displayName.substringBefore(' ')

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (person.isDeceased) "What would $first have wanted?" else "What did $first say?")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (person.isDeceased) {
                        "Whether their memories may be kept here and shared with the family. " +
                            "This is the family's decision on their behalf, and it is written down as that."
                    } else {
                        "Whether their memories may be kept here and shared with the family. " +
                            "Write down what they said, not what you hope they meant."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = answer == true, onClick = { answer = true }, label = { Text("Yes") })
                    FilterChip(selected = answer == false, onClick = { answer = false }, label = { Text("No") })
                }
                if (!person.isDeceased) {
                    Text("How did they tell you?", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MethodChip("In person", ConsentMethod.IN_PERSON, method) { method = it }
                        MethodChip("On a recording", ConsentMethod.ON_RECORDING, method) { method = it }
                        MethodChip("In writing", ConsentMethod.IN_WRITING, method) { method = it }
                    }
                    if (answer == true) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Switch(checked = afterDeath, onCheckedChange = { afterDeath = it })
                            Text("Keep sharing after they die", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            val a = answer
            val m = method
            Button(
                enabled = a != null && m != null,
                onClick = {
                    if (a != null && m != null) {
                        onSave(a, if (person.isDeceased) a else (a && afterDeath), m)
                    }
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun MethodChip(
    label: String,
    value: ConsentMethod,
    selected: ConsentMethod?,
    onPick: (ConsentMethod) -> Unit
) {
    FilterChip(selected = selected == value, onClick = { onPick(value) }, label = { Text(label) })
}
