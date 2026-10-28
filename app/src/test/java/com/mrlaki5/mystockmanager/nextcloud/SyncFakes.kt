package com.mrlaki5.mystockmanager.nextcloud

import com.mrlaki5.mystockmanager.data.db.dao.CsvImageRow
import com.mrlaki5.mystockmanager.metadata.model.ParsedMetadata
import java.io.InputStream

/** An in-memory [SyncStore] and [PullStore] that follow the same rules as SyncDao's and PullDao's SQL. */
class FakeSyncStore : SyncStore, PullStore {

    data class Event(val id: Long, var name: String, var location: String? = null)
    data class Image(val id: Long, val folderId: Long, val displayName: String, var fileVersion: Long = 1) {
        var uri = "content://images/$id"
        var title: String? = null
        var description: String? = null
        var keywords: List<String> = emptyList()
        var category: String? = null
        var capturedOn: String? = null
        var captionPlace: String? = null
        var metadataVersion = 1L
        var generating = false
    }
    data class SyncFolder(
        val remoteName: String,
        val localName: String,
        var csvVersion: Long = 1,
        var csvSyncedVersion: Long? = null,
    )
    data class SyncImage(
        var remoteFileName: String,
        var syncedVersion: Long? = null,
        var remoteSize: Long? = null,
        var failedVersion: Long? = null,
        var csvVersion: Long? = null,
    )

    val events = linkedMapOf<Long, Event>()
    val images = linkedMapOf<Long, Image>()
    val syncFolders = linkedMapOf<Long, SyncFolder>()
    val syncImages = linkedMapOf<Long, SyncImage>()
    val deletions = linkedSetOf<String>()
    private var nextId = 1000L

    fun addEvent(id: Long, name: String, location: String? = null) {
        events[id] = Event(id, name, location)
    }

    fun addImage(id: Long, folderId: Long, displayName: String) {
        images[id] = Image(id, folderId, displayName)
    }

    fun rename(folderId: Long, name: String) {
        events.getValue(folderId).name = name
    }

    fun rewrite(imageId: Long) {
        images.getValue(imageId).fileVersion++
    }

    fun describe(imageId: Long, title: String, keywords: List<String>, description: String = "A body.", category: String? = null) {
        images.getValue(imageId).apply {
            this.title = title
            this.description = description
            this.keywords = keywords
            this.category = category
            metadataVersion++
        }
    }

    fun deleteImage(imageId: Long, tombstone: Boolean = true) {
        if (tombstone) {
            remotePathOf(imageId)?.let { deletions += it }
            images[imageId]?.let { syncFolders[it.folderId] }?.let { it.csvVersion++ }
        }
        images.remove(imageId)
        syncImages.remove(imageId)
    }

    fun deleteEvent(folderId: Long, tombstone: Boolean = true) {
        if (tombstone) {
            syncFolders[folderId]?.let { folder ->
                deletions.removeAll { it.startsWith(folder.remoteName + "/") }
                deletions += folder.remoteName
            }
        }
        images.values.filter { it.folderId == folderId }.map { it.id }.forEach {
            images.remove(it)
            syncImages.remove(it)
        }
        events.remove(folderId)
        syncFolders.remove(folderId)
    }

    fun isDirty(image: Image): Boolean {
        val sync = syncImages[image.id]
        val owed = sync == null || sync.syncedVersion == null || sync.syncedVersion != image.fileVersion
        return owed && sync?.failedVersion != image.fileVersion
    }

    fun isCsvStale(folderId: Long): Boolean {
        val folder = syncFolders[folderId] ?: return false
        if (images.values.any { it.folderId == folderId && it.generating }) return false
        if (folder.csvSyncedVersion != null && folder.csvSyncedVersion != folder.csvVersion) return true
        return csvImagesOf(folderId).any { (image, sync) -> sync.csvVersion != image.metadataVersion }
    }

    private fun csvImagesOf(folderId: Long): List<Pair<Image, SyncImage>> =
        images.values.filter { it.folderId == folderId }.sortedBy { it.id }.mapNotNull { image ->
            syncImages[image.id]?.takeIf { it.remoteSize != null }?.let { image to it }
        }

