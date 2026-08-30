package com.arv.app.core.ai

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Gets the speech model onto the phone once, and never again.
 *
 * The model is fetched rather than shipped in the APK. It is about 40 MB of binary that
 * never changes, and committing it would take this repository from roughly 5 MB to 45 MB
 * permanently, since git keeps every version of every blob forever.
 *
 * Fetching a model is not the same as uploading a recording. The promise printed on the
 * onboarding screen is that a family's audio never leaves the phone, and it does not: the
 * audio is decoded and recognized locally, by this model, with no network involved. What
 * crosses the wire here is an app asset, the same category as the APK itself.
 *
 * After the one-time download, transcription works with the phone in airplane mode.
 */
class VoskModelStore(context: Context) {

    private val root = File(context.filesDir, "vosk")
    private val modelDir = File(root, MODEL_NAME)

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * True when a usable model is on disk.
     *
     * Checks for a file the model cannot work without rather than merely that the folder
     * exists, so a download interrupted halfway does not read as success and fail later
     * with something unrecognizable.
     */
    val isReady: Boolean
        get() = File(modelDir, "am/final.mdl").exists() &&
            File(modelDir, "conf/model.conf").exists()

    /** Absolute path Vosk wants, or null if [isReady] is false. */
    fun path(): String? = if (isReady) modelDir.absolutePath else null

    /** Roughly how much this will cost someone on a metered connection. */
    val downloadBytes: Long get() = APPROX_BYTES

    /**
     * Downloads and unpacks the model. Safe to call when already present: returns
     * immediately. Blocking; call it off the main thread.
     */
    fun ensure(onProgress: (Float) -> Unit = {}): Result<String> {
        if (isReady) return Result.success(modelDir.absolutePath)

        return runCatching {
            root.mkdirs()

            // Unpack into a staging directory and move it into place only once it is
            // complete and verified. A half-written model that looks present is worse
            // than no model, because it fails at transcription time instead of setup.
            val staging = File(root, "staging").apply {
                deleteRecursively()
                mkdirs()
            }

            val request = Request.Builder().url(MODEL_URL).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Model download failed: HTTP ${response.code}")
                val body = response.body ?: error("Model download returned no body")
                val total = body.contentLength().takeIf { it > 0 } ?: APPROX_BYTES
                var read = 0L

                ZipInputStream(body.byteStream()).use { zip ->
                    var entry = zip.nextEntry
                    val buffer = ByteArray(64 * 1024)
                    while (entry != null) {
                        val target = File(staging, entry.name)

                        // Zip entries are attacker-controlled paths in the general case.
                        // Refuse anything that resolves outside the staging directory.
                        if (!target.canonicalPath.startsWith(staging.canonicalPath)) {
                            error("Refusing zip entry outside the model directory: ${entry.name}")
                        }

                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out ->
                                while (true) {
                                    val n = zip.read(buffer)
                                    if (n < 0) break
                                    out.write(buffer, 0, n)
                                    read += n
                                    onProgress((read.toFloat() / total).coerceIn(0f, 0.99f))
                                }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }

            val unpacked = File(staging, MODEL_NAME).takeIf { it.isDirectory }
                ?: staging.listFiles()?.firstOrNull { it.isDirectory }
                ?: error("Model archive had no directory inside it")

            modelDir.deleteRecursively()
            if (!unpacked.renameTo(modelDir)) {
                unpacked.copyRecursively(modelDir, overwrite = true)
            }
            staging.deleteRecursively()

            if (!isReady) error("Model unpacked but is missing its acoustic model")
            onProgress(1f)
            modelDir.absolutePath
        }.onFailure {
            // Leave nothing behind that could later be mistaken for a working model.
            File(root, "staging").deleteRecursively()
        }
    }

    /** Removes the model. The next transcription will need the one-time setup again. */
    fun clear() {
        root.deleteRecursively()
    }

    private companion object {
        const val MODEL_NAME = "vosk-model-small-en-us-0.15"
        const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
        const val APPROX_BYTES = 41_205_931L
    }
}
