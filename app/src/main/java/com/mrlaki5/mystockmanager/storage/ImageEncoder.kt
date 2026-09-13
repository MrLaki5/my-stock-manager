package com.mrlaki5.mystockmanager.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Downscales a JPEG for the vision call. Full-resolution stock frames are 20-50MP;
 * sending them wastes tokens and upload time without improving keyword quality, so the
 * long edge is capped and the result re-encoded at a modest quality.
 */
object ImageEncoder {

    private const val MAX_EDGE = 1024
    private const val QUALITY = 85

    fun toBase64Jpeg(file: File): String {
        val bitmap = decodeScaled(file) ?: error("Could not decode ${file.name} as an image")
        return try {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }
        } finally {
            bitmap.recycle()
        }
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
