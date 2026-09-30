package com.mrlaki5.mystockmanager.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An "event" — a shoot, a day, a theme. Deliberately flat: events are not nested.
 * Names are unique so the same event cannot be created twice by accident, and because
 * the name becomes a MediaStore album directory on export.
 */
@Entity(
    tableName = "folders",
    indices = [Index(value = ["name"], unique = true)],
)
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    /**
     * The last generation hint for this event, pre-filled next time: context the vision model
     * cannot see, such as the place or what the subject is. Each image keeps its own caption
     * place, so this is only a default.
     */
    @ColumnInfo(name = "location") val hint: String? = null,
    /** Place in the user-arranged event list; lowest is shown first. */
    @ColumnInfo(defaultValue = "0") val position: Int = 0,
)
