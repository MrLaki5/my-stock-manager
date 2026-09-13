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
            model = :model, generationError = NULL
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
    )

    @Query("DELETE FROM images WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * A hand edit. Unlike [saveGenerated] this leaves state, model and generatedAt alone:
     * the metadata came from the model originally and editing a keyword does not change
     * which model produced it or when.
     */
    @Query(
        """
        UPDATE images
        SET title = :title, description = :description, keywords = :keywords,
            category = :category
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

    @Query("UPDATE images SET state = :to WHERE state = :from")
    suspend fun resetState(from: ImageState, to: ImageState)

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
}
