package com.arv.app.core.ai

import com.arv.app.core.model.Relationship
import com.arv.app.core.model.RelationshipKind

/**
 * One person's view of the family, laid out.
 *
 * This file must never import Story. It takes people and edges and returns positions.
 * Anything belonging to a memory is attached afterwards by the caller, past the permission
 * check, because sharing a person is not sharing a story and the drawing code is the last
 * place that should be able to blur it.
 *
 * Generation 0 is whoever you centred on, negative is older, positive is younger, so
 * y = generation * rowHeight puts ancestors above with no flipping. Recentring is not
 * navigation to a different object: it is the same pure function over the same edges with
 * a different first argument, which is what "every person is a tree and also a leaf in
 * everyone else's tree" means once it stops being a metaphor.
 */
object TreeFrame {

    data class Node(
        val personId: String,
        /** 0 is the centre, negative is older, positive is younger. */
        val generation: Int,
        /** Left-to-right position within that generation. */
        val slot: Int,
        /** True when the centre is reached from here through an edge marked uncertain. */
        val viaUncertain: Boolean = false,
        /**
         * Reached sideways rather than straight up or down: an aunt, an uncle, a cousin,
         * a niece. Same row as a blood generation, entirely different word for it, so the
         * screen has to be able to tell them apart.
         */
        val sideways: Boolean = false
    )

    data class Frame(
        val centrePersonId: String,
        val nodes: List<Node>
    ) {
        fun generation(g: Int): List<Node> = nodes.filter { it.generation == g }.sortedBy { it.slot }

        /** The direct line at that generation: parents, siblings, children. */
        fun direct(g: Int): List<Node> = generation(g).filter { !it.sideways }

        /** The ones off to the side at that generation: aunts, cousins, nieces. */
        fun sideways(g: Int): List<Node> = generation(g).filter { it.sideways }
        val generations: List<Int> get() = nodes.map { it.generation }.distinct().sorted()
    }

    /**
     * Builds the frame around [centrePersonId].
     *
     * [up] and [down] bound how far the view reaches, because a screen is not a genealogy
     * report and a 34 person archive still has lines that run five generations deep.
     */
    fun frameFor(
        centrePersonId: String,
        relationships: List<Relationship>,
        up: Int = 2,
        down: Int = 2
    ): Frame {
        val links = adjacency(relationships)

        val placed = LinkedHashMap<String, Int>()
        val shaky = mutableSetOf<String>()
        placed[centrePersonId] = 0

        // Two separate walks, each going one way only.
        //
        // A single walk over the whole graph zigzags: from a half sister it climbs to the
        // shared mother, drops to her other daughter, and climbs again to that daughter's
        // father, seating him on the half sister's parents row. He is not her parent. Going
        // up only, then down only, keeps everyone on a row that means what it says.
        walk(centrePersonId, links, placed, shaky, up = true, limit = up)
        walk(centrePersonId, links, placed, shaky, up = false, limit = down)

        // Siblings stand beside you rather than under your parents, which is what makes
        // "under my dad, us kids" read correctly once you recentre on him: from there they
        // are simply his children.
        //
        // Only real parent and child hops count here. Allowing any downward link let a
        // grandparent edge through, so a grandchild two generations below came back as a
        // sibling: on an aunt's page her niece was listed as her sister.
        links[centrePersonId].orEmpty().filter { it.delta == -1 }
            .flatMap { parent -> links[parent.otherId].orEmpty().filter { it.delta == 1 } }
            .map { it.otherId }
            .distinct()
            .filter { it !in placed }
            .forEach { placed[it] = 0 }

        // Siblings stated directly, with no parents on file for either of them. Deriving
        // siblings only from shared parents dropped four of the five names in an obituary.
        relationships.filter { it.kind == RelationshipKind.SIBLING }
            .mapNotNull { r ->
                when (centrePersonId) {
                    r.fromPersonId -> r.toPersonId
                    r.toPersonId -> r.fromPersonId
                    else -> null
                }
            }
            .distinct()
            .filter { it !in placed }
            .forEach { placed[it] = 0 }

        // Aunts, uncles, cousins, nieces and nephews: one step off the direct line. They
        // share a row with blood generations without being the same thing, so they are
        // marked rather than merged.
        val sideways = mutableSetOf<String>()
        val parents = links[centrePersonId].orEmpty().filter { it.delta == -1 }.map { it.otherId }
        val auntsUncles = parents
            .flatMap { p2 -> links[p2].orEmpty().filter { it.delta == -1 }.map { it.otherId } }
            .flatMap { gp -> links[gp].orEmpty().filter { it.delta == 1 }.map { it.otherId } }
            .distinct()
            .filter { it !in parents && it != centrePersonId }
        auntsUncles.filter { it !in placed }.forEach { placed[it] = -1; sideways += it }

        val cousins = auntsUncles
            .flatMap { a -> links[a].orEmpty().filter { it.delta == 1 }.map { it.otherId } }
            .distinct()
        cousins.filter { it !in placed }.forEach { placed[it] = 0; sideways += it }

        // Aunts and uncles stated outright, with no parents on file to reach them through.
        // The derivation above walks up to the grandparents and back down, so somebody
        // recorded only as an uncle never appeared at all. Which side of the family they
        // are on cannot be worked out without those parents, so they group under the plain
        // heading rather than being guessed onto one.
        for (r in relationships.filter { it.kind == RelationshipKind.AUNT_UNCLE }) {
            val (other, generation) = when (centrePersonId) {
                r.toPersonId -> r.fromPersonId to -1
                r.fromPersonId -> r.toPersonId to 1
                else -> continue
            }
            if (other !in placed) {
                placed[other] = generation
                sideways += other
            }
        }

        // Siblings' children.
        placed.filter { it.value == 0 && it.key != centrePersonId && it.key !in sideways }.keys
            .flatMap { sib -> links[sib].orEmpty().filter { it.delta == 1 }.map { it.otherId } }
            .distinct()
            .filter { it !in placed }
            .forEach { placed[it] = 1; sideways += it }

        val nodes = placed.entries
            .groupBy({ it.value }, { it.key })
            .flatMap { (g, ids) ->
                ids.mapIndexed { slot, id ->
                    Node(id, g, slot, viaUncertain = id in shaky, sideways = id in sideways)
                }
            }
        return Frame(centrePersonId, nodes)
    }

