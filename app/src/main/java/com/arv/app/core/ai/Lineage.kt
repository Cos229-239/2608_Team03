package com.arv.app.core.ai

import com.arv.app.core.model.Relationship
import com.arv.app.core.model.RelationshipKind

/**
 * Walking the family graph.
 *
 * There is no tree in this app. There is a graph of people, and a tree is what you get when
 * you render that graph from somebody's point of view. Dana's tree and her father's tree
 * are the same edges drawn from a different centre: every person is the root of their own
 * view and a node in everyone else's, which is why nothing here has a root parameter.
 *
 * Pure functions over plain data, no Android and no database, so the rules that decide who
 * may read a family's private material can be tested exhaustively on the JVM.
 *
 * EDGE DIRECTION, since getting this backwards would invert the permission model:
 * an edge reads **from is the kind of to**. `Relationship(from = opal, to = dana,
 * kind = PARENT)` means Opal is Dana's parent. CHILD and GRANDCHILD are the same
 * statement written the other way round and are followed accordingly.
 */
object Lineage {

    /**
     * Everyone [personId] descends from, plus [personId] themselves.
     *
     * Self is included because a branch named after you is yours. Someone who marks a
     * memory as belonging to their own line must be able to read it, which the previous
     * implementation of BRANCH got wrong in a way that made recordings vanish from the
     * person who made them.
     */
    fun ancestorsOf(
        personId: String,
        relationships: List<Relationship>,
        maxGenerations: Int = MAX_GENERATIONS
    ): Set<String> {
        val parents = parentIndex(relationships)

        val seen = mutableSetOf(personId)
        var frontier = listOf(personId)
        var depth = 0

        // Breadth-first and bounded. Family data is entered by hand and by importers, and
        // a cycle ("her own grandmother") is a data-entry slip away. A permission check
        // must not be able to hang on one, so the visited set stops repeats and the depth
        // bound stops anything the visited set somehow misses.
        while (frontier.isNotEmpty() && depth < maxGenerations) {
            val next = mutableListOf<String>()
            for (child in frontier) {
                for (parent in parents[child].orEmpty()) {
                    if (seen.add(parent)) next += parent
                }
            }
            frontier = next
            depth++
        }
        return seen
    }

    /** True when [personId] is [ancestorId], or descends from them. */
    fun isDescendantOf(
        personId: String,
        ancestorId: String,
        relationships: List<Relationship>
    ): Boolean = ancestorId in ancestorsOf(personId, relationships)

    /**
     * Every ancestor who could name a branch, nearest first.
     *
     * What the recorder offers when someone says a memory belongs to one side of the
     * family. Excludes the person themselves: "my branch" meaning only me is what PRIVATE
     * is for, and offering both would be two names for one thing.
     */
    fun branchChoicesFor(
        personId: String,
        relationships: List<Relationship>
    ): List<String> = (ancestorsOf(personId, relationships) - personId).toList()

    /**
     * Which of your parents an ancestor sits behind: whose side of the family they are on.
     *
     * Derived, never stored. "3x great-grandmother" tells you how far up somebody is and
     * nothing about which of eight lines they belong to, and the answer changes depending
     * on who is asking: Dana's paternal grandmother is her cousin's maternal one. Storing
     * a side would be storing one person's viewpoint as if it were a property of somebody
     * else.
     *
     * Returns the immediate parents of [viewpointId] through whom [ancestorId] can be
     * reached. One parent means one side. Both means the lines converge, which happens in
     * real families more often than people expect. Empty means the archive does not yet
     * know how they connect, and that is a question worth asking someone rather than a
     * blank to fill in silently.
     */
    fun sidesOf(
        ancestorId: String,
        viewpointId: String,
        relationships: List<Relationship>
    ): Set<String> {
        // Strictly the immediate parents. parentIndex folds GRANDPARENT edges in as
        // ancestry, which is right for "who do I descend from" and wrong here: an imported
        // grandparent shortcut would make a grandmother one of your parents and then report
        // her as her own side of the family.
        val parents = immediateParents(viewpointId, relationships)
        if (ancestorId == viewpointId) return emptySet()
        return parents.filter { parent ->
            ancestorId == parent || ancestorId in ancestorsOf(parent, relationships)
        }.toSet()
    }

