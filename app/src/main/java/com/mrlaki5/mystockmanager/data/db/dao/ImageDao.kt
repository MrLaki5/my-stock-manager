package com.mrlaki5.mystockmanager.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mrlaki5.mystockmanager.data.db.entity.ImageEntity
import com.mrlaki5.mystockmanager.data.db.entity.ImageState
import kotlinx.coroutines.flow.Flow

@Dao
interface ImageDao {

    @Query("SELECT * FROM images WHERE folderId = :folderId ORDER BY importedAt DESC")
    fun observeByFolder(folderId: Long): Flow<List<ImageEntity>>

    @Query("SELECT * FROM images WHERE id = :id")
    suspend fun getById(id: Long): ImageEntity?

    @Query("SELECT * FROM images WHERE id = :id")
    fun observeById(id: Long): Flow<ImageEntity?>

    @Insert
    suspend fun insert(image: ImageEntity): Long

    @Query("SELECT COUNT(*) FROM images WHERE folderId = :folderId AND sha256 = :sha256")
    suspend fun countDuplicate(folderId: Long, sha256: String): Int

    @Query("UPDATE images SET state = :state WHERE id = :id")
    suspend fun updateState(id: Long, state: ImageState)

    @Query("UPDATE images SET state = :state, generationError = :error WHERE id = :id")
    suspend fun markFailed(id: Long, state: ImageState, error: String?)

    @Query(
        """
        UPDATE images
        SET title = :title, description = :description, keywords = :keywords,
            category = :category, state = :state, generatedAt = :generatedAt,
            model = :model, captionPlace = :captionPlace, generationError = NULL,
            fileVersion = fileVersion + 1
        WHERE id = :id
        """
    )
    suspend fun saveGenerated(
        id: Long,
        title: String,
        description: String,
        keywords: List<String>,
        category: String?,
        state: ImageState,
        generatedAt: Long,
        model: String?,
        captionPlace: String,
    )

    /**
     * A hand edit of existing metadata. Unlike [saveGenerated] this leaves state, model and
     * generatedAt alone: editing a keyword does not change which model produced it or when.
     */
    @Query(
        """
        UPDATE images
        SET title = :title, description = :description, keywords = :keywords,
            category = :category, fileVersion = fileVersion + 1
        WHERE id = :id
        """
    )
    suspend fun updateMetadata(
        id: Long,
        title: String,
        description: String,
        keywords: List<String>,
        category: String?,
    )

    /**
     * A first hand write on an image that never got metadata. It now has metadata like a
     * generated one, so it leaves the Generate queue; model stays null since none produced it.
     */
    @Query(
        """
        UPDATE images
        SET title = :title, description = :description, keywords = :keywords,
            category = :category, state = :state, generatedAt = :writtenAt,
            captionPlace = COALESCE(captionPlace, ''), generationError = NULL,
            fileVersion = fileVersion + 1
        WHERE id = :id
        """
    )
    suspend fun saveHandWritten(
        id: Long,
        title: String,
        description: String,
        keywords: List<String>,
        category: String?,
        state: ImageState,
        writtenAt: Long,
    )

    /** Puts an image back where it was before generation: generated if it already had metadata, else filed. */
    @Query(
        """
        UPDATE images
        SET state = CASE WHEN title IS NULL THEN 'FILED' ELSE 'GENERATED' END, generationError = :error
        WHERE id = :id
        """
    )
    suspend fun restoreAfterFailedGeneration(id: Long, error: String)

    @Query("UPDATE images SET state = CASE WHEN title IS NULL THEN 'FILED' ELSE 'GENERATED' END WHERE state = 'GENERATING'")
    suspend fun restoreStuckGenerating()

    @Query("SELECT * FROM images WHERE folderId = :folderId AND state = :state")
    suspend fun getByFolderAndState(folderId: Long, state: ImageState): List<ImageEntity>

    @Query("UPDATE images SET mediaStoreUri = :uri, exportedAt = :at WHERE id = :id")
    suspend fun markExported(id: Long, uri: String?, at: Long?)

    @Query("SELECT * FROM images WHERE mediaStoreUri IS NULL")
    suspend fun getWithoutMediaStoreUri(): List<ImageEntity>

    @Query("SELECT * FROM images WHERE capturedOn IS NULL AND mediaStoreUri IS NOT NULL")
    suspend fun getWithoutCaptureDate(): List<ImageEntity>

    @Query("UPDATE images SET capturedOn = :capturedOn WHERE id = :id")
    suspend fun setCapturedOn(id: Long, capturedOn: String?)

    @Query("SELECT * FROM images WHERE captionPlace IS NULL AND description IS NOT NULL AND mediaStoreUri IS NOT NULL")
    suspend fun getWithoutCaptionPlace(): List<ImageEntity>

    @Query("UPDATE images SET captionPlace = :place WHERE id = :id")
    suspend fun setCaptionPlace(id: Long, place: String)
}