    /** Climbs one direction only, never turning around. */
    private fun walk(
        from: String,
        links: Map<String, List<Link>>,
        placed: MutableMap<String, Int>,
        shaky: MutableSet<String>,
        up: Boolean,
        limit: Int
    ) {
        val queue = ArrayDeque(listOf(from))
        while (queue.isNotEmpty()) {
            val here = queue.removeFirst()
            val g = placed[here] ?: continue
            for (link in links[here].orEmpty()) {
                if (up && link.delta > 0) continue
                if (!up && link.delta < 0) continue
                val next = g + link.delta
                if (next < -limit || next > limit) continue
                if (link.otherId in placed) continue
                placed[link.otherId] = next
                if (link.uncertain || here in shaky) shaky += link.otherId
                queue.addLast(link.otherId)
            }
        }
    }

    private class Link(val otherId: String, val delta: Int, val uncertain: Boolean)

    /**
     * Everyone reachable from a person, with how many generations the hop crosses.
     *
     * A GRANDPARENT edge counts as two, not one. Treating it as a single hop put a
     * grandparent on the same row as a parent, and worse, a person reachable both ways
     * landed on whichever row the traversal happened to reach first.
     */
    private fun adjacency(relationships: List<Relationship>): Map<String, List<Link>> {
        val out = mutableMapOf<String, MutableList<Link>>()
        for (r in relationships) {
            val steps = when (r.kind) {
                RelationshipKind.PARENT, RelationshipKind.CHILD -> 1
                RelationshipKind.GRANDPARENT, RelationshipKind.GRANDCHILD -> 2
                else -> continue
            }
            val (older, younger) = when (r.kind) {
                RelationshipKind.PARENT, RelationshipKind.GRANDPARENT ->
                    r.fromPersonId to r.toPersonId
                else -> r.toPersonId to r.fromPersonId
            }
            out.getOrPut(younger) { mutableListOf() }.add(Link(older, -steps, r.uncertain))
            out.getOrPut(older) { mutableListOf() }.add(Link(younger, steps, r.uncertain))
        }
        // Shorter hops first, so a stated parent chain is preferred over a vaguer shortcut.
        return out.mapValues { (_, v) -> v.sortedBy { kotlin.math.abs(it.delta) } }
    }
}
