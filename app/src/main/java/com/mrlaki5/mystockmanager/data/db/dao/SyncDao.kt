package com.mrlaki5.mystockmanager.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.mrlaki5.mystockmanager.data.db.entity.RemoteDeletionEntity
import com.mrlaki5.mystockmanager.data.db.entity.SyncFolderEntity
import com.mrlaki5.mystockmanager.data.db.entity.SyncImageEntity
import com.mrlaki5.mystockmanager.nextcloud.ImageCloudRow
import com.mrlaki5.mystockmanager.nextcloud.RemoteNames
import com.mrlaki5.mystockmanager.nextcloud.SyncAction
import com.mrlaki5.mystockmanager.nextcloud.SyncStore
import com.mrlaki5.mystockmanager.nextcloud.SyncedFolder
import com.mrlaki5.mystockmanager.nextcloud.SyncedImage
import kotlinx.coroutines.flow.Flow

data class SyncCounts(val pending: Int, val failed: Int)

data class FolderToSync(val id: Long, val name: String, val remoteName: String?)

data class ImageToSync(
    val id: Long,
    val folderId: Long,
    val displayName: String,
    val mediaStoreUri: String,
    val fileVersion: Long,
    val folderRemoteName: String,
    val remoteFileName: String?,
    val remoteSize: Long?,
)

data class SyncedImageRow(val imageId: Long, val folderId: Long, val remoteFileName: String, val remoteSize: Long?)

@Dao
abstract class SyncDao : SyncStore {

    @Query(COUNTS)
    abstract fun observeCounts(): Flow<SyncCounts>

    @Query("$CLOUD_ROWS WHERE i.folderId = :folderId")
    abstract fun observeCloudRows(folderId: Long): Flow<List<ImageCloudRow>>

    @Query("$CLOUD_ROWS WHERE i.id = :imageId")
    abstract fun observeCloudRow(imageId: Long): Flow<ImageCloudRow?>

    /** Tombstoning inside the delete's transaction means no upload can slip in between the two. */
    @Transaction
    open suspend fun deleteImage(imageId: Long, tombstone: Boolean) {
        if (tombstone) remotePathOf(imageId)?.let { insertDeletion(RemoteDeletionEntity(it, now())) }
        deleteImageRow(imageId)
    }

    @Transaction
    open suspend fun deleteEvent(folderId: Long, tombstone: Boolean) {
        if (tombstone) {
            remoteNameOf(folderId)?.let { folder ->
                // Deleting the folder removes its files too, so their own tombstones are redundant.
                dropDeletionsUnder(folder)
                insertDeletion(RemoteDeletionEntity(folder, now()))
            }
        }
        deleteImagesOf(folderId)
        deleteFolderRow(folderId)
    }

    @Transaction
    open suspend fun resetAll() {
        clearSyncImages()
        clearSyncFolders()
        clearDeletions()
    }

    @Transaction
    override suspend fun nextAction(): SyncAction? {
        firstDeletion()?.let { return SyncAction.DeleteRemote(it) }

        firstFolderToSync()?.let { folder ->
            val taken = remoteNamesExcept(folder.id)
            return when (val from = folder.remoteName) {
                null -> SyncAction.CreateFolder(folder.id, folder.name, taken)
                else -> SyncAction.MoveFolder(folder.id, folder.name, from, taken)
            }
        }

        val image = firstDirtyImage() ?: return null
        val fileName = image.remoteFileName
            ?: RemoteNames.fileName(image.displayName, image.id, fileNamesIn(image.folderId))
                .also { insertSyncImage(SyncImageEntity(imageId = image.id, remoteFileName = it)) }
        return SyncAction.Upload(
            imageId = image.id,
            folderId = image.folderId,
            folder = image.folderRemoteName,
            fileName = fileName,
            displayName = image.displayName,
            version = image.fileVersion,
            mediaStoreUri = image.mediaStoreUri,
            firstUpload = image.remoteSize == null,
        )
    }

    @Query("DELETE FROM remote_deletions WHERE path = :path")
    abstract override suspend fun dropDeletion(path: String)

