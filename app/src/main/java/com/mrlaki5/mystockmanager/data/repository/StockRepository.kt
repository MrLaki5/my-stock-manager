package com.mrlaki5.mystockmanager.data.repository

import android.net.Uri
import androidx.core.net.toUri
import com.mrlaki5.mystockmanager.data.db.dao.FolderDao
import com.mrlaki5.mystockmanager.data.db.dao.FolderSummary
import com.mrlaki5.mystockmanager.data.db.dao.ImageDao
import com.mrlaki5.mystockmanager.data.db.entity.FolderEntity
import com.mrlaki5.mystockmanager.data.db.entity.ImageEntity
import com.mrlaki5.mystockmanager.data.db.entity.ImageState
import com.mrlaki5.mystockmanager.storage.AppFileStore
import com.mrlaki5.mystockmanager.storage.ImportCopier
import com.mrlaki5.mystockmanager.storage.ImportSummary
import com.mrlaki5.mystockmanager.storage.MediaStoreExporter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StockRepository @Inject constructor(
    private val folderDao: FolderDao,
    private val imageDao: ImageDao,
    private val importCopier: ImportCopier,
    private val fileStore: AppFileStore,
    private val mediaStore: MediaStoreExporter,
) {

    fun observeEvents(): Flow<List<FolderSummary>> = folderDao.observeSummaries()

    fun observeEvent(id: Long): Flow<FolderEntity?> = folderDao.observeById(id)

    fun observeImages(folderId: Long): Flow<List<ImageEntity>> =
        imageDao.observeByFolder(folderId)

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
            image.mediaStoreUri?.let { runCatching { mediaStore.delete(it.toUri()) } }
            imageDao.delete(image.id)
        }
        folderDao.delete(id)
    }

    suspend fun deleteImage(image: ImageEntity) {
        image.mediaStoreUri?.let { runCatching { mediaStore.delete(it.toUri()) } }
        imageDao.delete(image.id)
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
