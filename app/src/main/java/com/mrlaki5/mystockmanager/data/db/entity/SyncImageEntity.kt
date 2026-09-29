package com.mrlaki5.mystockmanager.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** Written before the upload starts, so a delete that races it still knows the remote file. */
@Entity(
    tableName = "sync_images",
    foreignKeys = [
        ForeignKey(
            entity = ImageEntity::class,
            parentColumns = ["id"],
            childColumns = ["imageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SyncImageEntity(
    @PrimaryKey val imageId: Long,
    val remoteFileName: String,
    /** The [ImageEntity.fileVersion] last uploaded; null means an upload is owed. */
    val syncedVersion: Long? = null,
    /** Null until the app has uploaded this file itself at least once. */
    val remoteSize: Long? = null,
    /** The version NextCloud refused, so the same bytes are not retried on every run. */
    val failedVersion: Long? = null,
    val error: String? = null,
)