    /**
     * Which side of the family somebody came down, whether or not they are an ancestor.
     *
     * [sidesOf] answers this for people you descend from. It cannot answer it for an aunt,
     * because you do not descend from your aunt, and yet she is unmistakably on one side of
     * the family. So this widens the question: somebody is on a parent's side if they are
     * an ancestor of that parent, or if they share a parent with one of that parent's
     * ancestors, which is what makes a great-aunt land beside the great-grandparents she
     * grew up with.
     *
     * Computed from whoever is being looked at, never from a fixed person. Follow the same
     * grandmother down two different grandchildren and each of them is told the truth about
     * where she sits in their family, which is the whole reason none of this is stored.
     */
    fun sideOf(
        personId: String,
        viewpointId: String,
        relationships: List<Relationship>
    ): Set<String> {
        if (personId == viewpointId) return emptySet()
        val direct = sidesOf(personId, viewpointId, relationships)
        if (direct.isNotEmpty()) return direct

        val parents = immediateParents(viewpointId, relationships)
        return parents.filter { parent ->
            ancestorsOf(parent, relationships).any { line ->
                siblingKind(personId, line, relationships) != SiblingKind.NONE
            }
        }.toSet()
    }

    /** The other ends of somebody's stated SPOUSE and PARTNER edges, and the people they
     *  share a child with. */
    data class Partners(
        /** Stated married. */
        val married: List<String>,
        /** Stated partners who are not also stated married. */
        val partnered: List<String>,
        /** Share a child, with no stated edge. Derived, so labelled by the fact itself:
         *  the archive knows they raised children together and does not know they married. */
        val coParents: List<String>
    )

    /**
     * Who somebody built a family with.
     *
     * Spouses were never drawn anywhere: the frame walks lines of descent and a marriage
     * is not one, so a grandfather's page showed his children and grandchildren and not
     * the woman he was married to for fifty years, who stood in the archive four rows
     * away as those children's mother. Co-parents are derived from shared children
     * exactly so that gap cannot happen; what stays honest is the label, because sharing
     * a child proves the children and does not prove a wedding.
     */
    fun partnersOf(personId: String, relationships: List<Relationship>): Partners {
        val married = mutableListOf<String>()
        val partnered = mutableListOf<String>()
        for (r in relationships) {
            val other = when (personId) {
                r.fromPersonId -> r.toPersonId
                r.toPersonId -> r.fromPersonId
                else -> null
            } ?: continue
            when (r.kind) {
                RelationshipKind.SPOUSE -> married += other
                RelationshipKind.PARTNER -> partnered += other
                else -> Unit
            }
        }
        val stated = (married + partnered).toSet()
        val coParents = childrenOf(personId, relationships)
            .flatMap { child -> immediateParents(child, relationships) }
            .distinct()
            .filter { it != personId && it !in stated }
        return Partners(
            married = married.distinct(),
            partnered = partnered.distinct().filter { it !in married },
            coParents = coParents
        )
    }

    /** Strictly the people recorded as somebody's child. The inverse of [immediateParents]. */
    private fun childrenOf(
        personId: String,
        relationships: List<Relationship>
    ): List<String> = relationships.mapNotNull { r ->
        when (r.kind) {
            RelationshipKind.PARENT -> r.toPersonId.takeIf { r.fromPersonId == personId }
            RelationshipKind.CHILD -> r.fromPersonId.takeIf { r.toPersonId == personId }
            else -> null
        }
    }.distinct()

    /**
     * Strictly the people recorded as somebody's parent. No grandparent shortcuts.
     *
     * Public because a screen needs to name the sides of a family before it knows whether
     * anyone is standing on them. A side of the family with nobody recorded on it is not
     * the same as a side that does not exist, and only the parent list can tell them apart.
     */
    fun immediateParents(
        personId: String,
        relationships: List<Relationship>
    ): List<String> = relationships.mapNotNull { r ->
        when (r.kind) {
            RelationshipKind.PARENT -> r.fromPersonId.takeIf { r.toPersonId == personId }
            RelationshipKind.CHILD -> r.toPersonId.takeIf { r.fromPersonId == personId }
            else -> null
        }
    }.distinct()

