package com.mrlaki5.mystockmanager.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor

/**
 * Downscales a JPEG or a video frame for the vision model. Full-resolution stock frames are
 * 20-50MP; sending them wastes tokens and upload time without improving keyword quality, so
 * the long edge is capped and the result re-encoded at a modest quality.
 */
object ImageEncoder {

    private const val MAX_EDGE = 1024
    private const val QUALITY = 85

    fun toJpeg(file: File): ByteArray = compress(decodeScaled(file) ?: error("Could not decode ${file.name} as an image"))

    /** The clip's middle frame, at the same size and quality a photo is sent at. */
    fun videoFrameToJpeg(fd: FileDescriptor): ByteArray =
        compress(MediaProbe.middleFrame(fd, MAX_EDGE) ?: error("Could not read a frame from the video"))

    private fun compress(bitmap: Bitmap): ByteArray =
        try {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }

    private fun decodeScaled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longEdge / (sample * 2) >= MAX_EDGE) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null

        val decodedLongEdge = maxOf(decoded.width, decoded.height)
        if (decodedLongEdge <= MAX_EDGE) return decoded

        val scale = MAX_EDGE.toFloat() / decodedLongEdge
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }
}