    @Transaction
    override suspend fun commitFolder(folderId: Long, remoteName: String, localName: String, movedFrom: String?) {
        // Deletions queued under the old name while the move was in flight now live under the new one.
        if (movedFrom != null) retargetDeletions(movedFrom, remoteName)
        if (folderExists(folderId)) {
            upsertSyncFolder(SyncFolderEntity(folderId, remoteName, localName))
        } else {
            insertDeletion(RemoteDeletionEntity(remoteName, now()))
        }
    }

    @Transaction
    override suspend fun forgetFolder(folderId: Long) {
        deleteSyncImagesOf(folderId)
        deleteSyncFolder(folderId)
    }

    @Query(
        """
        UPDATE sync_images SET syncedVersion = :version, remoteSize = :size, failedVersion = NULL,
            error = NULL
        WHERE imageId = :imageId
        """
    )
    abstract override suspend fun commitUpload(imageId: Long, version: Long, size: Long)

    @Query("UPDATE sync_images SET remoteFileName = :fileName WHERE imageId = :imageId")
    abstract override suspend fun renameUpload(imageId: Long, fileName: String)

    @Query("UPDATE sync_images SET failedVersion = :version, error = :error WHERE imageId = :imageId")
    abstract override suspend fun markFailed(imageId: Long, version: Long, error: String)

    @Transaction
    override suspend fun syncedFolders(): List<SyncedFolder> {
        val images = syncedImageRows().groupBy { it.folderId }
        return allSyncFolders().map { folder ->
            SyncedFolder(
                folderId = folder.folderId,
                remoteName = folder.remoteName,
                images = images[folder.folderId].orEmpty()
                    .map { SyncedImage(it.imageId, it.remoteFileName, it.remoteSize) },
            )
        }
    }

    // Chunked because API 29's SQLite caps a statement at 999 bound variables.
    override suspend fun markDirty(imageIds: List<Long>) {
        imageIds.chunked(500).forEach { markDirtyChunk(it) }
    }

    @Query("UPDATE sync_images SET failedVersion = NULL, error = NULL")
    abstract override suspend fun clearFailures()

    @Query("SELECT path FROM remote_deletions ORDER BY createdAt, path LIMIT 1")
    protected abstract suspend fun firstDeletion(): String?

    // Moves sort before creates so a renamed event frees its old name before a new event claims it.
    @Query(
        """
        SELECT f.id AS id, f.name AS name, sf.remoteName AS remoteName
        FROM folders f LEFT JOIN sync_folders sf ON sf.folderId = f.id
        WHERE sf.folderId IS NULL OR sf.localName != f.name
        ORDER BY sf.folderId IS NULL, f.id
        LIMIT 1
        """
    )
    protected abstract suspend fun firstFolderToSync(): FolderToSync?

    @Query("SELECT remoteName FROM sync_folders WHERE folderId != :folderId")
    protected abstract suspend fun remoteNamesExcept(folderId: Long): List<String>

    @Query(
        """
        SELECT i.id AS id, i.folderId AS folderId, i.displayName AS displayName,
            i.mediaStoreUri AS mediaStoreUri, i.fileVersion AS fileVersion,
            sf.remoteName AS folderRemoteName, s.remoteFileName AS remoteFileName,
            s.remoteSize AS remoteSize
        FROM images i
        JOIN folders f ON f.id = i.folderId
        JOIN sync_folders sf ON sf.folderId = f.id AND sf.localName = f.name
        LEFT JOIN sync_images s ON s.imageId = i.id
        WHERE i.mediaStoreUri IS NOT NULL AND $DIRTY
        ORDER BY i.id
        LIMIT 1
        """
    )
    protected abstract suspend fun firstDirtyImage(): ImageToSync?

    @Query(
        """
        SELECT s.remoteFileName FROM sync_images s JOIN images i ON i.id = s.imageId
        WHERE i.folderId = :folderId
        """
    )
    protected abstract suspend fun fileNamesIn(folderId: Long): List<String>

    @Insert
    protected abstract suspend fun insertSyncImage(entity: SyncImageEntity)

