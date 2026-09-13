package com.mrlaki5.mystockmanager.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mrlaki5.mystockmanager.data.db.entity.FolderEntity
import kotlinx.coroutines.flow.Flow

/**
 * An event plus the counts the list screen needs, computed in SQL rather than in Kotlin.
 *
 * Note the column aliases: `generated` alone is a reserved word in Room's SQL grammar
 * (SQLite's GENERATED ALWAYS AS), and using it produces a baffling parse error pointing
 * at the next token instead.
 */
data class FolderSummary(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val total: Int,
    val generatedCount: Int,
    val failedCount: Int,
    val generatingCount: Int,
)

@Dao
interface FolderDao {

    @Query(
        "SELECT f.id AS id, f.name AS name, f.createdAt AS createdAt, " +
            "COUNT(i.id) AS total, " +
            "COALESCE(SUM(CASE WHEN i.state = 'GENERATED' THEN 1 ELSE 0 END), 0) AS generatedCount, " +
            "COALESCE(SUM(CASE WHEN i.state = 'GENERATION_FAILED' THEN 1 ELSE 0 END), 0) AS failedCount, " +
            "COALESCE(SUM(CASE WHEN i.state = 'GENERATING' THEN 1 ELSE 0 END), 0) AS generatingCount " +
            "FROM folders f LEFT JOIN images i ON i.folderId = f.id " +
            "GROUP BY f.id, f.name, f.createdAt ORDER BY f.createdAt DESC"
    )
    fun observeSummaries(): Flow<List<FolderSummary>>

    @Query("SELECT * FROM folders WHERE id = :id")
    fun observeById(id: Long): Flow<FolderEntity?>

    @Insert
    suspend fun insert(folder: FolderEntity): Long

    @Query("UPDATE folders SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long)

    @Query("UPDATE folders SET location = :location, updatedAt = :now WHERE id = :id")
    suspend fun setLocation(id: Long, location: String?, now: Long)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM folders WHERE name = :name COLLATE NOCASE")
    suspend fun countWithName(name: String): Int
}
