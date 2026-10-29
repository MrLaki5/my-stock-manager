package com.mrlaki5.mystockmanager.storage

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.mrlaki5.mystockmanager.data.db.dao.ImageDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** A ready system dialog that moves [count] camera-roll originals to the trash. */
class OriginalTrashRequest(val intent: PendingIntent, val count: Int)

/** Moves the originals of freshly imported images to the system trash, so the gallery shows each photo once. */
@Singleton
class OriginalTrash @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val imageDao: ImageDao,
    private val mediaStore: MediaStoreExporter,
) {

    /** The batch trash dialog arrived in Android 11. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Null when nothing can be trashed: Android 10, cloud-only picks, or picks that are this
     * app's own album copies, which must never be treated as someone else's original.
     */
    suspend fun request(sources: List<ImportedSource>): OriginalTrashRequest? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext null
        val ownIds = imageDao.getAlbumRows().mapNotNull { MediaStoreExporter.idOf(it.mediaStoreUri) }.toSet()
        // MediaStore only trashes a clip through a video URI, so each id keeps the kind it was picked as.
        val originals = sources
            .mapNotNull { source -> mediaIdOf(source.uri)?.let { it to source.kind } }
            .filter { (id, _) -> id !in ownIds }
            .distinctBy { (id, _) -> id }
        if (originals.isEmpty()) return@withContext null
        val uris = originals.map { (id, kind) -> MediaStoreExporter.mediaUri(id, kind) }
        runCatching { OriginalTrashRequest(mediaStore.trashRequest(uris), uris.size) }.getOrNull()
    }

    private fun mediaIdOf(uri: Uri): Long? = when (uri.authority) {
        MEDIA_DOCUMENTS -> runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()
            ?.let { MediaStoreExporter.idOf(it.toString()) }
        else -> localMediaId(uri.authority, uri.pathSegments)
    }

    companion object {
        private const val MEDIA_DOCUMENTS = "com.android.providers.media.documents"
        private const val LOCAL_PICKER = "com.android.providers.media.photopicker"

        /**
         * The MediaStore id behind a Photo Picker or MediaStore URI. A local picker URI ends in the
         * MediaStore id (verified on Android 13, not documented); cloud picks have no local file.
         */
        fun localMediaId(authority: String?, segments: List<String>): Long? {
            if (authority != MediaStore.AUTHORITY) return null
            val first = segments.firstOrNull() ?: return null
            val fromPicker = first.startsWith("picker") && LOCAL_PICKER in segments
            val fromMediaStore = segments.getOrNull(1) in setOf("images", "video") && segments.getOrNull(2) == "media"
            if (!fromPicker && !fromMediaStore) return null
            return segments.last().toLongOrNull()
        }
    }
}
