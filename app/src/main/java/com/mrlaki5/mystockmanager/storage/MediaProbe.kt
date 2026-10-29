package com.mrlaki5.mystockmanager.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.mrlaki5.mystockmanager.data.db.entity.MediaKind
import java.io.FileDescriptor

/** [mimeType] is what the decoder found, null when it did not say; [durationMs] is null for photos. */
data class ProbedMedia(
    val kind: MediaKind,
    val widthPx: Int,
    val heightPx: Int,
    val durationMs: Long?,
    val capturedOn: String?,
    val mimeType: String?,
)

/** Reads through a file descriptor, so a large clip is seeked in place rather than copied out. */
object MediaProbe {

    /** Decides photo or clip from the content, since a provider's declared type can be missing or generic. */
    fun inspect(fd: FileDescriptor): ProbedMedia? = image(fd) ?: video(fd)

    /** A frame from the middle of the clip, where the subject is likelier settled than at the cut-in; fits in [maxEdge]. */
    fun middleFrame(fd: FileDescriptor, maxEdge: Int): Bitmap? = runCatching {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(fd)
            val middleUs = (retriever.number(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: 0L) * 1000 / 2
            retriever.getScaledFrameAtTime(middleUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxEdge, maxEdge)
        }
    }.getOrNull()

    private fun image(fd: FileDescriptor): ProbedMedia? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFileDescriptor(fd, null, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return ProbedMedia(MediaKind.IMAGE, bounds.outWidth, bounds.outHeight, null, CaptureDate.readFrom(fd), bounds.outMimeType)
    }

    private fun video(fd: FileDescriptor): ProbedMedia? = runCatching {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(fd)
            val width = retriever.number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: return null
            val height = retriever.number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: return null
            val duration = retriever.number(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: return null
            if (width <= 0 || height <= 0) return null
            // Phones record portrait clips as landscape frames plus a rotation flag.
            val turned = retriever.number(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.let { it % 180 != 0L } == true
            ProbedMedia(
                kind = MediaKind.VIDEO,
                widthPx = if (turned) height else width,
                heightPx = if (turned) width else height,
                durationMs = duration,
                capturedOn = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.let(CaptureDate::fromMp4),
                mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
            )
        }
    }.getOrNull()

    private fun MediaMetadataRetriever.number(key: Int): Long? = extractMetadata(key)?.trim()?.toLongOrNull()
}
