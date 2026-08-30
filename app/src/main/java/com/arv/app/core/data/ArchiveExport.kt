package com.arv.app.core.data

import com.arv.app.core.ai.MemoryAccess
import com.arv.app.core.ai.Viewer
import com.arv.app.core.data.local.ArvDatabase
import com.arv.app.core.data.local.toDomain
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes the whole archive out as one file somebody can keep.
 *
 * The app refuses to fall back destructively on a schema change and it refuses to upload
 * anything without being asked, both of which are right, and both of which add up to a
 * family's only copy living in one folder on one phone. Uninstall it, drop it, run an
 * instrumented test against it, and the recordings are gone. No amount of care inside the
 * app fixes that; the archive has to be able to leave.
 *
 * Three things go in the zip, and the third is the point:
 *
 *   recordings/   the audio, byte for byte
 *   documents/    the scans and photographs
 *   archive.json  people, relationships, stories, assets and transcripts
 *   index.html    a plain page listing everything, openable in any browser
 *
 * The index exists because this archive is meant to outlive the software. In twenty years
 * Arv may not run on anything, and a folder of UUID-named .m4a files is not an inheritance.
 * A page that says whose voice each file is, when it was recorded and what was said in it
 * still is.
 */
object ArchiveExport {

    /**
     * Streams the archive into [out] as a zip.
     *
     * Nothing is buffered whole: a family with hours of interviews would not fit in memory
     * on the phone that recorded them.
     */
    suspend fun writeTo(
        out: OutputStream,
        db: ArvDatabase,
        familyId: String,
        familyName: String,
        filesDir: File,
        viewer: Viewer,
        onProgress: (Float) -> Unit = {}
    ) {
        val people = db.personDao().all(familyId)
        val consentContext = people.map { it.toDomain() }
        // The zip holds exactly what this viewer may read inside the app, and nothing
        // more. Export is the easiest place to lose that promise: it ran unfiltered, so
        // anyone in the family could carry out every private story, transcript and
        // recording as a file.
        val stories = db.storyDao().all(familyId)
            .filter { MemoryAccess.canRead(it.toDomain(), viewer, consentContext) }
        val allowed = stories.map { it.storyId }.toSet()
        val relationships = db.relationshipDao().observeAllOnce(familyId)
        val assets = db.assetDao().forFamily(familyId).filter { it.storyId in allowed }
        val transcripts = assets.flatMap { db.transcriptDao().forAssetOnce(it.assetId) }

        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("archive.json"))
            zip.write(
                manifest(familyId, familyName, people, stories, relationships, assets, transcripts)
                    .toString(2).toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("index.html"))
            zip.write(indexHtml(familyName, people, stories, assets, transcripts).toByteArray())
            zip.closeEntry()

            // Only files this family's assets actually point at. An export is not a dump of
            // whatever happens to be in app storage.
            val wanted = assets.mapNotNull { asset ->
                File(asset.localPath).takeIf { it.exists() }?.let { asset.assetId to it }
            }
            wanted.forEachIndexed { i, (_, file) ->
                val folder = if (file.parentFile?.name == "documents") "documents" else "recordings"
                zip.putNextEntry(ZipEntry("$folder/${file.name}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                onProgress((i + 1f) / wanted.size.coerceAtLeast(1))
            }
        }
        onProgress(1f)
    }

