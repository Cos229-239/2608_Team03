package com.arv.app.core.ai

import com.arv.app.core.model.Relationship
import com.arv.app.core.model.RelationshipKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ancestor walk decides who may read a family's branch-scoped material, so a bug here
 * is a privacy incident rather than a defect. Direction is asserted explicitly, because
 * reading an edge backwards would hand one side of a family the other side's memories.
 *
 * Convention under test: an edge reads **from is the kind of to**.
 * `Relationship(from = opal, to = dana, kind = PARENT)` means Opal is Dana's parent.
 */
class LineageTest {

    private fun parent(parent: String, child: String) =
        Relationship(fromPersonId = parent, toPersonId = child, kind = RelationshipKind.PARENT)

    /**
     *        opal ── walter          (grandparents, Swedish line)
     *              \   /
     *              dad        mom       (mom is the other line entirely)
     *                 \      /
     *                  dana
     *                    |
     *                  maya
     */
    private val family = listOf(
        parent("p_opal", "p_ray"),
        parent("p_walter", "p_ray"),
        parent("p_ray", "p_dana"),
        parent("p_ruth", "p_dana"),
        parent("p_dana", "p_maya")
    )

    @Test
    fun `a person is their own ancestor`() {
        // A branch named after you is yours. The old model lost this and made a recording
        // invisible to the person who filed it.
        assertTrue("p_dana" in Lineage.ancestorsOf("p_dana", family))
    }

    @Test
    fun `ancestors reach every line, not one`() {
        // The whole reason a single branchRootPersonId could not work.
        val a = Lineage.ancestorsOf("p_dana", family)
        assertTrue("p_ray" in a)
        assertTrue("p_ruth" in a)
        assertTrue("p_opal" in a)
        assertTrue("p_walter" in a)
    }

    @Test
    fun `ancestors climb more than one generation`() {
        assertTrue("p_opal" in Lineage.ancestorsOf("p_maya", family))
    }

    @Test
    fun `descendants are never ancestors`() {
        val a = Lineage.ancestorsOf("p_dana", family)
        assertFalse("p_maya" in a)
    }

    @Test
    fun `direction is not symmetric`() {
        // If this ever passes both ways the edge is being read backwards.
        assertTrue(Lineage.isDescendantOf("p_dana", "p_opal", family))
        assertFalse(Lineage.isDescendantOf("p_opal", "p_dana", family))
    }

    @Test
    fun `CHILD edges are the same statement written the other way round`() {
        val viaChild = listOf(
            Relationship("p_dana", "p_ray", RelationshipKind.CHILD)
        )
        assertTrue("p_ray" in Lineage.ancestorsOf("p_dana", viaChild))
    }

    @Test
    fun `GRANDPARENT edges count as ancestry`() {
        // A family that recorded "this is my grandmother" and never filled in the
        // generation between still knows she is an ancestor.
        val skipped = listOf(
            Relationship("p_opal", "p_dana", RelationshipKind.GRANDPARENT)
        )
        assertTrue("p_opal" in Lineage.ancestorsOf("p_dana", skipped))
    }

    @Test
    fun `marrying in does not make a line yours`() {
        val married = family + Relationship("p_marcus", "p_dana", RelationshipKind.SPOUSE)
        val a = Lineage.ancestorsOf("p_marcus", married)
        assertFalse("p_neighbour" in a)
        assertFalse("p_ray" in a)
        assertEquals(setOf("p_marcus"), a)
    }

    @Test
    fun `siblings and cousins are not ancestors`() {
        val wider = family + listOf(
            Relationship("p_sister", "p_dana", RelationshipKind.SIBLING),
            Relationship("p_cousin", "p_dana", RelationshipKind.COUSIN),
            Relationship("p_aunt", "p_dana", RelationshipKind.AUNT_UNCLE),
            Relationship("p_neighbour", "p_dana", RelationshipKind.CHOSEN)
        )
        val a = Lineage.ancestorsOf("p_dana", wider)
        assertFalse("p_sister" in a)
        assertFalse("p_cousin" in a)
        assertFalse("p_aunt" in a)
        assertFalse("p_neighbour" in a)
    }

    @Test
    fun `a cycle in the data terminates instead of hanging`() {
        // Family data is hand-entered and imported. "Her own grandmother" is one slip away,
        // and a permission check must not be able to spin on it.
        val cyclic = listOf(
            parent("p_a", "p_b"),
            parent("p_b", "p_c"),
            parent("p_c", "p_a")
        )
        assertEquals(setOf("p_a", "p_b", "p_c"), Lineage.ancestorsOf("p_a", cyclic))
    }

