package com.mrlaki5.mystockmanager.data.repository

import android.net.Uri
import androidx.core.net.toUri
import com.mrlaki5.mystockmanager.data.db.dao.FolderDao
import com.mrlaki5.mystockmanager.data.db.dao.FolderSummary
import com.mrlaki5.mystockmanager.data.db.dao.ImageDao
import com.mrlaki5.mystockmanager.data.db.entity.FolderEntity
import com.mrlaki5.mystockmanager.data.db.entity.ImageEntity
import com.mrlaki5.mystockmanager.data.db.entity.ImageState
import com.mrlaki5.mystockmanager.metadata.MetadataEmbedder
import com.mrlaki5.mystockmanager.metadata.model.EditorialTitle
import com.mrlaki5.mystockmanager.metadata.model.StockMetadata
import com.mrlaki5.mystockmanager.storage.CaptureDate
import com.mrlaki5.mystockmanager.storage.AppFileStore
import com.mrlaki5.mystockmanager.storage.ImportCopier
import com.mrlaki5.mystockmanager.storage.ImportSummary
import com.mrlaki5.mystockmanager.storage.MediaStoreExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
    private val embedder: MetadataEmbedder,
) {

    fun observeEvents(): Flow<List<FolderSummary>> = folderDao.observeSummaries()

    fun observeEvent(id: Long): Flow<FolderEntity?> = folderDao.observeById(id)

    fun observeImages(folderId: Long): Flow<List<ImageEntity>> =
        imageDao.observeByFolder(folderId)

    fun observeImage(id: Long): Flow<ImageEntity?> = imageDao.observeById(id)

    /**
     * The event location an image inherits, for a screen that wants to render the caption
     * as the user types rather than only after saving.
     */
    suspend fun locationFor(image: ImageEntity): String? =
        image.folderId?.let { folderDao.observeById(it).first()?.location }

    /**
     * Saves hand-edited metadata, writing it into the album file before the row.
     *
     * That order matters: the file is what gets uploaded, so a row claiming a keyword the
     * JPEG does not carry would be a lie the user cannot see. If the embed fails, nothing
     * is saved and the caller is told why.
     *
     * The title on [metadata] is ignored and rebuilt. The caption format is an invariant of
     * the file rather than a suggestion to callers, so it is derived at the one point that
     * writes it and cannot be bypassed by a screen that forgot.
     */
    suspend fun updateMetadata(imageId: Long, metadata: StockMetadata): Result<StockMetadata> {
        val image = imageDao.getById(imageId)
            ?: return Result.failure(IllegalStateException("This image no longer exists"))
        val uri = image.mediaStoreUri?.toUri()
            ?: return Result.failure(IllegalStateException("This image is not in the album"))

        val captioned = metadata.copy(
            title = EditorialTitle.build(
                location = image.folderId?.let { folderDao.observeById(it).first()?.location },
                capturedOn = image.capturedOn,
                description = metadata.description,
            ),
        )

        return embedder.embed(uri, captioned).onSuccess { written ->
            imageDao.updateMetadata(
                id = imageId,
                title = written.title,
                description = written.description,
                keywords = written.keywords,
                category = written.category,
            )
        }
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
            folderDao.insert(FolderEntity(name = trimmed, createdAt = now, updatedAt = now))
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
    suspend fun deleteEvent(id: Long) {
        imageDao.observeByFolder(id).first().forEach { image ->
            removeAlbumCopy(image)
            imageDao.delete(image.id)
        }
        folderDao.delete(id)
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
            imageDao.delete(id)
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

    suspend fun importInto(folderId: Long, uris: List<Uri>): ImportSummary {
        val event = folderDao.observeById(folderId).first()
            ?: return ImportSummary(0, 0, uris.size)
        return importCopier.import(uris, folderId, event.name)
    }

    /** Remembers the location so the next generation for this event pre-fills it. */
    suspend fun setEventLocation(id: Long, location: String?) =
        folderDao.setLocation(id, location?.trim()?.takeIf { it.isNotEmpty() }, System.currentTimeMillis())

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
            val captured = runCatching {
                mediaStore.openInput(uri)?.use { CaptureDate.readFrom(it) }
            }.getOrNull()
                ?: mediaStore.dateTakenMillis(uri)?.let(CaptureDate::fromEpochMillis)
                ?: continue
            imageDao.setCapturedOn(image.id, captured)
        }
    }

    /**
     * A process death mid-generation leaves rows stuck in GENERATING with no worker
     * behind them. Called at startup so the UI never shows a spinner that will never end.
     */
    suspend fun resetStuckGenerating() = imageDao.resetState(
        from = ImageState.GENERATING,
        to = ImageState.FILED,
    )

    /**
     * One-time move of images imported before the app kept a single copy in the album.
     * Publishes the best available private copy (the metadata-embedded one if
     * generation had already run) and then removes the private directories.
     */
    suspend fun migrateLegacyPrivateFiles() {
        val orphans = imageDao.getWithoutMediaStoreUri()
        for (image in orphans) {
            val source = fileStore.legacyExport(image.id)
                .takeIf { it.exists() && it.length() > 0 }
                ?: fileStore.legacyOriginal(image.id).takeIf { it.exists() && it.length() > 0 }
                ?: continue

            val event = image.folderId?.let { folderDao.observeById(it).first() } ?: continue
            runCatching { mediaStore.publish(source, image.displayName, event.name) }
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
