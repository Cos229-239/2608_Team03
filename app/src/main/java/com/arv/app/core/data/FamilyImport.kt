package com.arv.app.core.data

import com.arv.app.core.model.Confidence
import com.arv.app.core.model.RelationshipKind
import org.json.JSONObject

/**
 * Reads a family history file into people and the links between them.
 *
 * Two rules shape everything here.
 *
 * The first is that nothing is upgraded on the way in. A file that says a person came from
 * unchecked research produces a person marked unchecked, carrying the source text that
 * makes them checkable. Genealogy is where families most reliably end up storing confident
 * lies, because a name copied out of somebody else's tree looks identical to a name taken
 * off a death certificate once both are in the same list.
 *
 * The second is that no link is invented. Relationship labels in these files are written
 * from one person's point of view, and "3x great-grandmother" says how many generations up
 * somebody sits without saying which of eight lines they sit on. Guessing from a surname
 * would put a stranger in a family's tree and it would look exactly like a fact. So the
 * ancestors whose position is unambiguous are linked, everyone else arrives unconnected,
 * and connecting them is work the family does knowingly.
 */
object FamilyImport {

    data class ImportedPerson(
        val displayName: String,
        val alsoKnownAs: List<String>,
        val relationLabel: String?,
        val birthYear: Int?,
        val deathYear: Int?,
        val birthPlace: String?,
        val note: String?,
        val confidence: Confidence,
        val source: String?,
        /** How this person links to the importer, or null when the file does not say. */
        val linkToImporter: RelationshipKind?,
        /**
         * Named parents, by display name.
         *
         * Preferred over [relationLabel] whenever present, because a parent is a fact about
         * two people while a label is one person's word for a relationship. Stating parents
         * is also the only way a file can express a half sibling: shared parents are what
         * distinguishes half from full from step, and no label can carry that.
         */
        val parentNames: List<String>,
        /** A named spouse, when the file says so. Not ancestry, but it is how a family reads. */
        val spouseName: String?
    )

    data class Parsed(
        val familyName: String?,
        val people: List<ImportedPerson>
    ) {
        val linked: Int get() = people.count { it.linkToImporter != null }
        val unlinked: Int get() = people.count { it.linkToImporter == null }
        val needingChecks: Int get() = people.count {
            it.confidence == Confidence.UNVERIFIED || it.confidence == Confidence.CONFLICTED
        }
    }

    fun parse(json: String): Result<Parsed> = runCatching {
        val root = JSONObject(json)
        val arr = root.optJSONArray("people") ?: error("No people array in that file")

        val people = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val aka = o.optJSONArray("alsoKnownAs")
            val label = o.optString("relationLabel").takeIf { it.isNotBlank() }
            ImportedPerson(
                displayName = o.optString("displayName").ifBlank { "Unnamed" },
                alsoKnownAs = (0 until (aka?.length() ?: 0)).map { aka!!.getString(it) },
                relationLabel = label,
                birthYear = o.optInt("birthYear").takeIf { it > 0 },
                deathYear = o.optInt("deathYear").takeIf { it > 0 },
                birthPlace = o.optString("birthPlace").takeIf { it.isNotBlank() },
                note = o.optString("note").takeIf { it.isNotBlank() },
                confidence = confidenceOf(o.optString("confidence")),
                source = o.optString("source").takeIf { it.isNotBlank() },
                linkToImporter = linkFor(label),
                parentNames = o.optJSONArray("parents").let { arr2 ->
                    (0 until (arr2?.length() ?: 0)).map { arr2!!.getString(it) }
                },
                spouseName = o.optString("spouse").takeIf { it.isNotBlank() }
            )
        }
        Parsed(root.optString("familyName").takeIf { it.isNotBlank() }, people)
    }

    /**
     * Grades in these files are free text and vary by whoever wrote them, so anything not
     * recognised lands on UNVERIFIED. Failing towards "nobody has checked this" is the only
     * safe direction: the cost of wrongly doubting a real ancestor is that someone confirms
     * them, and the cost of wrongly trusting an invented one is that they become family.
     */
    private fun confidenceOf(raw: String): Confidence = when {
        raw.isBlank() -> Confidence.UNVERIFIED
        raw.startsWith("verified") && raw.contains("partial") -> Confidence.PARTLY_DOCUMENTED
        raw.startsWith("verified") -> Confidence.DOCUMENTED
        raw.contains("conflict") -> Confidence.CONFLICTED
        raw.startsWith("user_reported") || raw.startsWith("family") -> Confidence.FAMILY_TOLD
        else -> Confidence.UNVERIFIED
    }

    /**
     * Only labels whose position is beyond doubt become links.
     *
     * A parent is a parent and a grandparent is a grandparent. Past that the label stops
     * being enough: "great-grandmother" is certainly an ancestor but recording her as a
     * grandparent would be off by a generation, and the file does not say which line she is
     * on. Those people are imported and left for someone to place, which is the honest
     * outcome and also the more useful one, because it is a list of questions worth asking
     * while there is still somebody alive to ask.
     */
    private fun linkFor(label: String?): RelationshipKind? = when (label?.trim()?.lowercase()) {
        "mother", "father" -> RelationshipKind.PARENT
        "grandmother", "grandfather" -> RelationshipKind.GRANDPARENT
        "brother", "sister", "stepsister", "stepbrother" -> RelationshipKind.SIBLING
        "aunt", "uncle" -> RelationshipKind.AUNT_UNCLE
        else -> null
    }
}
