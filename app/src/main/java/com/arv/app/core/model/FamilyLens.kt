package com.arv.app.core.model

import com.arv.app.core.ai.Lineage

/**
 * Which side of the family somebody is looking at.
 *
 * Lifted out of the feed, where it was written and proven, because the tree needed the
 * same control and building a second one would have meant two screens that disagree about
 * what "Ruth's side" means. One definition, one set of words, both surfaces.
 *
 * A side is named by a parent rather than stored as a group, for the reason
 * [com.arv.app.core.model.Story.branchRootPersonId] gives: a family is a web of trees, so
 * nobody sits in exactly one side, and the only version of "my mother's side" that
 * survives that is naming her and walking up from whoever is reading.
 */
data class FamilyLens(
    val label: String,
    /** The parent whose side this is. Null for the whole family and for just-me. */
    val parentId: String? = null,
    val mine: Boolean = false
) {
    companion object {
        val Whole = FamilyLens("Whole family")

        /**
         * The whole family, one entry per parent the viewer actually has, and just-me.
         *
         * Only real parents get an entry. Offering "your father's side" to somebody whose
         * father the archive has never heard of is a filter that returns nothing and
         * explains nothing.
         */
        fun optionsFor(
            meId: String?,
            people: List<Person>,
            edges: List<Relationship>
        ): List<FamilyLens> {
            if (meId == null) return listOf(Whole)
            val sides = Lineage.immediateParents(meId, edges).mapNotNull { parentId ->
                people.firstOrNull { it.personId == parentId }?.let { parent ->
                    FamilyLens("${parent.shortName()}'s side", parentId = parentId)
                }
            }
            return listOf(Whole) + sides + FamilyLens("Just me", mine = true)
        }

        /**
         * The chosen lens if it still exists, otherwise the whole family.
         *
         * A side can stop existing while somebody is looking at it, because removing a
         * parent edge removes the side it named. Falling back beats filtering by a ghost
         * and showing an empty screen with no explanation.
         */
        fun resolve(chosen: FamilyLens, options: List<FamilyLens>): FamilyLens =
            options.firstOrNull { it.parentId == chosen.parentId && it.mine == chosen.mine }
                ?: Whole
    }
}

/** The people this lens keeps. Pure, so both screens filter identically. */
fun List<Person>.underLens(
    lens: FamilyLens,
    meId: String?,
    edges: List<Relationship>
): List<Person> = when {
    lens.mine -> filter { it.personId == meId }
    lens.parentId == null || meId == null -> this
    else -> filter {
        it.personId == meId || it.personId == lens.parentId ||
            lens.parentId in Lineage.sideOf(it.personId, meId, edges)
    }
}

/**
 * "Ruth Delaney" is Ruth, but "Miss Opal" is never just "Miss". Names carry respect and
 * truncation is not allowed to strip it.
 *
 * Was private to the feed. It is here because the lens labels a side of the family with a
 * parent's short name, and a name shortened one way on one screen and another way on the
 * next is the kind of small wrongness a family notices immediately.
 */
private val honorifics = setOf("Miss", "Mr", "Mr.", "Mrs", "Mrs.", "Ms", "Ms.", "Dr", "Dr.")

fun Person.shortName(): String {
    val parts = displayName.split(" ")
    return when {
        parts.size <= 1 -> displayName
        parts.first() in honorifics -> parts.take(2).joinToString(" ")
        else -> parts.first()
    }
}

/**
 * Whether a typed query should find this person.
 *
 * Matches the name they go by, the names they also went by, and where they were born,
 * because those are the three things somebody actually remembers about a relative they are
 * hunting for. A maiden name lives in [Person.alsoKnownAs] and is very often the only name
 * an older relative is remembered by, so leaving it out would make search useless on
 * exactly the people an archive exists to hold.
 *
 * Blank matches everybody rather than nobody: an empty field is not a filter.
 */
fun Person.matchesSearch(query: String): Boolean {
    val q = query.trim()
    if (q.isBlank()) return true
    return displayName.contains(q, ignoreCase = true) ||
        alsoKnownAs.any { it.contains(q, ignoreCase = true) } ||
        birthPlace?.contains(q, ignoreCase = true) == true
}
