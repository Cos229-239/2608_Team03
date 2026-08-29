package com.arv.app.core.ai

import com.arv.app.core.model.Relationship
import com.arv.app.core.model.RelationshipKind
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape that showed the bug: an aunt and an uncle appearing in the Parents row.
 *
 *   opal - walter        sam - ada
 *      ray  sheila      ruth  kevin
 *            \          /
 *              dana
 */
class SidewaysTest {

    private fun parent(p: String, c: String) =
        Relationship(fromPersonId = p, toPersonId = c, kind = RelationshipKind.PARENT)

    private val edges = listOf(
        parent("p_opal", "p_ray"), parent("p_walter", "p_ray"),
        parent("p_opal", "p_sheila"), parent("p_walter", "p_sheila"),
        parent("p_sam", "p_ruth"), parent("p_ada", "p_ruth"),
        parent("p_sam", "p_kevin"), parent("p_ada", "p_kevin"),
        parent("p_ray", "p_dana"), parent("p_ruth", "p_dana")
    )

    @Test
    fun `only real parents appear on the parents row`() {
        val f = TreeFrame.frameFor("p_dana", edges)
        val direct = f.direct(-1).map { it.personId }.toSet()
        assertTrue("ray is a parent", "p_ray" in direct)
        assertTrue("ruth is a parent", "p_ruth" in direct)
        assertTrue("sheila is an aunt, not a parent", "p_sheila" !in direct)
        assertTrue("kevin is an uncle, not a parent", "p_kevin" !in direct)
    }

    @Test
    fun `aunts and uncles land on their own row`() {
        val f = TreeFrame.frameFor("p_dana", edges)
        val off = f.sideways(-1).map { it.personId }.toSet()
        assertTrue("p_sheila" in off)
        assertTrue("p_kevin" in off)
    }
}
