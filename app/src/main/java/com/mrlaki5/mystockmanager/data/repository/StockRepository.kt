package com.mrlaki5.mystockmanager.data.repository

import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import com.mrlaki5.mystockmanager.data.db.dao.FolderDao
import com.mrlaki5.mystockmanager.data.db.dao.FolderSummary
import com.mrlaki5.mystockmanager.data.db.dao.ImageDao
import com.mrlaki5.mystockmanager.data.db.dao.SyncDao
import com.mrlaki5.mystockmanager.data.db.entity.AlbumState
import com.mrlaki5.mystockmanager.data.db.entity.FolderEntity
import com.mrlaki5.mystockmanager.data.db.entity.ImageEntity
import com.mrlaki5.mystockmanager.data.db.entity.ImageState
import com.mrlaki5.mystockmanager.data.db.entity.MediaKind
import com.mrlaki5.mystockmanager.metadata.EmbeddedMetadataReader
import com.mrlaki5.mystockmanager.metadata.model.ParsedMetadata
import com.mrlaki5.mystockmanager.metadata.model.StockMetadata
import com.mrlaki5.mystockmanager.nextcloud.CloudMark
import com.mrlaki5.mystockmanager.nextcloud.CloudStatus
import com.mrlaki5.mystockmanager.nextcloud.NextcloudSettings
import com.mrlaki5.mystockmanager.nextcloud.mark
import com.mrlaki5.mystockmanager.nextcloud.status
import com.mrlaki5.mystockmanager.storage.CaptureDate
import com.mrlaki5.mystockmanager.storage.AppFileStore
import com.mrlaki5.mystockmanager.storage.ImportCopier
import com.mrlaki5.mystockmanager.storage.ImportSummary
import com.mrlaki5.mystockmanager.storage.MediaStoreExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The outcome of a delete. [albumFilesLeft] is the files MediaStore refused to remove;
 * their rows are gone either way, so the count exists to be shown, not retried.
 */
data class DeleteSummary(val deleted: Int, val albumFilesLeft: Int)

