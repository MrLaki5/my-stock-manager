package com.mrlaki5.mystockmanager.nextcloud

import com.mrlaki5.mystockmanager.metadata.model.EditorialCaption
import kotlinx.coroutines.CancellationException
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class PullSummary(
    val newEvents: Int = 0,
    val added: Int = 0,
    val replaced: Int = 0,
    val unchanged: Int = 0,
    val skipped: Int = 0,
) {
    fun describe(): String {
        val parts = buildList {
            if (added > 0) {
                val into = if (newEvents > 0) " into $newEvents new event${plural(newEvents)}" else ""
                add("Added $added image${plural(added)}$into")
            }
            if (replaced > 0) add("replaced $replaced with the cloud's version")
            if (skipped > 0) add("skipped $skipped file${plural(skipped)} that could not be used")
        }
        if (added == 0 && replaced == 0) {
            return listOf("Already up to date with NextCloud").plus(parts).joinToString(" · ")
        }
        return parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
    }

    private fun plural(count: Int) = if (count == 1) "" else "s"
}

sealed interface PullOutcome {
    data class Done(val summary: PullSummary) : PullOutcome
    data class Retry(val message: String) : PullOutcome
    data class Failed(val message: String) : PullOutcome
}

sealed interface PullState {
    data object Idle : PullState
    data class Waiting(val retrying: Boolean) : PullState
    data class Running(val done: Int, val total: Int) : PullState
    data class Finished(val id: String, val summary: PullSummary) : PullState
    data class Failed(val id: String, val message: String) : PullState
}

