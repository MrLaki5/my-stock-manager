package com.mrlaki5.mystockmanager.nextcloud

import com.mrlaki5.mystockmanager.metadata.CsvRow

/** One step of a sync run; remote paths are relative to the sync root. */
sealed interface SyncAction {
    data class DeleteRemote(val path: String) : SyncAction

    data class CreateFolder(val folderId: Long, val eventName: String, val taken: List<String>) : SyncAction

    data class MoveFolder(
        val folderId: Long,
        val eventName: String,
        val from: String,
        val taken: List<String>,
    ) : SyncAction

    data class Upload(
        val imageId: Long,
        val folderId: Long,
        val folder: String,
        val fileName: String,
        val displayName: String,
        val version: Long,
        val mediaStoreUri: String,
        /** Until the app has written this file itself, an existing one there is not overwritten. */
        val firstUpload: Boolean,
    ) : SyncAction

    /** Rewrites the event's CSV; [version] is the folder's csvVersion it was built from. */
    data class WriteCsv(
        val folderId: Long,
        val folder: String,
        val version: Long,
        /** The app has written this folder's CSV before, so an empty one still replaces it. */
        val onCloud: Boolean,
        val images: List<CsvImage>,
    ) : SyncAction
}

/** An uploaded image as the CSV sees it; [row] is null while it has no metadata. */
data class CsvImage(val imageId: Long, val fileName: String, val metadataVersion: Long, val row: CsvRow?)

data class SyncedImage(val imageId: Long, val remoteFileName: String, val remoteSize: Long?)

data class SyncedFolder(
    val folderId: Long,
    val remoteName: String,
    val images: List<SyncedImage>,
    val csvOnCloud: Boolean,
)

/** The sync engine's view of the database, kept narrow so the engine can run against a fake. */
interface SyncStore {
    /** Deletions first, then folder moves and creates, then uploads, then CSVs; reserves the upload's file name. */
    suspend fun nextAction(): SyncAction?

    suspend fun dropDeletion(path: String)

    /** Tombstones the new path instead when the event was deleted while the request was in flight. */
    suspend fun commitFolder(folderId: Long, remoteName: String, localName: String, movedFrom: String?)

    /** Drops what is known about a folder that is gone from the cloud, so it is recreated and refilled. */
    suspend fun forgetFolder(folderId: Long)

    suspend fun commitUpload(imageId: Long, version: Long, size: Long)

    suspend fun renameUpload(imageId: Long, fileName: String)

    suspend fun markFailed(imageId: Long, version: Long, error: String)

    suspend fun syncedFolders(): List<SyncedFolder>

    suspend fun markDirty(imageIds: List<Long>)

    suspend fun clearFailures()

    /** [written] is false when the event had nothing to put in a file and none was there. */
    suspend fun commitCsv(folderId: Long, version: Long, images: List<CsvImage>, written: Boolean)

    /** The CSV is gone from the cloud, so it is written again from scratch. */
    suspend fun forgetCsv(folderId: Long)
}
