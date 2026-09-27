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
 * Every person, every era, every place, and every archive area gets its own librarian. A
 * question comes in, each shelf looks at it, and the shelves that recognize something
 * nominate memories from their own slice with the reason stated. Nominations for the same
 * memory add up across shelves, so a memory that a person shelf, an era shelf, and an area
 * shelf all point to outranks any single-signal match.
 *
 * The signals are the flat pipeline's, called from the same [Signals] code rather than
 * copied, so the two cannot rank differently. They once could: this file was written
 * with its own copy of the scoring and a comment promising the weights matched, and the
 * place signal did not make the trip. What the hive adds is the route. The answer names
 * which shelves it came through, so a family member can see how the librarian found what it
 * found. Retrieval that cannot explain itself has no place in an archive built on
 * provenance.
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
        val reasonsById = LinkedHashMap<String, MutableList<Reason>>()
        val storiesById = HashMap<String, Story>()
        nominationsByShelf.forEach { (_, nominations) ->
            nominations.forEach { nomination ->
                reasonsById.getOrPut(nomination.story.storyId) { mutableListOf() } += nomination.reason
                storiesById[nomination.story.storyId] = nomination.story
            }
        }

        val matched = reasonsById.keys.map { storiesById.getValue(it) }
        val (usable, withheldCount) = MemoryAccess.partition(matched, viewer, scope, people)
        if (usable.isEmpty()) return LibrarianOutcome.AllWithheld(withheldCount)

        val ranked = AnswerAssembly.rank(
            usable.map { AnswerAssembly.Match(it, reasonsById.getValue(it.storyId)) }
        )
        val shown = ranked.take(AnswerAssembly.MAX_SOURCES)

        // The route only names shelves that contributed to what is actually shown.
        // Naming a shelf whose nominations were all cut or withheld would leak that
        // something matched there.
        val chosenIds = shown.map { it.story.storyId }.toSet()
        val route = nominationsByShelf
            .filter { (_, nominations) -> nominations.any { it.story.storyId in chosenIds } }
            .map { (shelf, _) -> shelf.shelfName }

        val sources = shown.map { match ->
            AnswerAssembly.sourceFor(match, parsed, segmentsForStory)
        }

        return LibrarianOutcome.Answered(
            LibrarianAnswer(
                question = question,
                scope = scope,
                text = AnswerAssembly.composeLead(ranked, people, withheldCount),
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

        // One shelf per place, however it was typed. "Mom's house" and "mom’s house" are one
        // place, and the shelf takes its name from the first way somebody wrote it.
        val placeShelves = stories
            .filter { !it.placeLabel.isNullOrBlank() }
            .groupBy { Matching.normalize(it.placeLabel!!) }
            .filterKeys { it.isNotEmpty() }
            .map { (_, slice) -> PlaceShelfLibrarian(slice.first().placeLabel!!.trim(), slice) }

        val areaShelves = ArchiveArea.entries
            .map { area -> area to stories.filter { it.area == area } }
            .filter { (_, slice) -> slice.isNotEmpty() }
            .map { (area, slice) -> AreaShelfLibrarian(area, slice, segmentsForStory) }

        return personShelves + eraShelves + placeShelves + areaShelves
    }
}

/**
 * A memory put forward by one shelf, with the reason stated. The reason is not
 * decoration: it is what makes the routing inspectable when someone asks why the
 * librarian surfaced what it surfaced, and it is shown under the source.
 */
data class Nomination(
    val story: Story,
    val reason: Reason
) {
    val score: Int get() = reason.score
}

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
        return stories.flatMap { story ->
            Signals.person(story, person, parsed).map { Nomination(story, it) }
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
            Signals.era(story, yearsHere).map { Nomination(story, it) }
        }
    }
}

/**
 * Owns every memory saved with one place. Activated when the question names the place, or
 * shares a word with it, and says which.
 */
class PlaceShelfLibrarian(
    place: String,
    private val stories: List<Story>
) : ShelfLibrarian {

    override val shelfName = "the $place shelf"

    override suspend fun nominate(parsed: QuestionParse): List<Nomination> =
        stories.mapNotNull { story -> Signals.place(story, parsed)?.let { Nomination(story, it) } }
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
        return stories.flatMap { story ->
            (Signals.written(story, parsed) + listOfNotNull(Signals.spoken(story, parsed, segmentsForStory)))
                .map { Nomination(story, it) }
        }
    }
}
