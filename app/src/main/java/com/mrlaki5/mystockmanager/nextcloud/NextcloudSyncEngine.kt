package com.mrlaki5.mystockmanager.nextcloud

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SyncOutcome {
    data object Done : SyncOutcome
    data class Retry(val message: String) : SyncOutcome
    data class Failed(val message: String) : SyncOutcome
}

fun interface RemoteDriveFactory {
    fun create(account: NextcloudAccount): RemoteDrive
}

/** Album files, addressed by their MediaStore URI string. */
interface LocalFiles {
    fun sizeOf(uri: String): Long?
    fun open(uri: String): InputStream
}

/** Commits each step before picking the next, so a run stopped anywhere resumes without redoing work. */
@Singleton
class NextcloudSyncEngine @Inject constructor(
    private val store: SyncStore,
    private val flags: SyncFlags,
    private val drives: RemoteDriveFactory,
    private val files: LocalFiles,
) {
    // One run at a time, even when a replaced worker has not finished cancelling yet.
    private val mutex = Mutex()

    suspend fun run(): SyncOutcome = mutex.withLock {
        val account = flags.account ?: return SyncOutcome.Failed("Set up your NextCloud account in Settings.")
        if (flags.authRejected) return SyncOutcome.Failed(AUTH_REJECTED)
        Run(drives.create(account)).execute()
    }

    /** Runs [block] with no sync in flight, for changes such as a reset that a run must not interleave with. */
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }

    private inner class Run(private val drive: RemoteDrive) {
        private var rootReady = false
        private var missingStreak = 0

        suspend fun execute(): SyncOutcome {
            if (flags.fullCheckRequested) {
                fullCheck()?.let { return it }
                flags.fullCheckRequested = false
                flags.fullCheckDone(System.currentTimeMillis())
            }
            repeat(MAX_STEPS) {
                val action = store.nextAction() ?: return SyncOutcome.Done
                if (!rootReady) {
                    drive.ensureRoot().stopOrNull()?.let { return it }
                    rootReady = true
                }
                perform(action)?.let { return it }
            }
            return SyncOutcome.Retry("Sync paused after $MAX_STEPS steps and will continue.")
        }

        /** Null means carry on with the next step. */
        private suspend fun perform(action: SyncAction): SyncOutcome? = when (action) {
            is SyncAction.DeleteRemote -> delete(action)
            is SyncAction.CreateFolder -> createFolder(action)
            is SyncAction.MoveFolder -> moveFolder(action)
            is SyncAction.Upload -> upload(action)
        }

        private suspend fun delete(action: SyncAction.DeleteRemote): SyncOutcome? =
            when (val result = drive.delete(action.path.split('/'))) {
                is DavResult.Ok, is DavResult.ParentMissing -> done { store.dropDeletion(action.path) }
                // A path NextCloud will never let us delete must not block everything queued behind it.
                is DavResult.ItemRejected -> done { store.dropDeletion(action.path) }
                is DavResult.Failure -> result.toOutcome()
            }

        private suspend fun createFolder(action: SyncAction.CreateFolder): SyncOutcome? {
            val base = RemoteNames.folderBase(action.eventName)
            for (name in RemoteNames.candidates(base, action.taken).take(MAX_CANDIDATES)) {
                when (val result = drive.mkcol(listOf(name))) {
                    is DavResult.Ok -> {
                        // An existing folder of that name is adopted, so a crash before the commit never forks "A (2)".
                        if (result.value == MkcolResult.EXISTS) {
                            when (val existing = drive.stat(listOf(name))) {
                                is DavResult.Ok -> if (existing.value?.isCollection != true) continue
                                is DavResult.Failure -> return existing.toOutcome()
                            }
                        }
                        return done { store.commitFolder(action.folderId, name, action.eventName, movedFrom = null) }
                    }
                    is DavResult.ParentMissing -> return rootMissing()
                    is DavResult.Failure -> return result.toOutcome("Could not create a folder for \"${action.eventName}\"")
                }
            }
            return SyncOutcome.Failed("No free folder name on NextCloud for \"${action.eventName}\".")
        }

        private suspend fun moveFolder(action: SyncAction.MoveFolder): SyncOutcome? {
            val base = RemoteNames.folderBase(action.eventName)
            if (RemoteNames.matchesBase(action.from, base)) {
                return done { store.commitFolder(action.folderId, action.from, action.eventName, movedFrom = null) }
            }
            for (name in RemoteNames.candidates(base, action.taken).take(MAX_CANDIDATES)) {
                when (val result = drive.move(listOf(action.from), listOf(name))) {
                    is DavResult.Ok -> when (result.value) {
                        MoveResult.MOVED -> return done {
                            store.commitFolder(action.folderId, name, action.eventName, movedFrom = action.from)
                        }
                        MoveResult.DESTINATION_EXISTS -> continue
                        // Gone from the cloud: forgetting it recreates the folder and re-uploads its images.
                        MoveResult.SOURCE_MISSING -> return done { store.forgetFolder(action.folderId) }
                    }
                    is DavResult.ParentMissing -> return done { store.forgetFolder(action.folderId) }
                    is DavResult.Failure -> return result.toOutcome("Could not rename the folder for \"${action.eventName}\"")
                }
            }
            return SyncOutcome.Failed("No free folder name on NextCloud for \"${action.eventName}\".")
        }

        private suspend fun upload(action: SyncAction.Upload): SyncOutcome? {
            val size = files.sizeOf(action.mediaStoreUri)
                ?: return done { store.markFailed(action.imageId, action.version, "The image is missing from the album.") }
            val path = listOf(action.folder, action.fileName)

            if (action.firstUpload) {
                when (val existing = drive.stat(path)) {
                    is DavResult.Ok -> existing.value?.let { entry ->
                        // Same name and size is this image from an earlier install; anything else is left alone.
                        return if (!entry.isCollection && entry.size == size) {
                            done { store.commitUpload(action.imageId, action.version, size) }
                        } else {
                            claimAnotherName(action)
                        }
                    }
                    is DavResult.ParentMissing -> return done { store.forgetFolder(action.folderId) }
                    is DavResult.Failure -> return existing.toOutcome()
                }
            }

            val result = drive.put(path, size, createOnly = action.firstUpload) { files.open(action.mediaStoreUri) }
            return when (result) {
                is DavResult.Ok -> when (result.value) {
                    PutResult.WRITTEN -> done { store.commitUpload(action.imageId, action.version, size) }
                    // Appeared since the stat above; a second clash under the suffixed name marks it failed.
                    PutResult.ALREADY_EXISTS -> claimAnotherName(action)
                }
                is DavResult.ItemRejected -> done { store.markFailed(action.imageId, action.version, result.message) }
                is DavResult.ParentMissing -> done { store.forgetFolder(action.folderId) }
                is DavResult.Failure -> result.toOutcome()
            }
        }

        private suspend fun claimAnotherName(action: SyncAction.Upload): SyncOutcome? {
            val suffixed = RemoteNames.withIdSuffix(RemoteNames.fileBase(action.displayName), action.imageId)
            return done {
                if (action.fileName.equals(suffixed, ignoreCase = true)) {
                    store.markFailed(action.imageId, action.version, "A different ${action.fileName} is already on NextCloud.")
                } else {
                    store.renameUpload(action.imageId, suffixed)
                }
            }
        }

        private suspend fun fullCheck(): SyncOutcome? {
            store.clearFailures()
            for (folder in store.syncedFolders()) {
                when (val listing = drive.list(listOf(folder.remoteName))) {
                    is DavResult.Ok -> {
                        val entries = listing.value
                        if (entries == null) {
                            store.forgetFolder(folder.folderId)
                            continue
                        }
                        val sizes = entries.filterNot { it.isCollection }.associate { it.name to it.size }
                        val stale = folder.images
                            .filter { it.remoteSize != null && sizes[it.remoteFileName] != it.remoteSize }
                            .map { it.imageId }
                        store.markDirty(stale)
                    }
                    is DavResult.ParentMissing -> store.forgetFolder(folder.folderId)
                    is DavResult.Failure -> return listing.toOutcome()
                }
            }
            return null
        }

        // Re-ensures the root on the next step, but gives up if NextCloud keeps saying it is missing.
        private fun rootMissing(): SyncOutcome? {
            rootReady = false
            return if (++missingStreak > 2) SyncOutcome.Failed("NextCloud keeps reporting the sync folder as missing.") else null
        }

        private suspend fun done(commit: suspend () -> Unit): SyncOutcome? {
            commit()
            missingStreak = 0
            return null
        }

        private fun DavResult<*>.stopOrNull(): SyncOutcome? = (this as? DavResult.Failure)?.toOutcome()

        private fun DavResult.Failure.toOutcome(context: String? = null): SyncOutcome {
            val text = if (context == null) message else "$context: $message"
            return when (this) {
                is DavResult.Transient -> SyncOutcome.Retry(text)
                is DavResult.AuthRejected -> {
                    // NextCloud throttles repeated failed logins, so stop until the credentials are saved again.
                    flags.authRejected = true
                    SyncOutcome.Failed(AUTH_REJECTED)
                }
                is DavResult.Fatal, is DavResult.ItemRejected, is DavResult.ParentMissing -> SyncOutcome.Failed(text)
            }
        }
    }

    companion object {
        const val AUTH_REJECTED = "NextCloud rejected the username or app password. Save them again in Settings."
        private const val MAX_STEPS = 10_000
        private const val MAX_CANDIDATES = 50
    }
}