    private fun remotePathOf(imageId: Long): String? {
        val image = images[imageId] ?: return null
        val file = syncImages[imageId]?.remoteFileName ?: return null
        val folder = syncFolders[image.folderId]?.remoteName ?: return null
        return "$folder/$file"
    }

    override suspend fun nextAction(): SyncAction? {
        deletions.firstOrNull()?.let { return SyncAction.DeleteRemote(it) }

        val folder = events.values
            .filter { event -> syncFolders[event.id]?.let { it.localName != event.name } ?: true }
            .sortedWith(compareBy({ syncFolders[it.id] == null }, { it.id }))
            .firstOrNull()
        if (folder != null) {
            val taken = syncFolders.filterKeys { it != folder.id }.values.map { it.remoteName }
            return when (val synced = syncFolders[folder.id]) {
                null -> SyncAction.CreateFolder(folder.id, folder.name, taken)
                else -> SyncAction.MoveFolder(folder.id, folder.name, synced.remoteName, taken)
            }
        }

        val image = images.values.sortedBy { it.id }.firstOrNull { image ->
            isDirty(image) && syncFolders[image.folderId]?.localName == events[image.folderId]?.name
        } ?: return nextCsv()
        val sync = syncImages.getOrPut(image.id) {
            val taken = syncImages.filterKeys { images[it]?.folderId == image.folderId }.values.map { it.remoteFileName }
            SyncImage(RemoteNames.fileName(image.displayName, image.id, taken))
        }
        return SyncAction.Upload(
            imageId = image.id,
            folderId = image.folderId,
            folder = syncFolders.getValue(image.folderId).remoteName,
            fileName = sync.remoteFileName,
            displayName = image.displayName,
            version = image.fileVersion,
            mediaStoreUri = image.uri,
            firstUpload = sync.remoteSize == null,
        )
    }

    private fun nextCsv(): SyncAction? {
        val folderId = syncFolders.keys.sorted().firstOrNull { id ->
            syncFolders.getValue(id).localName == events[id]?.name && isCsvStale(id)
        } ?: return null
        val folder = syncFolders.getValue(folderId)
        return SyncAction.WriteCsv(
            folderId = folderId,
            folder = folder.remoteName,
            version = folder.csvVersion,
            onCloud = folder.csvSyncedVersion != null,
            images = csvImagesOf(folderId).map { (image, sync) ->
                CsvImageRow(
                    image.id, sync.remoteFileName, image.metadataVersion, image.title, image.description,
                    image.keywords, image.category, image.captionPlace, image.capturedOn,
                ).toCsvImage()
            },
        )
    }

    override suspend fun dropDeletion(path: String) {
        deletions.remove(path)
    }

    override suspend fun commitFolder(folderId: Long, remoteName: String, localName: String, movedFrom: String?) {
        if (movedFrom != null) {
            val moved = deletions.filter { it == movedFrom || it.startsWith("$movedFrom/") }
            deletions.removeAll(moved.toSet())
            moved.forEach { deletions += remoteName + it.removePrefix(movedFrom) }
        }
        if (folderId in events) {
            syncFolders[folderId] = syncFolders[folderId]?.copy(remoteName = remoteName, localName = localName)
                ?: SyncFolder(remoteName, localName)
        } else {
            deletions += remoteName
        }
    }

    override suspend fun forgetFolder(folderId: Long) {
        images.values.filter { it.folderId == folderId }.forEach { syncImages.remove(it.id) }
        syncFolders.remove(folderId)
    }

    override suspend fun commitUpload(imageId: Long, version: Long, size: Long) {
        syncImages[imageId]?.apply {
            syncedVersion = version
            remoteSize = size
            failedVersion = null
        }
    }

    override suspend fun renameUpload(imageId: Long, fileName: String) {
        syncImages[imageId]?.remoteFileName = fileName
    }

    override suspend fun markFailed(imageId: Long, version: Long, error: String) {
        syncImages[imageId]?.failedVersion = version
    }