    /**
     * True when nothing in the archive connects this person to the viewpoint at all.
     *
     * Deliberately not "is not an ancestor". An aunt has a place in the family and is not
     * an ancestor of anybody; listing her as unplaced would bury the people who genuinely
     * are floating under a pile of relatives who are already fine. This asks the weaker and
     * more useful question: is there any path, of any kind, from them to me.
     */
    fun isUnplaced(
        personId: String,
        viewpointId: String,
        relationships: List<Relationship>
    ): Boolean = !isConnected(personId, viewpointId, relationships)

    /** Any path at all, following every kind of relationship in both directions. */
    fun isConnected(
        a: String,
        b: String,
        relationships: List<Relationship>
    ): Boolean {
        if (a == b) return true
        val neighbours = mutableMapOf<String, MutableSet<String>>()
        for (r in relationships) {
            neighbours.getOrPut(r.fromPersonId) { mutableSetOf() }.add(r.toPersonId)
            neighbours.getOrPut(r.toPersonId) { mutableSetOf() }.add(r.fromPersonId)
        }
        val seen = mutableSetOf(a)
        val queue = ArrayDeque(listOf(a))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            for (n in neighbours[next].orEmpty()) {
                if (n == b) return true
                if (seen.add(n)) queue.addLast(n)
            }
        }
        return false
    }

    /** How two people are siblings, if they are at all. */
    enum class SiblingKind { FULL, HALF, STEP, NONE }

    /**
     * Works out how two people are siblings from the graph, instead of trusting a label.
     *
     * Imported records label people from whoever compiled them. The same woman is a
     * stepdaughter in one relative's paperwork and a half sister in another's, and both
     * are correct from where they were written. Copying either label into a third
     * person's tree is how an archive ends up confidently wrong about its own family.
     *
     * Shared parents do not depend on a viewpoint, so parents are what gets stored and this
     * is derived from them. Two or more shared parents is FULL, exactly one is HALF, none
     * shared but a parent on each side who are partners is STEP.
     */
    fun siblingKind(
        a: String,
        b: String,
        relationships: List<Relationship>
    ): SiblingKind {
        if (a == b) return SiblingKind.NONE
        val parents = parentIndex(relationships)
        val pa = parents[a].orEmpty().toSet()
        val pb = parents[b].orEmpty().toSet()
        if (pa.isEmpty() || pb.isEmpty()) return SiblingKind.NONE

        val shared = pa intersect pb
        return when {
            shared.size >= 2 -> SiblingKind.FULL
            shared.size == 1 -> SiblingKind.HALF
            partnered(pa, pb, relationships) -> SiblingKind.STEP
            else -> SiblingKind.NONE
        }
    }

    /** True when someone in [pa] is married to or partnered with someone in [pb]. */
    private fun partnered(
        pa: Set<String>,
        pb: Set<String>,
        relationships: List<Relationship>
    ): Boolean = relationships.any {
        (it.kind == RelationshipKind.SPOUSE || it.kind == RelationshipKind.PARTNER) &&
            ((it.fromPersonId in pa && it.toPersonId in pb) ||
                (it.fromPersonId in pb && it.toPersonId in pa))
    }

    /**
     * child -> their parents.
     *
     * GRANDPARENT edges are folded in as ancestry too. They are less precise than two
     * PARENT hops, but a family that recorded "this is my grandmother" and never filled in
     * the generation between them still knows she is an ancestor, and the archive should
     * not pretend otherwise.
     */
    private fun parentIndex(relationships: List<Relationship>): Map<String, List<String>> {
        val out = mutableMapOf<String, MutableList<String>>()
        fun link(child: String, parent: String) {
            out.getOrPut(child) { mutableListOf() }.add(parent)
        }
        for (r in relationships) {
            when (r.kind) {
                RelationshipKind.PARENT, RelationshipKind.GRANDPARENT ->
                    link(r.toPersonId, r.fromPersonId)
                RelationshipKind.CHILD, RelationshipKind.GRANDCHILD ->
                    link(r.fromPersonId, r.toPersonId)
                // Siblings, spouses, cousins, aunts and chosen family are all real
                // relationships and none of them are ancestry. Marrying into a family does
                // not make its private material yours to read.
                else -> Unit
            }
        }
        return out
    }

    /** Deeper than any family anyone has actually entered by hand. */
    private const val MAX_GENERATIONS = 32
}
