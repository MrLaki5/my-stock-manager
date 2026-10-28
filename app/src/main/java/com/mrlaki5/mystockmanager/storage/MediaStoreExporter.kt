package com.mrlaki5.mystockmanager.storage

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.InputStream

/** One album file as MediaStore reports it to the app that owns it. */
data class AlbumEntry(val trashed: Boolean, val expiresAt: Long?)

/**
 * The app's only image store. Every imported image lives in
 * `Pictures/StockReady/<event>/`, visible to the gallery and to every file picker from
 * the moment it is imported — there is deliberately no second private copy to drift
 * out of sync with this one.
 *
 * On minSdk 29 scoped storage is always on and inserting our own media needs no
 * runtime permission.
 */
class MediaStoreExporter(private val context: Context) {

    private val collection: Uri
        get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** Inserts a new image into the event's album and returns its MediaStore URI. */
    fun publish(source: File, displayName: String, eventName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePathFor(eventName))
            // Hides the row from other apps until the bytes are fully written.
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values)
            ?: error("MediaStore refused to create an entry for $displayName")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: error("Could not open an output stream for $uri")

            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /** Replaces the bytes of an existing entry. "wt" truncates rather than overwriting in place. */
    fun overwrite(uri: Uri, source: File) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        } ?: error("Could not open $uri for writing")
    }

    /** Copies an entry out to a working file, for hashing, encoding, or metadata rewriting. */
    fun copyTo(uri: Uri, destination: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { input.copyTo(it) }
        } ?: error("Could not open $uri for reading")
    }

    /** Opens the entry for reading. For callers that only need the header, not the pixels. */
    fun openInput(uri: Uri): InputStream? = context.contentResolver.openInputStream(uri)

    /**
     * What MediaStore believes the capture time was, in epoch millis, or null if it does
     * not know. A fallback for files whose EXIF carries no date of its own; MediaStore
     * stores -1 rather than null when it has nothing, which is not a time.
     */
    fun dateTakenMillis(uri: Uri): Long? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Images.Media.DATE_TAKEN),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) return@use null
            cursor.getLong(0).takeIf { it > 0 }
        }
    }.getOrNull()

    /** Current byte length, or null if the entry is gone; the row's byteSize can be stale. */
    fun sizeOf(uri: Uri): Long? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
    }.getOrNull()?.takeIf { it >= 0 }

    /** Trashed entries are deleted too, so an image removed in the app does not linger in the trash. */
    fun delete(uri: Uri): Int = withTrashed(
        modern = { context.contentResolver.delete(uri, it) },
        legacy = { context.contentResolver.delete(uri, null, null) },
    )

    fun exists(uri: Uri): Boolean =
        runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)
                ?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)

    /** Follows an event rename, so the album folder never drifts from the event name; trashed files move too. */
    fun moveToEvent(uri: Uri, newEventName: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePathFor(newEventName))
        }
        withTrashed(
            modern = { context.contentResolver.update(uri, values, it) },
            legacy = { context.contentResolver.update(uri, values, null, null) },
        )
    }

    /**
     * The album entries behind [ids], trashed ones included. Ids MediaStore no longer knows are
     * absent from the map; null means MediaStore could not be read, which says nothing either way.
     */
    fun entries(ids: Collection<Long>): Map<Long, AlbumEntry>? {
        val found = mutableMapOf<Long, AlbumEntry>()
        // Chunked because API 29's SQLite caps a statement at 999 bound variables.
        for (chunk in ids.chunked(500)) {
            val selection = "${MediaStore.Images.Media._ID} IN (${chunk.joinToString(",") { "?" }})"
            val args = chunk.map(Long::toString).toTypedArray()
            val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_TRASHED, MediaStore.MediaColumns.DATE_EXPIRES),
                    Bundle().apply {
                        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                    },
                    null,
                )
            } else {
                context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)
            } ?: return null
            cursor.use {
                while (it.moveToNext()) {
                    val trashed = it.columnCount > 1 && it.getInt(1) == 1
                    // DATE_EXPIRES is in seconds.
                    val expiresAt = if (trashed && !it.isNull(2)) it.getLong(2) * 1000 else null
                    found[it.getLong(0)] = AlbumEntry(trashed, expiresAt)
                }
            }
        }
        return found
    }

    /** Takes an owned entry back out of the trash. Needs no confirmation, because the app owns it. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun restore(uri: Uri): Boolean {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 0) }
        return context.contentResolver.update(uri, values, matchTrashed()) > 0
    }

    /** The system dialog that moves other apps' photos to the trash; throws if MediaStore rejects a URI. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun trashRequest(uris: List<Uri>): PendingIntent =
        MediaStore.createTrashRequest(context.contentResolver, uris, true)

    fun listEvent(eventName: String): List<Uri> {
        val uris = mutableListOf<Uri>()
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} = ?",
            arrayOf(relativePathFor(eventName)),
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                uris += ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
            }
        }
        return uris
    }

    private inline fun <T> withTrashed(modern: (Bundle) -> T, legacy: () -> T): T =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) modern(matchTrashed()) else legacy()

    @RequiresApi(Build.VERSION_CODES.R)
    private fun matchTrashed() = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }

    companion object {
        const val ALBUM_ROOT = "Pictures/StockReady"

        /** The MediaStore id at the end of an entry URI, or null if it does not end in one. */
        fun idOf(uri: String): Long? = uri.substringAfterLast('/').toLongOrNull()

        /** The URI of any image on the phone by its MediaStore id, whichever volume it is on. */
        fun imageUri(id: Long): Uri =
            ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), id)

        /**
         * The album name a picker shows. MediaStore buckets are flat — the picker groups
         * by the immediate parent directory only — so each event becomes its own
         * top-level album. The prefix keeps them clustered together in an album list
         * full of unrelated folders.
         */
        fun albumNameFor(eventName: String): String = "StockReady - ${sanitize(eventName)}"

        /** MediaStore rejects path segments containing separators or control characters. */
        internal fun sanitize(eventName: String): String =
            eventName.trim()
                .replace(Regex("""[/\\:*?"<>|\x00-\x1F]"""), "-")
                .take(64)
                .ifBlank { "Unnamed event" }

        fun relativePathFor(eventName: String): String = "$ALBUM_ROOT/${albumNameFor(eventName)}/"
    }
}