    override suspend fun syncedFolders(): List<SyncedFolder> = syncFolders.map { (id, folder) ->
        SyncedFolder(
            folderId = id,
            remoteName = folder.remoteName,
            images = images.values.filter { it.folderId == id }.mapNotNull { image ->
                syncImages[image.id]?.let { SyncedImage(image.id, it.remoteFileName, it.remoteSize) }
            },
            csvOnCloud = folder.csvSyncedVersion != null,
        )
    }

    override suspend fun markDirty(imageIds: List<Long>) {
        imageIds.forEach { syncImages[it]?.syncedVersion = null }
    }

    override suspend fun clearFailures() {
        syncImages.values.forEach { it.failedVersion = null }
    }

    override suspend fun commitCsv(folderId: Long, version: Long, images: List<CsvImage>, written: Boolean) {
        images.forEach { syncImages[it.imageId]?.csvVersion = it.metadataVersion }
        syncFolders[folderId]?.csvSyncedVersion = version.takeIf { written }
    }

    override suspend fun forgetCsv(folderId: Long) {
        syncFolders[folderId]?.csvSyncedVersion = null
        images.values.filter { it.folderId == folderId }.forEach { syncImages[it.id]?.csvVersion = null }
    }

    override suspend fun pendingDeletions(): Set<String> = deletions.toSet()

    override suspend fun localFolders(): List<LocalFolder> =
        events.values.map { LocalFolder(it.id, it.name, it.location, syncFolders[it.id]?.remoteName) }

    override suspend fun localImages(folderId: Long): List<LocalImage> =
        images.values.filter { it.folderId == folderId }.map { image ->
            val sync = syncImages[image.id]
            LocalImage(
                image.id, image.displayName, image.uri, image.fileVersion, image.capturedOn, image.title,
                image.description, image.captionPlace, image.keywords, image.category,
                sync?.remoteFileName, sync?.syncedVersion, sync?.remoteSize,
            )
        }

    override suspend fun createLinkedEvent(name: String, remoteName: String, now: Long): LocalFolder {
        var candidate = name
        var suffix = 2
        while (events.values.any { it.name.equals(candidate, ignoreCase = true) }) candidate = "$name (${suffix++})"
        val id = nextId++
        events[id] = Event(id, candidate)
        syncFolders[id] = SyncFolder(remoteName, candidate)
        return LocalFolder(id, candidate, null, remoteName)
    }

    override suspend fun linkFolder(folderId: Long, remoteName: String, localName: String) {
        syncFolders[folderId] = SyncFolder(remoteName, localName)
    }

    override suspend fun setLocationIfMissing(folderId: Long, location: String) {
        events[folderId]?.let { if (it.location.isNullOrEmpty()) it.location = location }
    }

    override suspend fun insertPulled(folderId: Long, displayName: String, image: PulledImage, now: Long): Long {
        val id = nextId++
        images[id] = Image(id, folderId, displayName).also { it.fillFrom(image) }
        (image.csvMetadata ?: image.embeddedMetadata)?.let { images.getValue(id).fillFrom(it) }
        val csvVersion = 1L.takeIf { image.csvMetadata != null || image.embeddedMetadata == null }
        syncImages[id] = SyncImage(image.remoteFileName, syncedVersion = 1, remoteSize = image.byteSize, csvVersion = csvVersion)
        return id
    }

    override suspend fun replacePulled(imageId: Long, image: PulledImage, now: Long) {
        val local = images.getValue(imageId)
        local.fileVersion++
        if (local.capturedOn != image.capturedOn) local.metadataVersion++
        local.fillFrom(image)
        val csvVersion = syncImages[imageId]?.csvVersion
        syncImages[imageId] = SyncImage(image.remoteFileName, local.fileVersion, image.byteSize, csvVersion = csvVersion)
        image.csvMetadata?.let { applyMetadata(imageId, it, now) }
            ?: image.embeddedMetadata?.let { adoptEmbedded(imageId, it, now) }
    }

    override suspend fun linkImage(imageId: Long, remoteFileName: String, size: Long) {
        syncImages[imageId] = SyncImage(remoteFileName, images.getValue(imageId).fileVersion, size)
    }