    @Test
    fun `an unknown person has only themselves`() {
        assertEquals(setOf("p_nobody"), Lineage.ancestorsOf("p_nobody", family))
    }

    @Test
    fun `no relationships at all still returns self`() {
        assertEquals(setOf("p_dana"), Lineage.ancestorsOf("p_dana", emptyList()))
    }

    @Test
    fun `branch choices exclude yourself`() {
        // "My branch" meaning only me is what PRIVATE is for.
        val choices = Lineage.branchChoicesFor("p_dana", family)
        assertFalse("p_dana" in choices)
        assertEquals(
            setOf("p_ray", "p_ruth", "p_opal", "p_walter"),
            choices.toSet()
        )
    }

    @Test
    fun `generation bound stops a pathological chain`() {
        val long = (0 until 100).map { parent("p_$it", "p_${it + 1}") }
        val a = Lineage.ancestorsOf("p_100", long, maxGenerations = 3)
        assertTrue("p_99" in a)
        assertFalse("p_50" in a)
    }

    // --- sibling degree, derived rather than labelled ---

    /**
     * Theo and Dana share one parent and not the other, which makes them half siblings.
     * A label written from Ray's side would call Theo his stepdaughter, and copying that
     * word across into Dana's tree would be wrong. Shared parents settle it without
     * anybody's viewpoint.
     */
    private val halfSisters = listOf(
        parent("p_ruth", "p_dana"),
        parent("p_ray", "p_dana"),
        parent("p_ruth", "p_theo")
    )

    @Test
    fun `one shared parent is a half sibling, not a step sibling`() {
        assertEquals(
            Lineage.SiblingKind.HALF,
            Lineage.siblingKind("p_dana", "p_theo", halfSisters)
        )
    }

    @Test
    fun `sibling degree does not depend on which of them you ask`() {
        assertEquals(
            Lineage.siblingKind("p_dana", "p_theo", halfSisters),
            Lineage.siblingKind("p_theo", "p_dana", halfSisters)
        )
    }

    @Test
    fun `both parents shared is a full sibling`() {
        val full = halfSisters + parent("p_ray", "p_theo")
        assertEquals(
            Lineage.SiblingKind.FULL,
            Lineage.siblingKind("p_dana", "p_theo", full)
        )
    }

    @Test
    fun `no shared parent but partnered parents is a step sibling`() {
        val step = listOf(
            parent("p_ray", "p_dana"),
            parent("p_ruth", "p_theo"),
            Relationship("p_ray", "p_ruth", RelationshipKind.SPOUSE)
        )
        assertEquals(
            Lineage.SiblingKind.STEP,
            Lineage.siblingKind("p_dana", "p_theo", step)
        )
    }

    @Test
    fun `unrelated people are not siblings`() {
        val strangers = listOf(
            parent("p_ray", "p_dana"),
            parent("p_someone", "p_stranger")
        )
        assertEquals(
            Lineage.SiblingKind.NONE,
            Lineage.siblingKind("p_dana", "p_stranger", strangers)
        )
    }

    @Test
    fun `unknown parents means the graph cannot claim a sibling relationship`() {
        // Silence is the honest answer. Asserting FULL here would invent a family.
        assertEquals(
            Lineage.SiblingKind.NONE,
            Lineage.siblingKind("p_a", "p_b", emptyList())
        )
    }

    @Test
    fun `a person is not their own sibling`() {
        assertEquals(
            Lineage.SiblingKind.NONE,
            Lineage.siblingKind("p_dana", "p_dana", halfSisters)
        )
    }

    // --- which side of the family ---

    @Test
    fun `an ancestor is placed on the side of the parent they sit behind`() {
        // Opal and Walter are behind dad. Nobody is behind mom in this graph.
        assertEquals(setOf("p_ray"), Lineage.sidesOf("p_opal", "p_dana", family))
        assertEquals(setOf("p_ray"), Lineage.sidesOf("p_walter", "p_dana", family))
    }

    @Test
    fun `a parent is on their own side`() {
        assertEquals(setOf("p_ruth"), Lineage.sidesOf("p_ruth", "p_dana", family))
    }

    @Test
    fun `the side depends on who is asking`() {
        // Opal is behind Dana's father. From Maya she is behind Dana instead. The
        // same person, a different side, which is why this is never stored on the person.
        assertEquals(setOf("p_ray"), Lineage.sidesOf("p_opal", "p_dana", family))
        assertEquals(setOf("p_dana"), Lineage.sidesOf("p_opal", "p_maya", family))
    }

