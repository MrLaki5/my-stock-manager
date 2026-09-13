package com.mrlaki5.mystockmanager.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Where an image sits in the pipeline. Each stage is entered by an explicit user
 * action, never automatically.
 */
enum class ImageState {
    /** Copied into app storage but not yet assigned to an event. */
    IMPORTED,

    /** Assigned to an event, ready to be sent for generation. */
    FILED,

    /** A generation worker is running for this image. */
    GENERATING,

    /** Metadata generated, embedded into the export copy, and read back verified. */
    GENERATED,

    /** Generation failed. Retryable without blocking the rest of the batch. */
    GENERATION_FAILED,
}

@Entity(
    tableName = "images",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            // Deleting an event returns its images to the inbox rather than destroying
            // files the user imported.
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("folderId"), Index("sha256")],
)
data class ImageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderId: Long?,
    val displayName: String,
    /** Content hash of the imported bytes, used to skip re-importing the same photo. */
    val sha256: String,
    val widthPx: Int,
    val heightPx: Int,
    val byteSize: Long,
    val importedAt: Long,
    val state: ImageState,
    val title: String? = null,
    val description: String? = null,
    /** Relevance-ordered; the order is meaningful to both agencies. */
    val keywords: List<String> = emptyList(),
    val category: String? = null,
    val generationError: String? = null,
    val generatedAt: Long? = null,
    val model: String? = null,
    /** MediaStore row for the published copy in Pictures/StockReady/<event>/, if any. */
    val mediaStoreUri: String? = null,
    val exportedAt: Long? = null,
)
