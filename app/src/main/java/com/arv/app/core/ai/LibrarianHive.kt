package com.arv.app.core.ai

import com.arv.app.core.model.ArchiveArea
import com.arv.app.core.model.LibrarianAnswer
import com.arv.app.core.model.LibrarianScope
import com.arv.app.core.model.Person
import com.arv.app.core.model.Story
import com.arv.app.core.model.TranscriptSegment

/**
 * The hive: retrieval reorganized into shelf librarians that get routed to, instead of one
 * librarian scoring the whole archive flat.
 *
 * Every person, every era, and every archive area gets its own librarian. A question comes
 * in, each shelf looks at it, and the shelves that recognize something nominate memories
 * from their own slice with the reason stated. Nominations for the same memory add up
 * across shelves, so a memory that a person shelf, an era shelf, and an area shelf all
 * point to outranks any single-signal match.
 *
 * The weights are identical to [LocalLibrarianService], deliberately: the hive is an
 * accountability structure over the same scoring, not a different ranking. What it adds is
 * the route. The answer names which shelves it came through, so a family member can see
 * how the librarian found what it found. Retrieval that cannot explain itself has no place
 * in an archive built on provenance.
 *
 * Still deterministic, still entirely on device, still zero network.
 */
class LibrarianHive(
    private val storiesProvider: suspend (familyId: String) -> List<Story>,
    private val peopleProvider: suspend (familyId: String) -> List<Person>,
    private val segmentsForStory: suspend (storyId: String) -> List<TranscriptSegment>
) : LibrarianService {

    override suspend fun ask(
        question: String,
        scope: LibrarianScope,
        viewer: Viewer,
        familyId: String
    ): LibrarianOutcome {
        val all = storiesProvider(familyId)
        if (all.isEmpty()) return LibrarianOutcome.NoMatches

        val people = peopleProvider(familyId)
        val parsed = QuestionParse.of(question, people)

        // Route: every shelf sees the question; a shelf that recognizes nothing in it
        // stays silent. Silence is a routing decision, not an error.
        val nominationsByShelf = buildShelves(all, people)
            .map { shelf -> shelf to shelf.nominate(parsed) }
            .filter { (_, nominations) -> nominations.isNotEmpty() }
        if (nominationsByShelf.isEmpty()) return LibrarianOutcome.NoMatches

        // Blend before permission, so the withheld count stays honest: "shelves matched
        // it, and you may not read it" is real information.
        val blendedScore = HashMap<String, Int>()
        val storiesById = HashMap<String, Story>()
        nominationsByShelf.forEach { (_, nominations) ->
            nominations.forEach { nomination ->
                blendedScore.merge(nomination.story.storyId, nomination.score, Int::plus)
                storiesById[nomination.story.storyId] = nomination.story
            }
        }

        val matched = blendedScore.keys.map { storiesById.getValue(it) }
        val (usable, withheldCount) = MemoryAccess.partition(matched, viewer, scope, people)
        if (usable.isEmpty()) return LibrarianOutcome.AllWithheld(withheldCount)

        val byScore = usable.sortedWith(
            compareByDescending<Story> { blendedScore.getValue(it.storyId) }
                .thenByDescending { it.createdAt }
        ).take(AnswerAssembly.MAX_SOURCES)

        // The route only names shelves that contributed to what is actually shown.
        // Naming a shelf whose nominations were all cut or withheld would leak that
        // something matched there.
        val chosenIds = byScore.map { it.storyId }.toSet()
        val route = nominationsByShelf
            .filter { (_, nominations) -> nominations.any { it.story.storyId in chosenIds } }
            .map { (shelf, _) -> shelf.shelfName }

        val sources = byScore.map { story ->
            AnswerAssembly.sourceFor(story, parsed, segmentsForStory)
        }

        return LibrarianOutcome.Answered(
            LibrarianAnswer(
                question = question,
                scope = scope,
                text = AnswerAssembly.composeLead(byScore, people, withheldCount),
                sources = sources,
                withheldCount = withheldCount,
                routedThrough = route
            )
        )
    }

    /**
     * Shelves are rebuilt from the live archive on every question. At family-archive
     * scale that costs nothing, and it means a person added a minute ago already has a
     * shelf. Persisting shelf indexes is an optimization for a scale this app has not
     * reached.
     */
    private fun buildShelves(stories: List<Story>, people: List<Person>): List<ShelfLibrarian> {
        val personShelves = people.map { PersonShelfLibrarian(it, stories) }

        val decades = stories.flatMap { story ->
            val start = story.eraStart ?: return@flatMap emptyList<Int>()
            val end = story.eraEnd ?: start
            (start / 10 * 10..end / 10 * 10 step 10).toList()
        }.distinct().sorted()
        val eraShelves = decades.map { EraShelfLibrarian(it, stories) }

        val areaShelves = ArchiveArea.entries
            .map { area -> area to stories.filter { it.area == area } }
            .filter { (_, slice) -> slice.isNotEmpty() }
            .map { (area, slice) -> AreaShelfLibrarian(area, slice, segmentsForStory) }

        return personShelves + eraShelves + areaShelves
    }
}

