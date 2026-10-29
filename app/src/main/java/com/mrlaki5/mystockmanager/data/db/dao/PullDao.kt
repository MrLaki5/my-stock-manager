package com.mrlaki5.mystockmanager.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.mrlaki5.mystockmanager.data.db.entity.FolderEntity
import com.mrlaki5.mystockmanager.data.db.entity.ImageEntity
import com.mrlaki5.mystockmanager.data.db.entity.ImageState
import com.mrlaki5.mystockmanager.data.db.entity.MediaKind
import com.mrlaki5.mystockmanager.data.db.entity.SyncFolderEntity
import com.mrlaki5.mystockmanager.data.db.entity.SyncImageEntity
import com.mrlaki5.mystockmanager.nextcloud.LocalFolder
import com.mrlaki5.mystockmanager.nextcloud.LocalImage
import com.mrlaki5.mystockmanager.nextcloud.PullStore
import com.mrlaki5.mystockmanager.nextcloud.PulledImage
import com.mrlaki5.mystockmanager.metadata.model.ParsedMetadata

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
            i.fileVersion AS fileVersion, i.capturedOn AS capturedOn, i.title AS title,
            i.description AS description, i.captionPlace AS captionPlace, i.keywords AS keywords,
            i.category AS category, s.remoteFileName AS remoteFileName,
            s.syncedVersion AS syncedVersion, s.remoteSize AS remoteSize, i.mediaKind AS kind
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
        val metadata = image.csvMetadata ?: image.embeddedMetadata
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
                state = ImageState.of(metadata),
                title = metadata?.title,
                description = metadata?.description,
                captionPlace = metadata?.captionPlace,
                keywords = metadata?.keywords.orEmpty(),
                category = metadata?.category,
                generatedAt = now.takeIf { metadata?.generated == true },
                mediaStoreUri = image.mediaStoreUri,
                embeddedChecked = true,
                mediaKind = image.kind,
                durationMs = image.durationMs,
            )
        )
        // Matches the cloud CSV, row or no row, unless the metadata came from the file and is not in it yet.
        val csvVersion = 1L.takeIf { image.csvMetadata != null || image.embeddedMetadata == null }
        upsertSyncImage(SyncImageEntity(id, image.remoteFileName, syncedVersion = 1, remoteSize = image.byteSize, csvVersion = csvVersion))
        return id
    }

    @Transaction
    override suspend fun replacePulled(imageId: Long, image: PulledImage, now: Long) {
        updatePulledFile(
            id = imageId,
            sha256 = image.sha256,
            widthPx = image.widthPx,
            heightPx = image.heightPx,
            byteSize = image.byteSize,
            capturedOn = image.capturedOn,
            mediaStoreUri = image.mediaStoreUri,
            mediaKind = image.kind,
            durationMs = image.durationMs,
        )
        // The bumped version is recorded as synced, so the replaced file is not uploaded straight back.
        upsertSyncImage(
            SyncImageEntity(imageId, image.remoteFileName, fileVersionOf(imageId), image.byteSize, csvVersion = csvVersionOf(imageId)),
        )
        image.csvMetadata?.let { applyMetadata(imageId, it, now) }
            ?: image.embeddedMetadata?.let { adoptEmbedded(imageId, it, now) }
    }

    @Transaction
    override suspend fun linkImage(imageId: Long, remoteFileName: String, size: Long) =
        upsertSyncImage(SyncImageEntity(imageId, remoteFileName, fileVersionOf(imageId), size))

    @Transaction
    override suspend fun applyMetadata(imageId: Long, metadata: ParsedMetadata, now: Long): Boolean {
        val changed = updateMetadataIfDifferent(
            id = imageId,
            title = metadata.title,
            description = metadata.description,
            captionPlace = metadata.captionPlace,
            keywords = metadata.keywords,
            category = metadata.category,
            state = ImageState.of(metadata),
            generatedAt = now.takeIf { metadata.generated },
        ) > 0
        markInCsv(imageId)
        return changed
    }

    override suspend fun adoptEmbedded(imageId: Long, metadata: ParsedMetadata, now: Long) = adoptEmbeddedInto(
        id = imageId,
        title = metadata.title,
        description = metadata.description,
        captionPlace = metadata.captionPlace,
        keywords = metadata.keywords,
        category = metadata.category,
        state = ImageState.of(metadata),
        generatedAt = now.takeIf { metadata.generated },
    )

    @Query("UPDATE sync_folders SET csvSyncedVersion = csvVersion WHERE folderId = :folderId")
    abstract override suspend fun csvFound(folderId: Long)

    @Query("SELECT path FROM remote_deletions")
    protected abstract suspend fun deletionPaths(): List<String>

    @Query("SELECT COUNT(*) FROM folders WHERE name = :name COLLATE NOCASE")
    protected abstract suspend fun countWithName(name: String): Int

    @Query("SELECT COALESCE(MIN(position), 0) - 1 FROM folders")
    protected abstract suspend fun topPosition(): Int

    @Query("SELECT fileVersion FROM images WHERE id = :imageId")
    protected abstract suspend fun fileVersionOf(imageId: Long): Long

    @Query("SELECT csvVersion FROM sync_images WHERE imageId = :imageId")
    protected abstract suspend fun csvVersionOf(imageId: Long): Long?

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
            capturedOn = :capturedOn, mediaStoreUri = :mediaStoreUri, mediaKind = :mediaKind, durationMs = :durationMs,
            fileVersion = fileVersion + 1,
            metadataVersion = metadataVersion + (capturedOn IS NOT :capturedOn),
            albumState = 'PRESENT', trashExpiresAt = NULL, embeddedChecked = 1
        WHERE id = :id
        """
    )
    protected abstract suspend fun updatePulledFile(
        id: Long,
        sha256: String,
        widthPx: Int,
        heightPx: Int,
        byteSize: Long,
        capturedOn: String?,
        mediaStoreUri: String,
        mediaKind: MediaKind,
        durationMs: Long?,
    )

    // Leaves an identical row alone, NULL and '' alike, so pulling again is not reported as an update.
    @Query(
        """
        UPDATE images
        SET title = :title, description = :description, captionPlace = :captionPlace,
            keywords = :keywords, category = :category, state = :state, generatedAt = :generatedAt,
            generationError = NULL, metadataVersion = metadataVersion + 1
        WHERE id = :id AND state != 'GENERATING' AND NOT (IFNULL(title, '') = IFNULL(:title, '')
            AND IFNULL(description, '') = IFNULL(:description, '') AND IFNULL(captionPlace, '') = :captionPlace
            AND keywords IS :keywords AND IFNULL(category, '') = IFNULL(:category, ''))
        """
    )
    protected abstract suspend fun updateMetadataIfDifferent(
        id: Long,
        title: String?,
        description: String?,
        captionPlace: String,
        keywords: List<String>,
        category: String?,
        state: ImageState,
        generatedAt: Long?,
    ): Int

    @Query(ADOPT_EMBEDDED)
    protected abstract suspend fun adoptEmbeddedInto(
        id: Long,
        title: String?,
        description: String?,
        captionPlace: String,
        keywords: List<String>,
        category: String?,
        state: ImageState,
        generatedAt: Long?,
    )

    @Query("UPDATE sync_images SET csvVersion = (SELECT metadataVersion FROM images WHERE id = :imageId) WHERE imageId = :imageId")
    protected abstract suspend fun markInCsv(imageId: Long)
}
