package com.arv.app.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max

/**
 * Where a person's face is kept, and how a picked picture becomes one.
 *
 * Portraits live in their own directory rather than beside documents, because they are not
 * documents. A face here is a display attribute of a person, and treating it as a filed
 * record was the mistake the first version of this made.
 *
 * Every picture is copied and downscaled on the way in. Two reasons, and the second is the
 * one that matters:
 *
 *  - A circle is 72dp at its largest. Holding a 12 megapixel phone photograph to draw it is
 *    how an archive with forty people in it runs a family's phone out of memory.
 *  - A `content://` URI is a temporary grant. It can be revoked, and the file behind it can
 *    be moved or deleted by whatever app owns it. Keeping the URI would mean a face quietly
 *    disappearing the next time somebody cleaned out their gallery.
 */
object PortraitStore {

    /** Big enough for a 72dp circle on the densest screen, small enough to be free. */
    private const val MAX_EDGE = 512

    private const val QUALITY = 88

    private fun dir(context: Context): File =
        File(context.filesDir, "portraits").apply { mkdirs() }

    /**
     * Reads [uri], downscales it, and writes it into the archive as a face.
     *
     * Returns the new file's path, or null when the picture could not be read. Null rather
     * than an exception: a person picking the wrong file from a gallery is an ordinary
     * event, not a crash.
     */
    fun intake(context: Context, uri: Uri): String? = runCatching {
        val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
            // Bounds first, so a huge photograph is never fully decoded just to be shrunk.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val bytes = input.readBytes()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val longest = max(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return@use null

            val opts = BitmapFactory.Options().apply {
                inSampleSize = generateSequence(1) { it * 2 }
                    .first { longest / it <= MAX_EDGE * 2 }
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } ?: return@runCatching null

        write(context, decoded).also { decoded.recycle() }
    }.getOrNull()

    /**
     * Copies a photograph already in the archive into the portraits directory.
     *
     * A copy rather than a reference on purpose. The face is the person's from this moment,
     * so deleting the record it came from does not blank somebody's circle, and the
     * permission decision about that record was made once, when it was chosen.
     */
    fun copyFromArchive(context: Context, sourcePath: String): String? = runCatching {
        val source = File(sourcePath)
        if (!source.exists()) return@runCatching null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourcePath, bounds)
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return@runCatching null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = generateSequence(1) { it * 2 }
                .first { longest / it <= MAX_EDGE * 2 }
        }
        val decoded = BitmapFactory.decodeFile(sourcePath, opts) ?: return@runCatching null
        write(context, decoded).also { decoded.recycle() }
    }.getOrNull()

    private fun write(context: Context, bitmap: Bitmap): String {
        val target = File(dir(context), "${UUID.randomUUID()}.jpg")
        FileOutputStream(target).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        }
        return target.absolutePath
    }

    /**
     * Deletes a face that is being replaced.
     *
     * Only ever called on a path inside the portraits directory, checked here rather than
     * trusted, because this is a delete taking a path from a database column and the one
     * thing it must never do is reach a recording.
     */
    fun discard(context: Context, path: String?) {
        if (path == null) return
        val file = File(path)
        val home = dir(context)
        if (file.parentFile?.canonicalFile == home.canonicalFile) {
            runCatching { file.delete() }
        }
    }
}