    @Upsert
    protected abstract suspend fun upsertSyncFolder(entity: SyncFolderEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertDeletion(entity: RemoteDeletionEntity)

    @Query(
        """
        SELECT sf.remoteName || '/' || s.remoteFileName
        FROM sync_images s
        JOIN images i ON i.id = s.imageId
        JOIN sync_folders sf ON sf.folderId = i.folderId
        WHERE s.imageId = :imageId
        """
    )
    protected abstract suspend fun remotePathOf(imageId: Long): String?

    @Query("SELECT remoteName FROM sync_folders WHERE folderId = :folderId")
    protected abstract suspend fun remoteNameOf(folderId: Long): String?

    // substr rather than LIKE, because event names can contain % and _.
    @Query("DELETE FROM remote_deletions WHERE substr(path, 1, length(:folder) + 1) = :folder || '/'")
    protected abstract suspend fun dropDeletionsUnder(folder: String)

    @Query(
        """
        UPDATE OR REPLACE remote_deletions SET path = :to || substr(path, length(:from) + 1)
        WHERE path = :from OR substr(path, 1, length(:from) + 1) = :from || '/'
        """
    )
    protected abstract suspend fun retargetDeletions(from: String, to: String)

    @Query("SELECT EXISTS(SELECT 1 FROM folders WHERE id = :folderId)")
    protected abstract suspend fun folderExists(folderId: Long): Boolean

    @Query("SELECT * FROM sync_folders")
    protected abstract suspend fun allSyncFolders(): List<SyncFolderEntity>

    @Query(
        """
        SELECT s.imageId AS imageId, i.folderId AS folderId, s.remoteFileName AS remoteFileName,
            s.remoteSize AS remoteSize
        FROM sync_images s JOIN images i ON i.id = s.imageId
        WHERE i.folderId IS NOT NULL
        """
    )
    protected abstract suspend fun syncedImageRows(): List<SyncedImageRow>

    @Query("UPDATE sync_images SET syncedVersion = NULL WHERE imageId IN (:imageIds)")
    protected abstract suspend fun markDirtyChunk(imageIds: List<Long>)

    @Query("DELETE FROM sync_images WHERE imageId IN (SELECT id FROM images WHERE folderId = :folderId)")
    protected abstract suspend fun deleteSyncImagesOf(folderId: Long)

    @Query("DELETE FROM sync_folders WHERE folderId = :folderId")
    protected abstract suspend fun deleteSyncFolder(folderId: Long)

    @Query("DELETE FROM images WHERE id = :imageId")
    protected abstract suspend fun deleteImageRow(imageId: Long)

    @Query("DELETE FROM images WHERE folderId = :folderId")
    protected abstract suspend fun deleteImagesOf(folderId: Long)

    @Query("DELETE FROM folders WHERE id = :folderId")
    protected abstract suspend fun deleteFolderRow(folderId: Long)

    @Query("DELETE FROM sync_images")
    protected abstract suspend fun clearSyncImages()

    @Query("DELETE FROM sync_folders")
    protected abstract suspend fun clearSyncFolders()

    @Query("DELETE FROM remote_deletions")
    protected abstract suspend fun clearDeletions()

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val CLOUD_ROWS =
            "SELECT i.id AS imageId, i.fileVersion AS fileVersion, s.syncedVersion AS syncedVersion, " +
                "s.failedVersion AS failedVersion, s.error AS error " +
                "FROM images i LEFT JOIN sync_images s ON s.imageId = i.id"

        const val DIRTY =
            "(s.imageId IS NULL OR s.syncedVersion IS NULL OR s.syncedVersion != i.fileVersion) " +
                "AND (s.failedVersion IS NULL OR s.failedVersion != i.fileVersion)"

        const val COUNTS =
            "SELECT " +
                "(SELECT COUNT(*) FROM remote_deletions) " +
                "+ (SELECT COUNT(*) FROM folders f LEFT JOIN sync_folders sf ON sf.folderId = f.id " +
                "WHERE sf.folderId IS NULL OR sf.localName != f.name) " +
                "+ (SELECT COUNT(*) FROM images i LEFT JOIN sync_images s ON s.imageId = i.id " +
                "WHERE i.folderId IS NOT NULL AND i.mediaStoreUri IS NOT NULL AND $DIRTY) AS pending, " +
                "(SELECT COUNT(*) FROM images i JOIN sync_images s ON s.imageId = i.id " +
                "WHERE s.failedVersion = i.fileVersion) AS failed"
    }
}
