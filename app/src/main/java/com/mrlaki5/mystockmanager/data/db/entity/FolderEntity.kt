package com.mrlaki5.mystockmanager.data.db.entity

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
     * Optional place the shoot happened. The vision model cannot know this, so when it
     * is supplied it is handed over as ground truth for titles, descriptions and
     * location keywords.
     */
    val location: String? = null,
)
