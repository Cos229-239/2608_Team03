package com.arv.app.core.data

import com.arv.app.core.data.local.PersonEntity
import com.arv.app.core.model.Confidence
import com.arv.app.core.model.ProfileState
import com.arv.app.core.model.RelationshipKind
import com.arv.app.core.sync.SyncPolicy
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
        /**
         * Known to have died, with no year on record.
         *
         * A family very often knows somebody is gone and not when. Inferring death from a
         * death year alone meant those people imported as living, so an archive built to
         * record who can still be asked was quietly listing people who cannot.
         */
        val deceased: Boolean,
        /** The later end of a death the file could only place within a year or two. */
        val deathYearEnd: Int?,
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

    data class PlannedPerson(val personId: String, val imported: ImportedPerson)
    data class PlannedEdge(
        val fromId: String,
        val toId: String,
        val kind: RelationshipKind,
        val uncertain: Boolean
    )
    data class Plan(val people: List<PlannedPerson>, val edges: List<PlannedEdge>)

    /**
     * Resolves every name in the file before a single edge is drawn.
     *
     * One pass did both at once, looking names up in a snapshot taken before the import
     * started. On a first import into an empty archive nobody in the file could find
     * anybody else in the file, so every parent and spouse stated between them was
     * silently skipped: 34 people would arrive and zero of the links the file spelled
     * out. Importing the identical file a second time healed it, which nobody would know
     * to do. Two passes make the order of rows in the file irrelevant.
     *
     * The importer's own row is never replanned as a person, but its parents and spouse
     * still become edges: skipping the whole row also skipped them, so nobody could state
     * their own parents in their own file.
     *
     * Pure so it can be tested without a database, which the one-pass version never was.
     */
    fun plan(
        parsed: Parsed,
        existingIdsByName: Map<String, String>,
        meId: String?,
        meName: String?,
        newId: () -> String
    ): Plan {
        fun norm(name: String) = name.trim().lowercase()

        val ids = existingIdsByName.mapKeys { (k, _) -> norm(k) }.toMutableMap()
        if (meId != null && meName != null) ids[norm(meName)] = meId

        val people = mutableListOf<PlannedPerson>()
        for (person in parsed.people) {
            val key = norm(person.displayName)
            if (meId != null && key == meName?.let(::norm)) continue
            val id = ids.getOrPut(key) { newId() }
            people += PlannedPerson(id, person)
        }

        val edges = mutableListOf<PlannedEdge>()
        for (person in parsed.people) {
            val id = ids[norm(person.displayName)] ?: continue

            person.spouseName?.let { spouse ->
                ids[norm(spouse)]?.let { spouseId ->
                    if (spouseId != id) {
                        edges += PlannedEdge(id, spouseId, RelationshipKind.SPOUSE, false)
                    }
                }
            }

            // Named parents win over a relation label. A label describes the writer's
            // relationship to someone; parents describe the person themselves, and only
            // parents can tell a half sibling from a step sibling.
            for (parentName in person.parentNames) {
                ids[norm(parentName)]?.let { parentId ->
                    if (parentId != id) {
                        edges += PlannedEdge(parentId, id, RelationshipKind.PARENT, false)
                    }
                }
            }

            // Both, not either: naming someone's parents must not delete their own link
            // to the importer. And never a link from the importer to themselves.
            val kind = person.linkToImporter
            if (kind != null && meId != null && id != meId) {
                edges += PlannedEdge(
                    id, meId, kind,
                    // Everything the file was not certain about stays marked uncertain,
                    // so a disputed link is visible rather than quietly authoritative.
                    uncertain = person.confidence == Confidence.UNVERIFIED ||
                        person.confidence == Confidence.CONFLICTED
                )
            }
        }
        return Plan(people, edges.distinct())
    }

    /** The row for somebody the archive does not hold yet. */
    fun newPerson(personId: String, familyId: String, imported: ImportedPerson, nowMillis: Long) =
        PersonEntity(
            personId = personId,
            familyId = familyId,
            displayName = imported.displayName,
            alsoKnownAs = imported.alsoKnownAs,
            birthYear = imported.birthYear,
            deathYear = imported.deathYear,
            deathYearEnd = imported.deathYearEnd,
            note = imported.note,
            birthPlace = imported.birthPlace,
            relationLabel = imported.relationLabel,
            state = stateOf(imported),
            confidence = imported.confidence,
            source = imported.source,
            updatedAt = nowMillis
        )

    /**
     * What an import does to somebody the archive already holds.
     *
     * The row is copied, never rebuilt. Rebuilding it from the file reset every column the
     * file has no field for, so importing a corrected file erased recorded consent and
     * refusals, stewards, linked accounts, portraits and when anyone checked the record.
     * Sync then sent the erased version to every phone in the family, because it was newer.
     *
     * The fields the file does carry change only as far as the file's grade reaches, because
     * a grade vouches for the whole person and not only for the fields that came with it.
     *
     *  - A lower grade than the archive holds changes nothing. A family-told birth year does
     *    not replace a documented one, and an import never lowers a grade. Lowering one is a
     *    judgement about a person, not a side effect of a file.
     *  - The same grade corrects. What the file states replaces what the archive holds, and
     *    what it leaves out stays, since leaving something out is not saying it is wrong and
     *    two relatives' files rarely know the same things. That includes a death: a file can
     *    say somebody died but has no way to say they did not, so an import never turns a
     *    memorial profile back into a living one. This is the fix-and-reimport case, because
     *    everyone an import created carries that file's grade.
     *  - A higher grade replaces. The person becomes what the better row says, blanks
     *    included. Keeping the rest would put old claims under a grade they never earned, and
     *    an old death kept under a documented grade makes somebody public record, which lifts
     *    the consent their recordings wait for.
     *
     * What this costs: deleting a wrong value from the file and importing again leaves it in
     * place at the same grade. Replacing it with the right value works.
     *
     * Returns [existing] itself when nothing changes, so a file imported twice gives sync
     * nothing to send. Anything else is stamped past the version it replaces, or a phone
     * whose clock runs slow would lose its own import to that version on the next pull.
     */
    fun merge(existing: PersonEntity, imported: ImportedPerson, nowMillis: Long): PersonEntity {
        val file = weight(imported.confidence)
        val archive = weight(existing.confidence)
        if (file < archive) return existing

        val merged = if (file > archive) {
            existing.copy(
                displayName = imported.displayName,
                alsoKnownAs = imported.alsoKnownAs,
                birthYear = imported.birthYear,
                deathYear = imported.deathYear,
                deathYearEnd = imported.deathYearEnd,
                birthPlace = imported.birthPlace,
                note = imported.note,
                // Not a claim about them. It is this phone's word for them, which no grade
                // vouches for, so a better row without one does not take it away.
                relationLabel = imported.relationLabel ?: existing.relationLabel,
                state = stateOf(imported),
                confidence = imported.confidence,
                source = imported.source
            )
        } else {
            existing.copy(
                displayName = imported.displayName,
                alsoKnownAs = imported.alsoKnownAs.ifEmpty { existing.alsoKnownAs },
                birthYear = imported.birthYear ?: existing.birthYear,
                // The two years are one answer. A file that dates the death states all of it,
                // so a range does not outlive a correction to one exact year.
                deathYear = imported.deathYear ?: existing.deathYear,
                deathYearEnd = if (imported.deathYear != null) imported.deathYearEnd else existing.deathYearEnd,
                birthPlace = imported.birthPlace ?: existing.birthPlace,
                note = imported.note ?: existing.note,
                relationLabel = imported.relationLabel ?: existing.relationLabel,
                state = if (imported.deceased) ProfileState.MEMORIAL else existing.state,
                confidence = imported.confidence,
                source = imported.source ?: existing.source
            )
        }
        return if (merged == existing) existing
        else merged.copy(updatedAt = SyncPolicy.stamp(existing.updatedAt, nowMillis))
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
                // Either a stated flag or a year. A year already means they died, so a file
                // that gives one does not also have to say so.
                deceased = o.optBoolean("deceased") || o.optInt("deathYear") > 0,
                // Only a range if it is actually later. A file repeating the same year twice
                // is stating one year, not an uncertainty.
                deathYearEnd = o.optInt("deathYearEnd")
                    .takeIf { it > 0 && it > o.optInt("deathYear") },
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

    /**
     * A stated death counts as much as a dated one. Requiring a year meant somebody known to
     * have died, with no year anybody recorded, imported as living.
     */
    private fun stateOf(imported: ImportedPerson) =
        if (imported.deceased) ProfileState.MEMORIAL else ProfileState.LIVING

    /**
     * How far a grade can be trusted, for [merge]. The enum is not declared in this order, so
     * compare these and never ordinals.
     *
     * Unverified and conflicted weigh the same. Neither has been checked, and only an
     * imported file marks a dispute, so a file can raise one and can also take the mark off.
     */
    private fun weight(confidence: Confidence): Int = when (confidence) {
        Confidence.DOCUMENTED -> 3
        Confidence.PARTLY_DOCUMENTED -> 2
        Confidence.FAMILY_TOLD -> 1
        Confidence.UNVERIFIED, Confidence.CONFLICTED -> 0
    }
}
