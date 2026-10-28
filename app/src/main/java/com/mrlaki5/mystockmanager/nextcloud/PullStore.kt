package com.mrlaki5.mystockmanager.nextcloud

import com.mrlaki5.mystockmanager.metadata.EmbeddedMetadata
import com.mrlaki5.mystockmanager.metadata.model.ParsedMetadata
import java.io.File

data class LocalFolder(val id: Long, val name: String, val location: String?, val remoteName: String?)

data class LocalImage(
    val id: Long,
    val displayName: String,
    val mediaStoreUri: String?,
    val fileVersion: Long,
    val capturedOn: String?,
    val title: String?,
    val description: String?,
    val captionPlace: String?,
    val keywords: List<String>,
    val category: String?,
    val remoteFileName: String?,
    val syncedVersion: Long?,
    val remoteSize: Long?,
)

/** A downloaded file; without a CSV row, [embeddedMetadata] only fills an image that has no metadata yet. */
data class PulledImage(
    val remoteFileName: String,
    val mediaStoreUri: String,
    val sha256: String,
    val widthPx: Int,
    val heightPx: Int,
    val byteSize: Long,
    val capturedOn: String?,
    val csvMetadata: ParsedMetadata?,
    val embeddedMetadata: ParsedMetadata?,
)

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

    /** Applies the cloud CSV's row, recording it as already in the CSV; false when nothing changed. */
    suspend fun applyMetadata(imageId: Long, metadata: ParsedMetadata, now: Long): Boolean

    /** Fills an image that has no metadata from its file's; it is not in the CSV yet, so sync writes it there. */
    suspend fun adoptEmbedded(imageId: Long, metadata: ParsedMetadata, now: Long)

    /** The folder's CSV is on the cloud, so a later deletion rewrites it. */
    suspend fun csvFound(folderId: Long)
}

data class InspectedImage(
    val widthPx: Int,
    val heightPx: Int,
    val capturedOn: String?,
    val embedded: EmbeddedMetadata?,
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
