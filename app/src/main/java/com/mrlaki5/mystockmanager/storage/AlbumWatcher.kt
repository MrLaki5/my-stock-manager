package com.mrlaki5.mystockmanager.storage

import android.content.Context
import android.database.ContentObserver
import android.provider.MediaStore
import com.mrlaki5.mystockmanager.data.db.dao.AlbumRow
import com.mrlaki5.mystockmanager.data.db.dao.ImageDao
import com.mrlaki5.mystockmanager.data.db.dao.SyncDao
import com.mrlaki5.mystockmanager.data.db.entity.AlbumState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

sealed interface AlbumChange {
    data class Mark(val imageId: Long, val state: AlbumState, val expiresAt: Long?) : AlbumChange

    /** The file left the trash for good, so the row goes too. */
    data class Forget(val imageId: Long) : AlbumChange
}

/** Keeps each image's [AlbumState] in step with MediaStore, e.g. after a delete in the gallery. */
@Singleton
class AlbumWatcher @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val imageDao: ImageDao,
    private val syncDao: SyncDao,
    private val mediaStore: MediaStoreExporter,
) {
    private val mutex = Mutex()
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val observer = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
            changes.tryEmit(Unit)
        }
    }

    /** MediaStore notifies for every photo and video on the phone, so bursts are collapsed into one check. */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        context.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        scope.launch {
            changes.onStart { emit(Unit) }.debounce(1.seconds).collect { runCatching { refresh() } }
        }
    }

    suspend fun refresh() = mutex.withLock {
        val rows = imageDao.getAlbumRows()
        val ids = rows.mapNotNull { MediaStoreExporter.idOf(it.mediaStoreUri) }
        val entries = mediaStore.entries(ids) ?: return@withLock
        for (change in reconcile(rows, entries)) {
            when (change) {
                is AlbumChange.Mark -> imageDao.setAlbumState(change.imageId, change.state, change.expiresAt)
                // The NextCloud copy stays as an archive: a purge from the trash is not a choice the user made here.
                is AlbumChange.Forget -> syncDao.deleteImage(change.imageId, tombstone = false)
            }
        }
    }

    companion object {
        /**
         * Only a row already seen in the trash is forgotten when its file disappears. Any other
         * disappearance is marked MISSING instead, since it may be MediaStore rebuilding its ids.
         */
        fun reconcile(rows: List<AlbumRow>, entries: Map<Long, AlbumEntry>): List<AlbumChange> =
            rows.mapNotNull { row ->
                val id = MediaStoreExporter.idOf(row.mediaStoreUri) ?: return@mapNotNull null
                val entry = entries[id]
                val target = when {
                    entry == null && row.albumState == AlbumState.TRASHED -> return@mapNotNull AlbumChange.Forget(row.id)
                    entry == null -> AlbumChange.Mark(row.id, AlbumState.MISSING, null)
                    entry.trashed -> AlbumChange.Mark(row.id, AlbumState.TRASHED, entry.expiresAt)
                    else -> AlbumChange.Mark(row.id, AlbumState.PRESENT, null)
                }
                target.takeIf { it.state != row.albumState || it.expiresAt != row.trashExpiresAt }
            }
    }
}
