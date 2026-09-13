package com.mrlaki5.mystockmanager.storage

import android.content.Context
import java.io.File

/**
 * Scratch space only.
 *
 * Images themselves are not stored here — they live in one place, the MediaStore album
 * (see [MediaStoreExporter]), so that what the app shows and what a file picker offers
 * can never diverge. These files are transient working copies for hashing, encoding,
 * and metadata rewriting, and are deleted as soon as the operation completes.
 */
class AppFileStore(private val context: Context) {

    val tmp: File get() = File(context.cacheDir, "tmp").apply { mkdirs() }

    fun newTempFile(prefix: String = "work"): File =
        File(tmp, "$prefix-${System.nanoTime()}.jpg")

    /** Runs [block] with a temp file that is always cleaned up afterwards. */
    inline fun <T> withTempFile(prefix: String = "work", block: (File) -> T): T {
        val file = newTempFile(prefix)
        return try {
            block(file)
        } finally {
            file.delete()
        }
    }

    /** Legacy location from before images moved into the MediaStore album. */
    fun legacyOriginal(imageId: Long): File =
        File(File(context.filesDir, "originals"), "$imageId.jpg")

    fun legacyExport(imageId: Long): File =
        File(File(context.filesDir, "exports"), "$imageId.jpg")

    fun legacyDirs(): List<File> = listOf(
        File(context.filesDir, "originals"),
        File(context.filesDir, "exports"),
        File(context.filesDir, "thumbs"),
    )
}
