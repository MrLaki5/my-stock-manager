package com.mrlaki5.mystockmanager.storage

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.mrlaki5.mystockmanager.data.db.dao.ImageDao
import com.mrlaki5.mystockmanager.data.db.entity.ImageEntity
import com.mrlaki5.mystockmanager.data.db.entity.ImageState
import com.mrlaki5.mystockmanager.data.db.entity.MediaKind
import com.mrlaki5.mystockmanager.metadata.EmbeddedMetadata
import com.mrlaki5.mystockmanager.metadata.EmbeddedMetadataReader
import com.mrlaki5.mystockmanager.metadata.model.ParsedMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** A picked file that became a new album copy, kept so its original can be trashed. */
data class ImportedSource(val uri: Uri, val kind: MediaKind)

/** [unsupported] are videos in a format other than MP4. */
data class ImportSummary(
    val imported: Int,
    val duplicates: Int,
    val failed: Int,
    val importedSources: List<ImportedSource> = emptyList(),
    val unsupported: Int = 0,
) {
    val total: Int get() = imported + duplicates + failed + unsupported
}

/**
 * Imports picked photos and MP4 clips straight into the event's album, so they are visible in the
 * gallery and in every file picker immediately — there is no separate private copy and
 * no later "publish" step to forget.
 *
 * The source in the camera roll is opened read-only here; trashing it afterwards is the
 * caller's opt-in step, see [OriginalTrash].
 *
 * Metadata a picked photo already embeds, say from another keywording tool, is taken over.
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

    /** [eventHint] lets a caption lead naming the event's place be split off the description. */
    suspend fun import(uris: List<Uri>, folderId: Long, eventName: String, eventHint: String?): ImportSummary =
        withContext(Dispatchers.IO) {
            val imported = mutableListOf<ImportedSource>()
            var duplicates = 0
            var failed = 0
            var unsupported = 0

            for (uri in uris) {
                when (val outcome = importOne(uri, folderId, eventName, eventHint)) {
                    is Outcome.Imported -> imported += ImportedSource(uri, outcome.kind)
                    Outcome.Duplicate -> duplicates++
                    Outcome.Unsupported -> unsupported++
                    Outcome.Failed -> failed++
                }
            }
            ImportSummary(imported.size, duplicates, failed, imported, unsupported)
        }

    private sealed interface Outcome {
        data class Imported(val kind: MediaKind) : Outcome
        data object Duplicate : Outcome
        data object Unsupported : Outcome
        data object Failed : Outcome
    }

    private suspend fun importOne(uri: Uri, folderId: Long, eventName: String, eventHint: String?): Outcome {
        try {
            val probed = context.contentResolver.openFileDescriptor(uri, "r")?.use { MediaProbe.inspect(it.fileDescriptor) }
                ?: return Outcome.Failed
            if (probed.kind == MediaKind.VIDEO && !isMp4(uri, probed)) return Outcome.Unsupported
            val displayName = displayNameOf(uri) ?: defaultName(probed.kind)

            // A photo needs a file of its own for the embedded metadata reader; it is small enough to copy.
            if (probed.kind == MediaKind.IMAGE) {
                return fileStore.withTempFile("import") { temp ->
                    val sha = copyAndHash(uri, temp) ?: return@withTempFile Outcome.Failed
                    if (imageDao.countDuplicate(folderId, sha) > 0) return@withTempFile Outcome.Duplicate
                    // Publish before inserting, so a failed write never leaves a row pointing at nothing.
                    val published = mediaStore.publish(temp, displayName, eventName, MediaKind.IMAGE)
                    record(folderId, displayName, sha, probed, temp.length(), published, eventHint, EmbeddedMetadataReader.read(temp))
                }
            }

            // Streamed straight into the album, so a clip of several GB needs no second copy's worth of space.
            val sha = copyAndHash(uri, destination = null) ?: return Outcome.Failed
            if (imageDao.countDuplicate(folderId, sha) > 0) return Outcome.Duplicate
            val published = mediaStore.publish(displayName, eventName, MediaKind.VIDEO) {
                context.contentResolver.openInputStream(uri) ?: error("Could not open $uri for reading")
            }
            return record(folderId, displayName, sha, probed, mediaStore.sizeOf(published) ?: 0, published, eventHint, embedded = null)
        } catch (e: Exception) {
            return Outcome.Failed
        }
    }

    /** Agencies take MP4, so a clip either the provider or the decoder names as another format is left out. */
    private fun isMp4(uri: Uri, probed: ProbedMedia): Boolean {
        val declared = context.contentResolver.getType(uri)?.takeIf { it.startsWith("video/") }
        return listOfNotNull(declared, probed.mimeType).all { it == MediaKind.VIDEO.mimeType }
    }

    private suspend fun record(
        folderId: Long,
        displayName: String,
        sha: String,
        probed: ProbedMedia,
        byteSize: Long,
        published: Uri,
        eventHint: String?,
        embedded: EmbeddedMetadata?,
    ): Outcome {
        // Anything still unknown is left for the startup backfill, once MediaStore's scan has caught up.
        val capturedOn = probed.capturedOn
            ?: mediaStore.dateTakenMillis(published)?.let(CaptureDate::fromEpochMillis)
        val metadata = embedded?.let {
            ParsedMetadata.of(it.title, it.caption, it.keywords, it.category, capturedOn, eventHint)
        }
        val now = System.currentTimeMillis()
        imageDao.insert(
            ImageEntity(
                folderId = folderId,
                displayName = displayName,
                sha256 = sha,
                widthPx = probed.widthPx,
                heightPx = probed.heightPx,
                byteSize = byteSize,
                importedAt = now,
                capturedOn = capturedOn,
                state = ImageState.of(metadata),
                title = metadata?.title,
                description = metadata?.description,
                keywords = metadata?.keywords.orEmpty(),
                category = metadata?.category,
                generatedAt = now.takeIf { metadata?.generated == true },
                mediaStoreUri = published.toString(),
                captionPlace = metadata?.captionPlace,
                embeddedChecked = true,
                mediaKind = probed.kind,
                durationMs = probed.durationMs,
            )
        )
        return Outcome.Imported(probed.kind)
    }

    /** Streams the source once, hashing as it goes, and copies it to [destination] when there is one. Null if it could not be read. */
    private fun copyAndHash(uri: Uri, destination: File?): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = context.contentResolver.openInputStream(uri) ?: return null
        input.use {
            destination?.outputStream()?.buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                    output?.write(buffer, 0, read)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun defaultName(kind: MediaKind): String = when (kind) {
        MediaKind.IMAGE -> "image-${System.currentTimeMillis()}.jpg"
        MediaKind.VIDEO -> "video-${System.currentTimeMillis()}.mp4"
    }

    private fun displayNameOf(uri: Uri): String? =
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor: Cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
}
