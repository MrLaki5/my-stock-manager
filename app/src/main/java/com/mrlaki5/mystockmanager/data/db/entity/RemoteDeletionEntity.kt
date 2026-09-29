package com.mrlaki5.mystockmanager.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A deletion made while sync was on; [path] is `folder` or `folder/file` below the sync root. */
@Entity(tableName = "remote_deletions")
data class RemoteDeletionEntity(
    @PrimaryKey val path: String,
    val createdAt: Long,
)
