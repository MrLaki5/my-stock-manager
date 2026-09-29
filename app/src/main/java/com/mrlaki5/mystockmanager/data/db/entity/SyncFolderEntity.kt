package com.mrlaki5.mystockmanager.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** Where an event's folder lives on NextCloud. No row means it has not been created there yet. */
@Entity(
    tableName = "sync_folders",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SyncFolderEntity(
    @PrimaryKey val folderId: Long,
    /** Can differ from the event name by a collision suffix, so it is stored, never derived. */
    val remoteName: String,
    /** The event name at the last create or move; a mismatch means a rename is pending. */
    val localName: String,
)
