package com.mrlaki5.mystockmanager.nextcloud

import java.io.File

data class LocalFolder(val id: Long, val name: String, val location: String?, val remoteName: String?)

data class LocalImage(
    val id: Long,
    val displayName: String,
    val mediaStoreUri: String?,
    val fileVersion: Long,
    val remoteFileName: String?,
    val syncedVersion: Long?,
    val remoteSize: Long?,
)

/** A downloaded file as the images table stores it; [description] is the caption body, without its lead. */
data class PulledImage(
    val remoteFileName: String,
    val mediaStoreUri: String,
    val sha256: String,
    val widthPx: Int,
    val heightPx: Int,
    val byteSize: Long,
    val capturedOn: String?,
    val title: String?,
    val description: String?,
    val keywords: List<String>,
    val category: String?,
) {
    val generated: Boolean get() = !title.isNullOrBlank() && !description.isNullOrBlank() && keywords.isNotEmpty()
}

/** The pull's view of the database. Everything it writes is recorded as already synced, so nothing is sent back up. */
interface PullStore {
    suspend fun pendingDeletions(): Set<String>

    suspend fun localFolders(): List<LocalFolder>

    suspend fun localImages(folderId: Long): List<LocalImage>

    /** Takes the first name no other event uses, since event names are unique regardless of case. */
    suspend fun createLinkedEvent(name: String, remoteName: String, now: Long): LocalFolder

    suspend fun linkFolder(folderId: Long, remoteName: String, localName: String)

    suspend fun setLocationIfMissing(folderId: Long, location: String)

    suspend fun insertPulled(folderId: Long, displayName: String, image: PulledImage, now: Long): Long

    suspend fun replacePulled(imageId: Long, image: PulledImage, now: Long)

    suspend fun linkImage(imageId: Long, remoteFileName: String, size: Long)
}

data class InspectedImage(
    val widthPx: Int,
    val heightPx: Int,
    val capturedOn: String?,
    val title: String?,
    val caption: String?,
    val keywords: List<String>,
    val category: String?,
)

/** The album side of a pull, narrowed so the pull can run against a fake. */
interface PullAlbum {
    fun newTempFile(): File

    /** Null when the file is not an image the app can use. */
    fun inspect(file: File): InspectedImage?

    fun publish(file: File, displayName: String, eventName: String): String

    /** False when the album entry is gone, so the caller publishes a fresh one instead. */
    fun overwrite(uri: String, file: File): Boolean

    fun sizeOf(uri: String): Long?

    fun dateTaken(uri: String): String?
}