/** Cloud → phone, only when asked: each root folder becomes an event, the cloud wins a clash, nothing local is deleted. */
@Singleton
class NextcloudPull @Inject constructor(
    private val store: PullStore,
    private val flags: SyncFlags,
    private val drives: RemoteDriveFactory,
    private val album: PullAlbum,
) {

    suspend fun run(onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }): PullOutcome {
        val account = flags.account ?: return PullOutcome.Failed("Set up your NextCloud account in Settings.")
        if (flags.authRejected) return PullOutcome.Failed(NextcloudSyncEngine.AUTH_REJECTED)
        val drive = drives.create(account)
        // Paths queued for deletion are on their way out; pulling them back would undo the delete.
        val pending = store.pendingDeletions()

        val remoteFolders = when (val root = drive.list(emptyList())) {
            is DavResult.Ok -> root.value.orEmpty().filter { it.isCollection && !it.name.startsWith(".") && it.name !in pending }
            is DavResult.Failure -> return outcomeOf(root)
        }
        val plan = mutableListOf<Pair<String, List<DavEntry>>>()
        for (folder in remoteFolders) {
            when (val listing = drive.list(listOf(folder.name))) {
                is DavResult.Ok -> listing.value?.let { entries ->
                    plan += folder.name to entries.filter {
                        !it.isCollection && isImageName(it.name) && "${folder.name}/${it.name}" !in pending
                    }
                }
                is DavResult.Failure -> return outcomeOf(listing)
            }
        }

        val total = plan.sumOf { it.second.size }
        var done = 0
        onProgress(done, total)

        val tally = Tally()
        val locals = store.localFolders().toMutableList()
        for ((remoteName, files) in plan) {
            val target = Target(resolveFolder(remoteName, locals, tally))
            val images = store.localImages(target.id)
            for (file in files) {
                pullFile(drive, target, remoteName, file, images, tally)?.let { return it }
                onProgress(++done, total)
            }
            // A shoot can span places; the event keeps the one most of its captions name.
            if (target.location == null) target.likelyLocation()?.let { store.setLocationIfMissing(target.id, it) }
        }
        return PullOutcome.Done(tally.toSummary())
    }

    private suspend fun resolveFolder(remoteName: String, locals: MutableList<LocalFolder>, tally: Tally): LocalFolder {
        locals.firstOrNull { it.remoteName == remoteName }?.let { return it }

        // An event of the same name that was never synced is the same shoot, not a second one.
        val sameName = locals.firstOrNull {
            it.remoteName == null && RemoteNames.folderBase(it.name).equals(remoteName, ignoreCase = true)
        }
        if (sameName != null) {
            store.linkFolder(sameName.id, remoteName, sameName.name)
            return sameName.copy(remoteName = remoteName).also { locals[locals.indexOf(sameName)] = it }
        }

        tally.newEvents++
        return store.createLinkedEvent(remoteName, remoteName, System.currentTimeMillis()).also { locals += it }
    }

    /** Null means carry on with the next file. */
    private suspend fun pullFile(
        drive: RemoteDrive,
        target: Target,
        remoteFolder: String,
        entry: DavEntry,
        images: List<LocalImage>,
        tally: Tally,
    ): PullOutcome? {
        val match = images.firstOrNull { it.remoteFileName == entry.name }
            ?: images.firstOrNull {
                it.remoteFileName == null && RemoteNames.fileBase(it.displayName).equals(entry.name, ignoreCase = true)
            }
        val localSize = match?.mediaStoreUri?.let(album::sizeOf)
        if (match != null && localSize != null) {
            val inSync = match.remoteFileName == entry.name &&
                match.syncedVersion == match.fileVersion &&
                match.remoteSize == entry.size
            if (inSync) return null.also { tally.unchanged++ }
            if (match.remoteFileName == null && localSize == entry.size) {
                store.linkImage(match.id, entry.name, localSize)
                return null.also { tally.unchanged++ }
            }
        }

        val temp = album.newTempFile()
        try {
            when (val result = drive.download(listOf(remoteFolder, entry.name), temp)) {
                is DavResult.Ok -> if (!result.value) return null.also { tally.skipped++ }
                is DavResult.ItemRejected, is DavResult.ParentMissing -> return null.also { tally.skipped++ }
                is DavResult.Failure -> return outcomeOf(result)
            }
            val inspected = album.inspect(temp) ?: return null.also { tally.skipped++ }

            val uri = if (match != null) {
                match.mediaStoreUri?.takeIf { album.overwrite(it, temp) } ?: album.publish(temp, match.displayName, target.name)
            } else {
                album.publish(temp, entry.name, target.name)
            }
            val capturedOn = inspected.capturedOn ?: album.dateTaken(uri)
            val parts = inspected.caption?.let {
                EditorialCaption.parse(it, capturedOn, target.location ?: target.likelyLocation())
            }
            parts?.location?.let { target.locationsSeen.merge(it, 1, Int::plus) }

            val pulled = PulledImage(
                remoteFileName = entry.name,
                mediaStoreUri = uri,
                sha256 = sha256(temp),
                widthPx = inspected.widthPx,
                heightPx = inspected.heightPx,
                byteSize = temp.length(),
                capturedOn = capturedOn,
                title = inspected.title?.trim()?.ifEmpty { null },
                description = parts?.body?.ifEmpty { null },
                keywords = inspected.keywords,
                category = inspected.category?.trim()?.ifEmpty { null },
            )
            val now = System.currentTimeMillis()
            if (match != null) {
                store.replacePulled(match.id, pulled, now)
                tally.replaced++
            } else {
                store.insertPulled(target.id, entry.name, pulled, now)
                tally.added++
            }
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return PullOutcome.Failed("Could not save ${entry.name} on this phone: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            temp.delete()
        }
    }

    private fun outcomeOf(failure: DavResult.Failure): PullOutcome = when (failure) {
        is DavResult.Transient -> PullOutcome.Retry(failure.message)
        is DavResult.AuthRejected -> {
            flags.authRejected = true
            PullOutcome.Failed(NextcloudSyncEngine.AUTH_REJECTED)
        }
        is DavResult.Fatal, is DavResult.ItemRejected, is DavResult.ParentMissing -> PullOutcome.Failed(failure.message)
    }

    private class Target(folder: LocalFolder) {
        val id = folder.id
        val name = folder.name
        val location = folder.location?.takeIf { it.isNotBlank() }
        val locationsSeen = linkedMapOf<String, Int>()

        fun likelyLocation(): String? = locationsSeen.maxByOrNull { it.value }?.key
    }

    private class Tally {
        var newEvents = 0
        var added = 0
        var replaced = 0
        var unchanged = 0
        var skipped = 0

        fun toSummary() = PullSummary(newEvents, added, replaced, unchanged, skipped)
    }

    companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "heic", "heif", "webp")

        fun isImageName(name: String): Boolean =
            !name.startsWith(".") && name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
