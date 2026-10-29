package com.mrlaki5.mystockmanager.metadata

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mrlaki5.mystockmanager.data.db.entity.MediaKind
import com.mrlaki5.mystockmanager.storage.AppFileStore
import com.mrlaki5.mystockmanager.storage.ImageEncoder
import com.mrlaki5.mystockmanager.storage.MediaStoreExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The Phase 0 check that only a real device can answer: whether Commons Imaging's
 * IPTC/XMP read path actually runs on ART.
 *
 * The library declares fields and methods typed against `java.awt`, which does not
 * exist on Android. ART soft-verifies such classes and only throws at the instruction
 * that touches the missing type, so this should work — but "should" is exactly what
 * Phase 0 exists to replace with evidence. This runs in an UNMINIFIED debug build,
 * where the awt classes are still present (R8 removes them in release).
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceMetadataTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = AppFileStore(context)
    @Test
    fun readsEmbeddedMetadataOnArt() {
        val destination = store.newTempFile("art-out")
        val metadata = sampleMetadata(40)

        EmbeddedFixture.embed(sourceJpeg("art-source.jpg"), destination, metadata)

        assertEquals(metadata, EmbeddedMetadataReader.read(destination))
        assertNull(EmbeddedMetadataReader.read(sourceJpeg("art-plain.jpg")))
    }

    /**
     * Isolates the single riskiest line: `EmbeddedMetadataReader` instantiates
     * `JpegImagingParameters`, whose superclass declares a `BufferedImageFactory` field.
     * If ART is going to fail, it fails here.
     */
    @Test
    fun instantiatesImagingParametersWithoutAwt() {
        val params = org.apache.commons.imaging.formats.jpeg.JpegImagingParameters()
        assertNotNull(params)
    }

    @Test
    fun downscalesForTheVisionModels() {
        val source = sourceJpeg("encode-source.jpg", width = 4000, height = 3000)
        val jpeg = ImageEncoder.toJpeg(source)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        // A 4000x3000 frame must not be sent at full size.
        assertTrue("not downscaled: ${bounds.outWidth}x${bounds.outHeight}", maxOf(bounds.outWidth, bounds.outHeight) <= 1024)
        assertTrue("suspiciously small image: ${bounds.outWidth}x${bounds.outHeight}", bounds.outWidth >= 512)
    }

    @Test
    fun publishesEachEventAsItsOwnPickerAlbum() {
        val destination = sourceJpeg("mediastore-source.jpg")

        val exporter = MediaStoreExporter(context)
        val stamp = System.currentTimeMillis()
        val events = listOf("Test Harbour $stamp", "Test Market $stamp")
        val uris = events.map { event -> exporter.publish(destination, "test-$stamp.jpg", event, MediaKind.IMAGE) }

        uris.forEach { uri ->
            context.contentResolver.openInputStream(uri).use { input ->
                assertTrue("MediaStore copy is empty", (input?.readBytes()?.size ?: 0) > 0)
            }
        }

        // The Photo Picker groups albums by BUCKET_DISPLAY_NAME. Assert what it will
        // actually show, rather than assuming the nesting under StockReady survives.
        val buckets = uris.map { bucketDisplayNameOf(it) }
        try {
            events.forEach { event ->
                val expected = MediaStoreExporter.albumNameFor(event)
                assertTrue("expected album '$expected', got $buckets", buckets.contains(expected))
            }
        } finally {
            // This runs on real phones; leaving test albums would clutter the user's gallery and upload pickers.
            uris.forEach { exporter.delete(it) }
        }
    }

    @Test
    fun deletesEveryImageInAnEventAlbum() {
        val destination = sourceJpeg("delete-source.jpg")

        val exporter = MediaStoreExporter(context)
        val event = "Test Disposable ${System.currentTimeMillis()}"
        exporter.publish(destination, "disposable.jpg", event, MediaKind.IMAGE)
        val listed = listEvent(event)
        assertTrue("nothing published", listed.isNotEmpty())

        listed.forEach { exporter.delete(it) }
        assertEquals(emptyList<Any>(), listEvent(event))
    }

    private fun listEvent(eventName: String): List<android.net.Uri> {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(MediaStoreExporter.relativePathFor(eventName)),
            null,
        )?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(0))) }
        }.orEmpty()
    }

    private fun bucketDisplayNameOf(uri: android.net.Uri): String? =
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
            null, null, null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun sampleMetadata(keywordCount: Int) = EmbeddedMetadata(
        title = "Golden hour over the harbour",
        caption = "Warm evening light across moored fishing boats in a calm harbour.",
        keywords = List(keywordCount) { "keyword$it" },
        category = "Nature",
    )

    private fun sourceJpeg(name: String, width: Int = 640, height: Int = 480): File {
        val file = store.newTempFile(name.removeSuffix(".jpg"))
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Noise, not flat colour: a uniform image compresses to almost nothing and
        // would make the downscale assertion meaningless.
        val pixels = IntArray(width * height) { (it * 2654435761u.toInt()) or 0xFF000000.toInt() }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bitmap.recycle()
        return file
    }
}