    private fun manifest(
        familyId: String,
        familyName: String,
        people: List<com.arv.app.core.data.local.PersonEntity>,
        stories: List<com.arv.app.core.data.local.StoryEntity>,
        relationships: List<com.arv.app.core.data.local.RelationshipEntity>,
        assets: List<com.arv.app.core.data.local.AssetEntity>,
        transcripts: List<com.arv.app.core.data.local.TranscriptSegmentEntity>
    ) = JSONObject().apply {
        put("format", "arv-archive")
        put("formatVersion", 1)
        put("familyId", familyId)
        put("familyName", familyName)
        put("people", JSONArray(people.map { p ->
            JSONObject().apply {
                put("personId", p.personId)
                put("displayName", p.displayName)
                put("alsoKnownAs", JSONArray(p.alsoKnownAs))
                p.birthYear?.let { put("birthYear", it) }
                p.deathYear?.let { put("deathYear", it) }
                p.birthPlace?.let { put("birthPlace", it) }
                p.relationLabel?.let { put("relationLabel", it) }
                // Provenance travels with the person. An export that flattened a guess and
                // a death certificate into the same thing would undo the whole point.
                put("confidence", p.confidence.name)
                p.source?.let { put("source", it) }
            }
        }))
        put("relationships", JSONArray(relationships.map { r ->
            JSONObject().apply {
                put("from", r.fromPersonId)
                put("to", r.toPersonId)
                put("kind", r.kind.name)
                put("uncertain", r.uncertain)
            }
        }))
        put("stories", JSONArray(stories.map { s ->
            JSONObject().apply {
                put("storyId", s.storyId)
                put("title", s.title)
                put("kind", s.kind.name)
                put("area", s.area.name)
                put("narratorIds", JSONArray(s.narratorIds))
                put("subjectPersonIds", JSONArray(s.subjectPersonIds))
                s.eraStart?.let { put("eraStart", it) }
                s.eraEnd?.let { put("eraEnd", it) }
                s.placeLabel?.let { put("placeLabel", it) }
                put("tags", JSONArray(s.tags))
                put("visibility", s.visibility.name)
                put("provenance", s.provenance.name)
                s.branchRootPersonId?.let { put("branchRootPersonId", it) }
                put("durationMs", s.durationMs)
                put("createdAt", s.createdAt)
            }
        }))
        put("assets", JSONArray(assets.map { a ->
            JSONObject().apply {
                put("assetId", a.assetId)
                put("storyId", a.storyId)
                put("type", a.type.name)
                put("mimeType", a.mimeType)
                put("bytes", a.bytes)
                // The path inside the zip, not the path on the phone that made it.
                put("file", File(a.localPath).name)
            }
        }))
        put("transcripts", JSONArray(transcripts.map { t ->
            JSONObject().apply {
                put("assetId", t.assetId)
                put("startMs", t.startMs)
                put("endMs", t.endMs)
                put("text", t.text)
                put("humanVerified", t.humanVerified)
                t.originalText?.let { put("originalText", it) }
            }
        }))
    }

    /** A page that still means something when nothing can open the .json. */
    private fun indexHtml(
        familyName: String,
        people: List<com.arv.app.core.data.local.PersonEntity>,
        stories: List<com.arv.app.core.data.local.StoryEntity>,
        assets: List<com.arv.app.core.data.local.AssetEntity>,
        transcripts: List<com.arv.app.core.data.local.TranscriptSegmentEntity>
    ): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val nameOf = people.associate { it.personId to it.displayName }

        val body = StringBuilder()
        body.append("<h1>${esc(familyName)}</h1>")
        body.append("<p>${stories.size} memories, ${people.size} people. ")
        body.append("Open any audio file in the recordings folder with any player.</p>")

        for (story in stories.sortedBy { it.eraStart ?: Int.MAX_VALUE }) {
            body.append("<article><h2>${esc(story.title)}</h2>")
            val told = story.narratorIds.mapNotNull { nameOf[it] }
            if (told.isNotEmpty()) body.append("<p>Told by ${esc(told.joinToString(", "))}</p>")
            story.eraStart?.let { body.append("<p>${it}</p>") }

            val asset = assets.firstOrNull { it.storyId == story.storyId }
            if (asset != null) {
                val folder = if (asset.type.name == "AUDIO") "recordings" else "documents"
                val file = File(asset.localPath).name
                body.append("<p><a href=\"$folder/${esc(file)}\">$folder/${esc(file)}</a></p>")
                val lines = transcripts.filter { it.assetId == asset.assetId }.sortedBy { it.startMs }
                if (lines.isNotEmpty()) {
                    body.append("<blockquote>")
                    for (l in lines) body.append("<p>${esc(l.text)}</p>")
                    body.append("</blockquote>")
                }
            }
            body.append("</article>")
        }

        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<title>${esc(familyName)}</title>
<style>
 body{font:16px/1.6 Georgia,serif;max-width:42rem;margin:3rem auto;padding:0 1rem;color:#1B2A20;background:#FAF6EC}
 h1{font-size:2rem} h2{font-size:1.2rem;margin-bottom:.2rem}
 article{border-top:1px solid #D9D1BE;padding:1.5rem 0}
 blockquote{margin:1rem 0;padding-left:1rem;border-left:3px solid #D9D1BE;color:#4F5A4F}
</style></head><body>$body</body></html>"""
    }
}
