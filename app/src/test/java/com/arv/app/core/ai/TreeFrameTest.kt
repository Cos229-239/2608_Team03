package com.arv.app.core.ai

import com.arv.app.core.model.Relationship
import com.arv.app.core.model.RelationshipKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A small family with two sides, a half sibling, and one person nobody has placed.
 *
 * edith ─ (opal) and (sam ─ ada), grandparents on each side
 *            ray ─ ruth
 *          dana   theo (ruth's daughter only, so a half sister)
 *            maya
 */
class TreeFrameTest {

    private fun parent(p: String, c: String) =
        Relationship(fromPersonId = p, toPersonId = c, kind = RelationshipKind.PARENT)

    private val edges = listOf(
        parent("p_edith", "p_opal"),
        parent("p_opal", "p_ray"),
        parent("p_sam", "p_ruth"),
        parent("p_ada", "p_ruth"),
        parent("p_ray", "p_dana"),
        parent("p_ruth", "p_dana"),
        parent("p_ruth", "p_theo"),
        parent("p_dana", "p_maya")
    )

    private fun idsAt(centre: String, g: Int) =
        TreeFrame.frameFor(centre, edges).generation(g).map { it.personId }.toSet()

    @Test
    fun `centred on dad, his children are below him`() {
        // "under my dad us kids"
        assertTrue("p_dana" in idsAt("p_ray", 1))
    }

    @Test
    fun `centred on dad, his parents are above him`() {
        // "his parents"
        assertTrue("p_opal" in idsAt("p_ray", -1))
        assertTrue("p_edith" in idsAt("p_ray", -2))
    }

    @Test
    fun `the centre sits at generation zero`() {
        val f = TreeFrame.frameFor("p_ray", edges)
        assertEquals(0, f.nodes.first { it.personId == "p_ray" }.generation)
    }

    @Test
    fun `older is negative so ancestors draw above without flipping`() {
        val f = TreeFrame.frameFor("p_dana", edges)
        val ray = f.nodes.first { it.personId == "p_ray" }
        val maya = f.nodes.first { it.personId == "p_maya" }
        assertTrue(ray.generation < 0)
        assertTrue(maya.generation > 0)
    }

    @Test
    fun `siblings stand beside you, not underneath your parents`() {
        // Theo shares Ruth with Dana, so from Dana she is alongside, not below.
        assertTrue("p_theo" in idsAt("p_dana", 0))
    }

    @Test
    fun `recentring is the same edges seen from somewhere else`() {
        // Dana is a child in her father's frame and the centre in her own. Same data.
        assertTrue("p_dana" in idsAt("p_ray", 1))
        assertEquals(0, TreeFrame.frameFor("p_dana", edges)
            .nodes.first { it.personId == "p_dana" }.generation)
    }

    @Test
    fun `a half sister brings only her own parent's side upward`() {
        // From Theo, Ruth is a parent and Ray is not anywhere above her.
        assertTrue("p_ruth" in idsAt("p_theo", -1))
        assertTrue("p_ray" !in idsAt("p_theo", -1))
    }

    @Test
    fun `depth is bounded so a deep line does not fill the screen`() {
        val deep = TreeFrame.frameFor("p_dana", edges, up = 1, down = 1)
        assertTrue(deep.nodes.none { it.generation < -1 || it.generation > 1 })
    }

    @Test
    fun `somebody with no edges is a frame of one`() {
        val f = TreeFrame.frameFor("p_unplaced", edges)
        assertEquals(listOf("p_unplaced"), f.nodes.map { it.personId })
    }

    @Test
    fun `uncertain links are carried through to the drawing`() {
        // An unverified ancestor must be able to look unverified on screen.
        val shaky = edges + Relationship(
            "p_maybe", "p_edith", RelationshipKind.PARENT, uncertain = true
        )
        val f = TreeFrame.frameFor("p_edith", shaky)
        assertTrue(f.nodes.first { it.personId == "p_maybe" }.viaUncertain)
    }

    @Test
    fun `slots within a generation are distinct`() {
        val f = TreeFrame.frameFor("p_dana", edges)
        for (g in f.generations) {
            val slots = f.generation(g).map { it.slot }
            assertEquals(slots.size, slots.toSet().size)
        }
    }

    // --- a grandparent shortcut must not fabricate siblings ---

    /**
     * Imported records often attach a grandparent straight to the person, skipping the
     * generation between. Following any downward link when gathering siblings then walked
     * grandparent -> grandchild and seated the grandchild beside their own aunt.
     */
    private val withShortcut = edges + Relationship(
        "p_opal", "p_dana", RelationshipKind.GRANDPARENT
    )

    @Test
    fun `a niece is not listed among her aunt's siblings`() {
        // p_aunt shares parents with p_ray, so from her, p_dana is a generation below.
        val withAunt = withShortcut + parent("p_opal", "p_aunt")
        assertTrue("p_dana" !in idsAt("p_aunt", 0))
    }

    @Test
    fun `a grandparent shortcut does not pull a grandchild up beside their parent`() {
        assertTrue("p_dana" !in idsAt("p_ray", 0))
        assertTrue("p_dana" in idsAt("p_ray", 1))
    }

    @Test
    fun `someone with no siblings has only themselves at generation zero`() {
        // The screen uses this to decide whether a Siblings heading belongs on the page.
        assertEquals(listOf("p_edith"), TreeFrame.frameFor("p_edith", edges).generation(0).map { it.personId })
    }

    @Test
    fun `real siblings are still found`() {
        assertTrue("p_theo" in idsAt("p_dana", 0))
    }

    // --- one father, three mothers, and a mother's child by somebody else ---

    /**
     * The shape a blended family actually has, and the one this screen kept getting wrong.
     *
     *   ray + ruth  -> dana, elle      full sisters
     *   ray + ?     -> theo, sam       half to dana, and the mother is not in the archive
     *   ruth + vic  -> bea             half to dana, nothing at all to theo
     *
     * Every one of these people has to see the same family from their own page. Attaching
     * siblings straight to one person with a SIBLING edge produced a correct page for that
     * one person and an empty one for everybody else, because a sibling edge says how two
     * people stand and never says through whom. Parents are what makes it derive.
     */
    private val blended = listOf(
        parent("f_ray", "f_dana"), parent("f_ruth", "f_dana"),
        parent("f_ray", "f_elle"), parent("f_ruth", "f_elle"),
        parent("f_ray", "f_theo"),
        parent("f_ray", "f_sam"),
        parent("f_ruth", "f_bea"), parent("f_vic", "f_bea")
    )

    private fun besideIn(centre: String) =
        TreeFrame.frameFor(centre, blended).generation(0).map { it.personId }.toSet() - centre

    @Test
    fun `a half sibling through the father stands beside you`() {
        assertEquals(setOf("f_elle", "f_theo", "f_sam", "f_bea"), besideIn("f_dana"))
    }

    @Test
    fun `the same family reads correctly from a half brother's page`() {
        // Theo sees his father's other children. He does not see Bea, who is his father's
        // ex-partner's daughter by another man and no relation to him at all.
        assertEquals(setOf("f_sam", "f_dana", "f_elle"), besideIn("f_theo"))
    }

    @Test
    fun `a mother's child by someone else sees only that side`() {
        assertEquals(setOf("f_dana", "f_elle"), besideIn("f_bea"))
    }

    @Test
    fun `sibling degree is derived from shared parents, not from a label`() {
        assertEquals(Lineage.SiblingKind.FULL, Lineage.siblingKind("f_dana", "f_elle", blended))
        assertEquals(Lineage.SiblingKind.HALF, Lineage.siblingKind("f_dana", "f_theo", blended))
        assertEquals(Lineage.SiblingKind.HALF, Lineage.siblingKind("f_dana", "f_bea", blended))
        assertEquals(Lineage.SiblingKind.NONE, Lineage.siblingKind("f_theo", "f_bea", blended))
    }

    @Test
    fun `brothers with an unrecorded mother still find each other`() {
        // Their mother is not in the archive yet, so the graph can only prove they share a
        // father. That understates them as half brothers, and it must still place them
        // together rather than dropping either from the other's page.
        assertTrue("f_sam" in besideIn("f_theo"))
        assertEquals(Lineage.SiblingKind.HALF, Lineage.siblingKind("f_theo", "f_sam", blended))
    }

    @Test
    fun `a stated sibling with no parents on file is still shown`() {
        // What an obituary gives you: five names and no parents. Deriving siblings only
        // from shared parents listed one of them and silently dropped the rest.
        val stated = blended + Relationship("f_kit", "f_dana", RelationshipKind.SIBLING)
        val beside = TreeFrame.frameFor("f_dana", stated).generation(0).map { it.personId }
        assertTrue("f_kit" in beside)
    }
}