    override suspend fun applyMetadata(imageId: Long, metadata: ParsedMetadata, now: Long): Boolean {
        val local = images.getValue(imageId)
        val same = local.title.orEmpty() == metadata.title.orEmpty() &&
            local.description.orEmpty() == metadata.description.orEmpty() &&
            local.captionPlace.orEmpty() == metadata.captionPlace && local.keywords == metadata.keywords &&
            local.category.orEmpty() == metadata.category.orEmpty()
        if (!same) {
            local.fillFrom(metadata)
            local.metadataVersion++
        }
        syncImages[imageId]?.csvVersion = local.metadataVersion
        return !same
    }

    override suspend fun adoptEmbedded(imageId: Long, metadata: ParsedMetadata, now: Long) {
        val local = images.getValue(imageId)
        if (local.title != null) return
        local.fillFrom(metadata)
        local.metadataVersion++
    }

    override suspend fun csvFound(folderId: Long) {
        syncFolders[folderId]?.let { it.csvSyncedVersion = it.csvVersion }
    }

    private fun Image.fillFrom(image: PulledImage) {
        uri = image.mediaStoreUri
        capturedOn = image.capturedOn
    }

    private fun Image.fillFrom(metadata: ParsedMetadata) {
        title = metadata.title
        description = metadata.description
        keywords = metadata.keywords
        category = metadata.category
        captionPlace = metadata.captionPlace
    }
}

/** An in-memory NextCloud sync root, one level of folders deep, as the app uses it. */
class FakeDrive : RemoteDrive {

    val folders = linkedSetOf<String>()
    val files = linkedMapOf<String, Long>()
    val bodies = mutableMapOf<String, ByteArray>()
    val calls = mutableListOf<String>()

    /** A file whose bytes are [content], so a [FakeAlbum] can tell downloads apart. */
    fun putFile(key: String, content: String) {
        folders += key.substringBefore('/')
        bodies[key] = content.toByteArray()
        files[key] = content.length.toLong()
    }

    /** Lets a test fail a call, or change the app's state while the call is in flight. */
    var intercept: suspend (String) -> DavResult.Failure? = { null }

    private suspend fun call(description: String): DavResult.Failure? {
        calls += description
        return intercept(description)
    }

    override suspend fun ensureRoot(): DavResult<Unit> {
        call("ROOT")?.let { return it }
        return DavResult.Ok(Unit)
    }

    override suspend fun mkcol(path: List<String>): DavResult<MkcolResult> {
        val name = path.single()
        call("MKCOL $name")?.let { return it }
        if (name in folders || name in files) return DavResult.Ok(MkcolResult.EXISTS)
        folders += name
        return DavResult.Ok(MkcolResult.CREATED)
    }

    override suspend fun move(from: List<String>, to: List<String>): DavResult<MoveResult> {
        val source = from.single()
        val target = to.single()
        call("MOVE $source -> $target")?.let { return it }
        if (source !in folders) return DavResult.Ok(MoveResult.SOURCE_MISSING)
        if (target in folders || target in files) return DavResult.Ok(MoveResult.DESTINATION_EXISTS)
        folders -= source
        folders += target
        files.keys.filter { it.startsWith("$source/") }.forEach { key ->
            val moved = "$target/" + key.removePrefix("$source/")
            files[moved] = files.remove(key)!!
            bodies.remove(key)?.let { bodies[moved] = it }
        }
        return DavResult.Ok(MoveResult.MOVED)
    }

    override suspend fun put(
        path: List<String>,
        length: Long,
        createOnly: Boolean,
        open: () -> InputStream,
    ): DavResult<PutResult> {
        val key = path.joinToString("/")
        call("PUT $key")?.let { return it }
        if (path.first() !in folders) return DavResult.ParentMissing("missing")
        if (createOnly && key in files) return DavResult.Ok(PutResult.ALREADY_EXISTS)
        val bytes = open().use { it.readBytes() }
        check(bytes.size.toLong() == length) { "Wrote ${bytes.size} bytes but declared $length" }
        files[key] = length
        bodies[key] = bytes
        return DavResult.Ok(PutResult.WRITTEN)
    }

