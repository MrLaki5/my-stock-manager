package com.mrlaki5.mystockmanager.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.mrlaki5.mystockmanager.data.db.entity.FolderEntity
import com.mrlaki5.mystockmanager.data.db.entity.ImageEntity
import com.mrlaki5.mystockmanager.data.db.entity.ImageState
import com.mrlaki5.mystockmanager.data.db.entity.SyncFolderEntity
import com.mrlaki5.mystockmanager.data.db.entity.SyncImageEntity
import com.mrlaki5.mystockmanager.nextcloud.LocalFolder
import com.mrlaki5.mystockmanager.nextcloud.LocalImage
import com.mrlaki5.mystockmanager.nextcloud.PullStore
import com.mrlaki5.mystockmanager.nextcloud.PulledImage

@Dao
abstract class PullDao : PullStore {

    override suspend fun pendingDeletions(): Set<String> = deletionPaths().toSet()

    @Query(
        """
        SELECT f.id AS id, f.name AS name, f.location AS location, sf.remoteName AS remoteName
        FROM folders f LEFT JOIN sync_folders sf ON sf.folderId = f.id
        """
    )
    abstract override suspend fun localFolders(): List<LocalFolder>

    @Query(
        """
        SELECT i.id AS id, i.displayName AS displayName, i.mediaStoreUri AS mediaStoreUri,
            i.fileVersion AS fileVersion, s.remoteFileName AS remoteFileName,
            s.syncedVersion AS syncedVersion, s.remoteSize AS remoteSize
        FROM images i LEFT JOIN sync_images s ON s.imageId = i.id
        WHERE i.folderId = :folderId
        """
    )
    abstract override suspend fun localImages(folderId: Long): List<LocalImage>

    @Transaction
    override suspend fun createLinkedEvent(name: String, remoteName: String, now: Long): LocalFolder {
        var candidate = name
        var suffix = 2
        while (countWithName(candidate) > 0) candidate = "$name (${suffix++})"
        val id = insertFolder(FolderEntity(name = candidate, createdAt = now, updatedAt = now, position = topPosition()))
        upsertSyncFolder(SyncFolderEntity(id, remoteName, candidate))
        return LocalFolder(id, candidate, location = null, remoteName = remoteName)
    }

    override suspend fun linkFolder(folderId: Long, remoteName: String, localName: String) =
        upsertSyncFolder(SyncFolderEntity(folderId, remoteName, localName))

    @Query("UPDATE folders SET location = :location WHERE id = :folderId AND (location IS NULL OR location = '')")
    abstract override suspend fun setLocationIfMissing(folderId: Long, location: String)

    @Transaction
    override suspend fun insertPulled(folderId: Long, displayName: String, image: PulledImage, now: Long): Long {
        val id = insertImage(
            ImageEntity(
                folderId = folderId,
                displayName = displayName,
                sha256 = image.sha256,
                widthPx = image.widthPx,
                heightPx = image.heightPx,
                byteSize = image.byteSize,
                importedAt = now,
                capturedOn = image.capturedOn,
                state = stateOf(image),
                title = image.title,
                description = image.description,
                captionPlace = image.captionPlace,
                keywords = image.keywords,
                category = image.category,
                generatedAt = now.takeIf { image.generated },
                mediaStoreUri = image.mediaStoreUri,
            )
        )
        upsertSyncImage(SyncImageEntity(id, image.remoteFileName, syncedVersion = 1, remoteSize = image.byteSize))
        return id
    }

    @Transaction
    override suspend fun replacePulled(imageId: Long, image: PulledImage, now: Long) {
        updatePulled(
            id = imageId,
            sha256 = image.sha256,
            widthPx = image.widthPx,
            heightPx = image.heightPx,
            byteSize = image.byteSize,
            capturedOn = image.capturedOn,
            state = stateOf(image),
            title = image.title,
            description = image.description,
            captionPlace = image.captionPlace,
            keywords = image.keywords,
            category = image.category,
            generatedAt = now.takeIf { image.generated },
            mediaStoreUri = image.mediaStoreUri,
        )
        // The bumped version is recorded as synced, so the replaced file is not uploaded straight back.
        upsertSyncImage(SyncImageEntity(imageId, image.remoteFileName, fileVersionOf(imageId), image.byteSize))
    }

    @Transaction
    override suspend fun linkImage(imageId: Long, remoteFileName: String, size: Long) =
        upsertSyncImage(SyncImageEntity(imageId, remoteFileName, fileVersionOf(imageId), size))

    private fun stateOf(image: PulledImage) = if (image.generated) ImageState.GENERATED else ImageState.FILED

    @Query("SELECT path FROM remote_deletions")
    protected abstract suspend fun deletionPaths(): List<String>

    @Query("SELECT COUNT(*) FROM folders WHERE name = :name COLLATE NOCASE")
    protected abstract suspend fun countWithName(name: String): Int

    @Query("SELECT COALESCE(MIN(position), 0) - 1 FROM folders")
    protected abstract suspend fun topPosition(): Int

    @Query("SELECT fileVersion FROM images WHERE id = :imageId")
    protected abstract suspend fun fileVersionOf(imageId: Long): Long

    @Insert
    protected abstract suspend fun insertFolder(folder: FolderEntity): Long

    @Insert
    protected abstract suspend fun insertImage(image: ImageEntity): Long

    @Upsert
    protected abstract suspend fun upsertSyncFolder(entity: SyncFolderEntity)

    @Upsert
    protected abstract suspend fun upsertSyncImage(entity: SyncImageEntity)

    @Query(
        """
        UPDATE images
        SET sha256 = :sha256, widthPx = :widthPx, heightPx = :heightPx, byteSize = :byteSize,
            capturedOn = :capturedOn, state = :state, title = :title, description = :description,
            captionPlace = :captionPlace,
            keywords = :keywords, category = :category, generatedAt = :generatedAt,
            mediaStoreUri = :mediaStoreUri, generationError = NULL, fileVersion = fileVersion + 1
        WHERE id = :id
        """
    )
    protected abstract suspend fun updatePulled(
        id: Long,
        sha256: String,
        widthPx: Int,
        heightPx: Int,
        byteSize: Long,
        capturedOn: String?,
        state: ImageState,
        title: String?,
        description: String?,
        captionPlace: String?,
        keywords: List<String>,
        category: String?,
        generatedAt: Long?,
        mediaStoreUri: String,
    )
}
