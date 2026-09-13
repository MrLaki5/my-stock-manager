package com.mrlaki5.mystockmanager.storage

import android.content.Context
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.mrlaki5.mystockmanager.data.db.dao.ImageDao
import com.mrlaki5.mystockmanager.data.db.entity.ImageEntity
import com.mrlaki5.mystockmanager.data.db.entity.ImageState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class ImportSummary(val imported: Int, val duplicates: Int, val failed: Int) {
    val total: Int get() = imported + duplicates + failed
}

/**
 * Imports picked images straight into the event's album, so they are visible in the
 * gallery and in every file picker immediately — there is no separate private copy and
 * no later "publish" step to forget.
 *
 * The source in the camera roll is opened read-only and never modified.
 *
 * Runs while the caller still holds the URI grant: grants from a share intent are
 * transient, so this must not be deferred to a background worker holding raw URIs.
 */
@Singleton
class ImportCopier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val fileStore: AppFileStore,
    private val mediaStore: MediaStoreExporter,
    private val imageDao: ImageDao,
) {

    suspend fun import(uris: List<Uri>, folderId: Long, eventName: String): ImportSummary =
        withContext(Dispatchers.IO) {
            var imported = 0
            var duplicates = 0
            var failed = 0

            for (uri in uris) {
                when (importOne(uri, folderId, eventName)) {
                    Outcome.IMPORTED -> imported++
                    Outcome.DUPLICATE -> duplicates++
                    Outcome.FAILED -> failed++
                }
            }
            ImportSummary(imported, duplicates, failed)
        }

    private enum class Outcome { IMPORTED, DUPLICATE, FAILED }

    private suspend fun importOne(uri: Uri, folderId: Long, eventName: String): Outcome =
        fileStore.withTempFile("import") { temp ->
            try {
                val sha = copyAndHash(uri, temp) ?: return@withTempFile Outcome.FAILED

                if (imageDao.countDuplicate(folderId, sha) > 0) return@withTempFile Outcome.DUPLICATE

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(temp.absolutePath, bounds)
                if (bounds.outWidth <= 0) return@withTempFile Outcome.FAILED

                val displayName = displayNameOf(uri)
                    ?: "image-${System.currentTimeMillis()}.jpg"

                // Publish before inserting, so a failed write never leaves a row
                // pointing at nothing.
                val published = mediaStore.publish(temp, displayName, eventName)

                // EXIF first, since it is the camera's own record. Plenty of libraries
                // have been through an editor that dropped it, so fall back to what
                // MediaStore worked out; anything still unknown is left for the startup
                // backfill to retry once the scan has caught up.
                val capturedOn = CaptureDate.readFrom(temp)
                    ?: mediaStore.dateTakenMillis(published)?.let(CaptureDate::fromEpochMillis)

                imageDao.insert(
                    ImageEntity(
                        folderId = folderId,
                        displayName = displayName,
                        sha256 = sha,
                        widthPx = bounds.outWidth,
                        heightPx = bounds.outHeight,
                        byteSize = temp.length(),
                        importedAt = System.currentTimeMillis(),
                        capturedOn = capturedOn,
                        state = ImageState.FILED,
                        mediaStoreUri = published.toString(),
                    )
                )
                Outcome.IMPORTED
            } catch (e: Exception) {
                Outcome.FAILED
            }
        }

    /** Streams the source once, hashing as it goes. Returns null if it could not be read. */
    private fun copyAndHash(uri: Uri, destination: File): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
        } ?: return null
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun displayNameOf(uri: Uri): String? =
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor: Cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
}
