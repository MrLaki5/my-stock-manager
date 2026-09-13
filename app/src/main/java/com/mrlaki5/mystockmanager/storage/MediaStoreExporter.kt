package com.mrlaki5.mystockmanager.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.io.InputStream

/**
 * The app's only image store. Every imported image lives in
 * `Pictures/StockReady/<event>/`, visible to the gallery and to every file picker from
 * the moment it is imported — there is deliberately no second private copy to drift
 * out of sync with this one.
 *
 * Generation rewrites these files in place. That is safe because Commons Imaging's
 * rewriters replace their segment rather than appending: `writeIptc` strips the
 * existing APP13 before inserting, and `updateXmpXml` does the same for APP1.
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

    fun delete(uri: Uri): Int = context.contentResolver.delete(uri, null, null)

    fun exists(uri: Uri): Boolean =
        runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)
                ?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)

    /** Follows an event rename, so the album folder never drifts from the event name. */
    fun moveToEvent(uri: Uri, newEventName: String) {
        context.contentResolver.update(
            uri,
            ContentValues().apply {
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePathFor(newEventName))
            },
            null,
            null,
        )
    }

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

    companion object {
        const val ALBUM_ROOT = "Pictures/StockReady"

        /**
         * The album name a picker shows. MediaStore buckets are flat — the picker groups
         * by the immediate parent directory only — so each event becomes its own
         * top-level album. The prefix keeps them clustered together in an album list
         * full of unrelated folders.
         */
        fun albumNameFor(eventName: String): String = "StockReady - ${sanitize(eventName)}"

        /** MediaStore rejects path segments containing separators or control characters. */
        private fun sanitize(eventName: String): String =
            eventName.trim()
                .replace(Regex("""[/\\:*?"<>|\x00-\x1F]"""), "-")
                .take(64)
                .ifBlank { "Unnamed event" }

        fun relativePathFor(eventName: String): String = "$ALBUM_ROOT/${albumNameFor(eventName)}/"
    }
}