    override suspend fun delete(path: List<String>): DavResult<Unit> {
        val key = path.joinToString("/")
        call("DELETE $key")?.let { return it }
        if (path.size == 1) {
            folders -= key
            files.keys.removeAll { it.startsWith("$key/") }
            bodies.keys.removeAll { it.startsWith("$key/") }
        } else {
            files.remove(key)
            bodies.remove(key)
        }
        return DavResult.Ok(Unit)
    }

    override suspend fun download(path: List<String>, to: java.io.File): DavResult<Boolean> {
        val key = path.joinToString("/")
        call("GET $key")?.let { return it }
        val size = files[key] ?: return DavResult.Ok(false)
        to.writeBytes(bodies[key] ?: ByteArray(size.toInt()))
        return DavResult.Ok(true)
    }

    override suspend fun list(path: List<String>): DavResult<List<DavEntry>?> {
        if (path.isEmpty()) {
            call("LIST /")?.let { return it }
            return DavResult.Ok(folders.filter { '/' !in it }.map { DavEntry(it, isCollection = true, size = null) })
        }
        val name = path.single()
        call("LIST $name")?.let { return it }
        if (name !in folders) return DavResult.Ok(null)
        val children = files.filterKeys { it.startsWith("$name/") }.map { (key, size) ->
            DavEntry(key.removePrefix("$name/"), isCollection = false, size = size)
        }
        val subfolders = folders.filter { it.startsWith("$name/") }.map { DavEntry(it.removePrefix("$name/"), true, null) }
        return DavResult.Ok(children + subfolders)
    }

    override suspend fun stat(path: List<String>): DavResult<DavEntry?> {
        val key = path.joinToString("/")
        call("STAT $key")?.let { return it }
        return DavResult.Ok(
            when {
                path.size == 1 && key in folders -> DavEntry(key, isCollection = true, size = null)
                key in files -> DavEntry(path.last(), isCollection = false, size = files[key])
                else -> null
            },
        )
    }
}

class FakeFiles : LocalFiles {
    val bytes = mutableMapOf<String, ByteArray>()

    fun put(imageId: Long, size: Int) {
        bytes["content://images/$imageId"] = ByteArray(size)
    }

    override fun sizeOf(uri: String): Long? = bytes[uri]?.size?.toLong()

    override fun open(uri: String): InputStream = bytes.getValue(uri).inputStream()

    override fun newTempFile(): java.io.File = java.io.File.createTempFile("csv", ".tmp").apply { deleteOnExit() }
}

class FakeFlags(override var account: NextcloudAccount?) : SyncFlags {
    override var authRejected = false
    override var fullCheckRequested = false
    var fullChecks = 0

    override fun fullCheckDone(at: Long) {
        fullChecks++
    }
}

/** The phone's album for pulls: entries by URI, and what inspecting each downloaded body yields. */
class FakeAlbum(private val dir: java.io.File) : PullAlbum {
    val entries = linkedMapOf<String, ByteArray>()
    val inspections = mutableMapOf<String, InspectedImage>()
    private var next = 0

    fun inspectsAs(content: String, image: InspectedImage = plain) {
        inspections[content] = image
    }

    override fun newTempFile() = java.io.File(dir, "pull-${next++}.tmp")

    override fun inspect(file: java.io.File): InspectedImage? = inspections[file.readText()]

    override fun publish(file: java.io.File, displayName: String, eventName: String): String {
        val uri = "content://album/$eventName/${next++}"
        entries[uri] = file.readBytes()
        return uri
    }

    override fun overwrite(uri: String, file: java.io.File): Boolean {
        if (uri !in entries) return false
        entries[uri] = file.readBytes()
        return true
    }

    override fun sizeOf(uri: String): Long? = entries[uri]?.size?.toLong()

    override fun dateTaken(uri: String): String? = null

    companion object {
        val plain = InspectedImage(100, 100, null, null)
    }
}