@Singleton
class StockRepository @Inject constructor(
    private val folderDao: FolderDao,
    private val imageDao: ImageDao,
    private val importCopier: ImportCopier,
    private val fileStore: AppFileStore,
    private val mediaStore: MediaStoreExporter,
    private val syncDao: SyncDao,
    private val nextcloud: NextcloudSettings,
) {

    fun observeEvents(): Flow<List<FolderSummary>> = folderDao.observeSummaries()

    fun observeEvent(id: Long): Flow<FolderEntity?> = folderDao.observeById(id)

    fun observeImages(folderId: Long): Flow<List<ImageEntity>> =
        imageDao.observeByFolder(folderId)

    fun observeImage(id: Long): Flow<ImageEntity?> = imageDao.observeById(id)

    /** Images with no mark are left out of the map. */
    fun observeCloudMarks(folderId: Long): Flow<Map<Long, CloudMark>> =
        combine(syncDao.observeCloudRows(folderId), nextcloud.enabledOffMain()) { rows, enabled ->
            rows.mapNotNull { row -> row.mark(enabled)?.let { row.imageId to it } }.toMap()
        }

    fun observeCloudStatus(imageId: Long): Flow<CloudStatus?> =
        combine(syncDao.observeCloudRow(imageId), nextcloud.enabledOffMain()) { row, enabled -> row?.status(enabled) }


    /**
     * Saves hand-edited metadata, clamped to the agency limits. [metadata] carries the caption
     * *body* as its description; the CSV prepends the lead.
     */
    suspend fun updateMetadata(imageId: Long, metadata: StockMetadata): Result<StockMetadata> {
        val image = imageDao.getById(imageId)
            ?: return Result.failure(IllegalStateException("This image no longer exists"))

        val stored = metadata.normalized()
        // A running generation will overwrite the row anyway, so only idle images are promoted.
        if (image.state == ImageState.FILED || image.state == ImageState.GENERATION_FAILED) {
            imageDao.saveHandWritten(
                id = imageId,
                title = stored.title,
                description = stored.description,
                keywords = stored.keywords,
                category = stored.category,
                state = ImageState.GENERATED,
                writtenAt = System.currentTimeMillis(),
            )
        } else {
            imageDao.updateMetadata(
                id = imageId,
                title = stored.title,
                description = stored.description,
                keywords = stored.keywords,
                category = stored.category,
            )
        }
        return Result.success(stored)
    }

    /** Names are unique; returns a message instead of throwing on a collision. */
    suspend fun createEvent(name: String): Result<Long> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("Name cannot be empty"))
        if (folderDao.countWithName(trimmed) > 0) {
            return Result.failure(IllegalArgumentException("An event called \"$trimmed\" already exists"))
        }
        val now = System.currentTimeMillis()
        return runCatching {
            folderDao.insert(FolderEntity(name = trimmed, createdAt = now, updatedAt = now, position = folderDao.topPosition()))
        }
    }

    /**
     * Renames the event and moves its album folder to match. Without the move, the
     * album name a picker shows would silently drift from the event name in the app.
     */
    suspend fun renameEvent(id: Long, name: String): Result<Unit> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("Name cannot be empty"))
        if (folderDao.countWithName(trimmed) > 0) {
            return Result.failure(IllegalArgumentException("An event called \"$trimmed\" already exists"))
        }
        return runCatching {
            folderDao.rename(id, trimmed, System.currentTimeMillis())
            imageDao.observeByFolder(id).first().forEach { image ->
                image.mediaStoreUri?.let {
                    runCatching { mediaStore.moveToEvent(it.toUri(), trimmed) }
                }
            }
        }
    }

    /**
     * Deletes the event and the copies it holds. Safe by construction: these are the
     * app's own copies, and the originals in the camera roll were never touched.
     */
    suspend fun deleteEvent(id: Long) = withContext(Dispatchers.IO) {
        imageDao.observeByFolder(id).first().forEach { removeAlbumCopy(it) }
        // With sync off the cloud copy is deliberately kept, as an archive.
        syncDao.deleteEvent(id, tombstone = nextcloud.enabled.value)
    }

    /**
     * Deletes the given images and the album copies they own. Safe for the same reason
     * [deleteEvent] is: StockReady holds the app's own copies, and the camera-roll
     * originals were never touched.
     *
     * Ids that no longer exist are skipped rather than counted, so a stale selection
     * cannot inflate the number reported back to the user.
     */
    suspend fun deleteImages(ids: Collection<Long>): DeleteSummary = withContext(Dispatchers.IO) {
        var deleted = 0
        var albumFilesLeft = 0
        for (id in ids) {
            val image = imageDao.getById(id) ?: continue
            if (!removeAlbumCopy(image)) albumFilesLeft++
            syncDao.deleteImage(id, tombstone = nextcloud.enabled.value)
            deleted++
        }
        DeleteSummary(deleted, albumFilesLeft)
    }

    /**
     * True when nothing of this image is left in the album. A zero-row delete counts as
     * success: it means the file was already gone, which is the outcome we wanted. Only
     * a refusal — ownership of the MediaStore row lost after a reinstall — leaves a file
     * behind, and the caller reports that rather than dropping it silently.
     */
    private fun removeAlbumCopy(image: ImageEntity): Boolean {
        val uri = image.mediaStoreUri?.toUri() ?: return true
        return runCatching { mediaStore.delete(uri) }.isSuccess
    }

    /** Takes an image the gallery moved to the trash back out of it; false if it could not be restored. */
    suspend fun restoreFromTrash(imageId: Long): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext false
        val uri = imageDao.getById(imageId)?.mediaStoreUri?.toUri() ?: return@withContext false
        val restored = runCatching { mediaStore.restore(uri) }.getOrDefault(false)
        if (restored) imageDao.setAlbumState(imageId, AlbumState.PRESENT, null)
        restored
    }

    suspend fun importInto(folderId: Long, uris: List<Uri>): ImportSummary {
        val event = folderDao.observeById(folderId).first()
            ?: return ImportSummary(0, 0, uris.size)
        return importCopier.import(uris, folderId, event.name, event.hint)
    }

    suspend fun reorderEvents(orderedIds: List<Long>) = folderDao.reorder(orderedIds)

    /** Remembers the hint so the next generation for this event pre-fills it. */
    suspend fun setEventHint(id: Long, hint: String?) =
        folderDao.setHint(id, hint?.trim()?.takeIf { it.isNotEmpty() }, System.currentTimeMillis())

    fun albumNameFor(eventName: String): String = MediaStoreExporter.albumNameFor(eventName)

    fun albumPathFor(eventName: String): String = MediaStoreExporter.relativePathFor(eventName)

    /**
     * Fills in the EXIF capture date for images imported before the editorial caption
     * needed one. Reads each album file's header rather than copying it out, so this is
     * cheap enough to run at startup; rows whose files carry no date are left null and
     * are not retried on every launch beyond a header read.
     */
    suspend fun backfillCaptureDates() = withContext(Dispatchers.IO) {
        for (image in imageDao.getWithoutCaptureDate()) {
            val uri = image.mediaStoreUri?.toUri() ?: continue
            // A clip's own date was already read on import, so only MediaStore's is worth asking again.
            val fromFile = if (image.mediaKind == MediaKind.IMAGE) {
                runCatching { mediaStore.openInput(uri)?.use { CaptureDate.readFrom(it) } }.getOrNull()
            } else {
                null
            }
            val captured = fromFile
                ?: mediaStore.dateTakenMillis(uri)?.let(CaptureDate::fromEpochMillis)
                ?: continue
            imageDao.setCapturedOn(image.id, captured)
        }
    }

    /** Reads each album file's embedded metadata once, for images with none of their own; sync then puts it in the CSV. */
    suspend fun adoptEmbeddedMetadata() = withContext(Dispatchers.IO) {
        for (image in imageDao.getUncheckedForEmbedded()) {
            val uri = image.mediaStoreUri?.toUri() ?: continue
            // A file that cannot be read now is retried on the next launch.
            val embedded = fileStore.withTempFile("embedded") { temp ->
                runCatching { mediaStore.copyTo(uri, temp) }.map { EmbeddedMetadataReader.read(temp) }
            }.getOrElse { continue }
            if (embedded == null) {
                imageDao.markEmbeddedChecked(image.id)
                continue
            }
            val hint = image.folderId?.let { folderDao.observeById(it).first()?.hint }
            val metadata = ParsedMetadata.of(embedded.title, embedded.caption, embedded.keywords, embedded.category, image.capturedOn, hint)
            imageDao.adoptEmbedded(
                id = image.id,
                title = metadata.title,
                description = metadata.description,
                captionPlace = metadata.captionPlace,
                keywords = metadata.keywords,
                category = metadata.category,
                state = ImageState.of(metadata),
                generatedAt = System.currentTimeMillis().takeIf { metadata.generated },
            )
        }
    }

    /**
     * A process death mid-generation leaves rows stuck in GENERATING with no worker
     * behind them. Called at startup so the UI never shows a spinner that will never end.
     */
    suspend fun resetStuckGenerating() = imageDao.restoreStuckGenerating()

    /**
     * One-time move of images imported before the app kept a single copy in the album.
     * Publishes the best available private copy and then removes the private directories.
     */
    suspend fun migrateLegacyPrivateFiles() {
        val orphans = imageDao.getWithoutMediaStoreUri()
        for (image in orphans) {
            val source = fileStore.legacyExport(image.id)
                .takeIf { it.exists() && it.length() > 0 }
                ?: fileStore.legacyOriginal(image.id).takeIf { it.exists() && it.length() > 0 }
                ?: continue

            val event = image.folderId?.let { folderDao.observeById(it).first() } ?: continue
            runCatching { mediaStore.publish(source, image.displayName, event.name, MediaKind.IMAGE) }
                .onSuccess { uri ->
                    imageDao.markExported(image.id, uri.toString(), System.currentTimeMillis())
                    fileStore.legacyExport(image.id).delete()
                    fileStore.legacyOriginal(image.id).delete()
                }
        }
        // Only sweep the directories once nothing is left pointing into them.
        if (imageDao.getWithoutMediaStoreUri().isEmpty()) {
            fileStore.legacyDirs().forEach { dir -> runCatching { dir.deleteRecursively() } }
        }
    }
}
