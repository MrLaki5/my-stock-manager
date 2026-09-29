package com.mrlaki5.mystockmanager.nextcloud

enum class CloudMark { SYNCED, PENDING, FAILED }

data class ImageCloudRow(
    val imageId: Long,
    val fileVersion: Long,
    val syncedVersion: Long?,
    val failedVersion: Long?,
    val error: String?,
)

data class CloudStatus(val mark: CloudMark, val error: String?)

/** Null means no mark: the current version is not on the cloud and, with sync off, is not on its way. */
fun ImageCloudRow.mark(syncEnabled: Boolean): CloudMark? = when {
    syncedVersion == fileVersion -> CloudMark.SYNCED
    failedVersion == fileVersion -> CloudMark.FAILED
    syncEnabled -> CloudMark.PENDING
    else -> null
}

fun ImageCloudRow.status(syncEnabled: Boolean): CloudStatus? =
    mark(syncEnabled)?.let { CloudStatus(it, error.takeIf { _ -> it == CloudMark.FAILED }) }