/**
 * A memory put forward by one shelf, with the reason stated. The reason is not
 * decoration: it is what makes the routing inspectable when someone asks why the
 * librarian surfaced what it surfaced.
 */
data class Nomination(
    val story: Story,
    val score: Int,
    val reason: String
)

/** One shelf in the hive. It only ever speaks about its own slice of the archive. */
interface ShelfLibrarian {
    val shelfName: String
    suspend fun nominate(parsed: QuestionParse): List<Nomination>
}

/**
 * Activated when the question names this person. "What did Ruth say" should beat any
 * word overlap, because that is how a family actually asks.
 */
class PersonShelfLibrarian(
    private val person: Person,
    private val stories: List<Story>
) : ShelfLibrarian {

    override val shelfName = "${person.displayName}'s shelf"

    override suspend fun nominate(parsed: QuestionParse): List<Nomination> {
        if (person.personId !in parsed.personIds) return emptyList()
        return buildList {
            stories.forEach { story ->
                if (person.personId in story.narratorIds) {
                    add(Nomination(story, 6, "told by ${person.displayName}"))
                }
                if (person.personId in story.subjectPersonIds) {
                    add(Nomination(story, 4, "about ${person.displayName}"))
                }
            }
        }
    }
}

/** Activated when a year in the question falls inside this decade. */
class EraShelfLibrarian(
    private val decadeStart: Int,
    private val stories: List<Story>
) : ShelfLibrarian {

    override val shelfName = "the ${decadeStart}s shelf"

    override suspend fun nominate(parsed: QuestionParse): List<Nomination> {
        val yearsHere = parsed.years.filter { it in decadeStart until decadeStart + 10 }
        if (yearsHere.isEmpty()) return emptyList()

        return stories.flatMap { story ->
            val start = story.eraStart ?: return@flatMap emptyList<Nomination>()
            val end = story.eraEnd ?: start
            yearsHere.filter { it in start..end }
                .map { year -> Nomination(story, 5, "its era covers $year") }
        }
    }
}

/**
 * Owns everything filed under one archive area and matches by words: titles, tags, and
 * what people actually said in the recordings. The transcript signal is what makes this
 * retrieval rather than filename search.
 */
class AreaShelfLibrarian(
    area: ArchiveArea,
    private val stories: List<Story>,
    private val segmentsForStory: suspend (storyId: String) -> List<TranscriptSegment>
) : ShelfLibrarian {

    override val shelfName = "the ${area.name.lowercase()} shelf"

    override suspend fun nominate(parsed: QuestionParse): List<Nomination> {
        if (parsed.terms.isEmpty()) return emptyList()

        return stories.mapNotNull { story ->
            var score = 0
            val matchedOn = mutableListOf<String>()

            val title = story.title.lowercase()
            parsed.terms.forEach { term ->
                if (title.contains(term)) {
                    score += 3
                    matchedOn += "\"$term\" in the title"
                }
                if (story.tags.any { it.lowercase().contains(term) }) {
                    score += 2
                    matchedOn += "the tag \"$term\""
                }
            }

            if (story.durationMs > 0) {
                val segments = segmentsForStory(story.storyId)
                val transcriptHits = segments.sumOf { segment ->
                    val text = segment.text.lowercase()
                    parsed.terms.count { text.contains(it) }
                }
                if (transcriptHits > 0) {
                    score += minOf(transcriptHits * 2, 8)
                    matchedOn += "words spoken in the recording"
                }
            }

            if (score <= 0) null
            else Nomination(story, score, "matched ${matchedOn.distinct().joinToString(", ")}")
        }
    }
}