    @Test
    fun `someone with no path is unplaced rather than guessed`() {
        // The real case: 20 imported ancestors whose line the file never stated.
        val withStranger = family + parent("p_unknown_line", "p_nobody")
        assertTrue(Lineage.isUnplaced("p_unknown_line", "p_dana", withStranger))
        assertEquals(emptySet<String>(), Lineage.sidesOf("p_unknown_line", "p_dana", withStranger))
    }

    @Test
    fun `converging lines report both sides rather than picking one`() {
        // Cousins marrying is not exotic in a real family record.
        val converging = listOf(
            parent("p_shared", "p_ruth"),
            parent("p_shared", "p_ray"),
            parent("p_ray", "p_dana"),
            parent("p_ruth", "p_dana")
        )
        assertEquals(
            setOf("p_ruth", "p_ray"),
            Lineage.sidesOf("p_shared", "p_dana", converging)
        )
    }

    @Test
    fun `you are not on your own side`() {
        assertEquals(emptySet<String>(), Lineage.sidesOf("p_dana", "p_dana", family))
    }

    // --- connectedness, which is a weaker question than ancestry ---

    @Test
    fun `an aunt is connected even though she is not an ancestor`() {
        // She has a place in the family. Listing her as unplaced would bury the people
        // who genuinely are floating.
        val withAunt = family + Relationship("p_aunt", "p_dana", RelationshipKind.AUNT_UNCLE)
        assertFalse(Lineage.isUnplaced("p_aunt", "p_dana", withAunt))
        assertEquals(emptySet<String>(), Lineage.sidesOf("p_aunt", "p_dana", withAunt))
    }

    @Test
    fun `someone with no edges at all is unplaced`() {
        val floating = family + parent("p_ghost", "p_other_ghost")
        assertTrue(Lineage.isUnplaced("p_ghost", "p_dana", floating))
    }

    @Test
    fun `connectedness reaches through a chain, not just direct edges`() {
        assertTrue(Lineage.isConnected("p_maya", "p_opal", family))
    }

    @Test
    fun `connectedness is symmetric`() {
        assertEquals(
            Lineage.isConnected("p_maya", "p_opal", family),
            Lineage.isConnected("p_opal", "p_maya", family)
        )
    }

    @Test
    fun `a grandparent shortcut does not make somebody their own side of the family`() {
        // Imported records often attach a grandparent straight to the person. Treating that
        // as a parent link reported the grandmother as one of the sides she sits behind.
        val shortcut = family + Relationship("p_opal", "p_dana", RelationshipKind.GRANDPARENT)
        assertEquals(setOf("p_ray"), Lineage.sidesOf("p_opal", "p_dana", shortcut))
    }

    // --- following a line down the correct side ---

    /**
     *   opal - walter          sam - ada
     *      ray   sheila      ruth   kevin
     *              \        /
     *                 dana
     */
    private val bothSides = listOf(
        parent("p_opal", "p_ray"), parent("p_walter", "p_ray"),
        parent("p_opal", "p_sheila"), parent("p_walter", "p_sheila"),
        parent("p_sam", "p_ruth"), parent("p_ada", "p_ruth"),
        parent("p_sam", "p_kevin"), parent("p_ada", "p_kevin"),
        parent("p_ray", "p_dana"), parent("p_ruth", "p_dana")
    )

    @Test
    fun `an aunt lands on the side she belongs to`() {
        assertEquals(setOf("p_ray"), Lineage.sideOf("p_sheila", "p_dana", bothSides))
        assertEquals(setOf("p_ruth"), Lineage.sideOf("p_kevin", "p_dana", bothSides))
    }

    @Test
    fun `grandparents land on the side they belong to`() {
        assertEquals(setOf("p_ray"), Lineage.sideOf("p_opal", "p_dana", bothSides))
        assertEquals(setOf("p_ruth"), Lineage.sideOf("p_sam", "p_dana", bothSides))
    }

    @Test
    fun `the same person sits on a different side for a different descendant`() {
        // Opal is on Ray's side from Dana. From Sheila's child she is on Sheila's side.
        val nextGen = bothSides + parent("p_sheila", "p_cousin")
        assertEquals(setOf("p_ray"), Lineage.sideOf("p_opal", "p_dana", nextGen))
        assertEquals(setOf("p_sheila"), Lineage.sideOf("p_opal", "p_cousin", nextGen))
    }

    @Test
    fun `a parent is their own side`() {
        assertEquals(setOf("p_ray"), Lineage.sideOf("p_ray", "p_dana", bothSides))
    }
}
