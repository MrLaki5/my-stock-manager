package com.mrlaki5.mystockmanager.metadata

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mrlaki5.mystockmanager.metadata.model.StockMetadata
import com.mrlaki5.mystockmanager.storage.AppFileStore
import com.mrlaki5.mystockmanager.storage.ImageEncoder
import com.mrlaki5.mystockmanager.storage.MediaStoreExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The Phase 0 check that only a real device can answer: whether Commons Imaging's
 * IPTC/XMP path actually runs on ART.
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
    private val writer = CommonsImagingMetadataWriter()

    @Test
    fun embedsAndReadsBackOnArt() {
        val source = sourceJpeg("art-source.jpg")
        val destination = File(store.exports, "art-out.jpg")
        val metadata = sampleMetadata(40)

        writer.embed(source, destination, metadata)

        assertTrue("no output written", destination.length() > 0)
        val read = MetadataReader.read(destination)

        assertEquals(metadata.keywords, read.iptcKeywords)
        assertEquals(metadata.keywords, read.xmpSubjects)
        assertEquals(metadata.title, read.iptcTitle)
        assertNotNull("no XMP packet", read.rawXmp)
        assertTrue("verification failed: ${read.matches(metadata)}", read.matches(metadata).isEmpty())
    }

    /**
     * Isolates the single riskiest line: `MetadataReader` instantiates
     * `JpegImagingParameters`, whose superclass declares a `BufferedImageFactory` field.
     * If ART is going to fail, it fails here.
     */
    @Test
    fun instantiatesImagingParametersWithoutAwt() {
        val params = org.apache.commons.imaging.formats.jpeg.JpegImagingParameters()
        assertNotNull(params)
    }

    @Test
    fun downscalesAndBase64EncodesForTheVisionCall() {
        val source = sourceJpeg("encode-source.jpg", width = 4000, height = 3000)
        val encoded = ImageEncoder.toBase64Jpeg(source)
        assertTrue("suspiciously small payload", encoded.length > 1000)
        // A 4000x3000 frame must not be sent at full size.
        assertTrue("payload not downscaled: ${encoded.length}", encoded.length < 900_000)
    }

    @Test
    fun publishesEachEventAsItsOwnPickerAlbum() {
        val source = sourceJpeg("mediastore-source.jpg")
        val destination = File(store.exports, "mediastore-out.jpg")
        writer.embed(source, destination, sampleMetadata(10))

        val exporter = MediaStoreExporter(context)
        val events = listOf("Harbour Shoot", "Autumn Market")
        val uris = events.map { event ->
            exporter.export(destination, "phase0-$event-${System.currentTimeMillis()}.jpg", event)
        }

        uris.forEach { uri ->
            context.contentResolver.openInputStream(uri).use { input ->
                assertTrue("MediaStore copy is empty", (input?.readBytes()?.size ?: 0) > 0)
            }
        }

        // The Photo Picker groups albums by BUCKET_DISPLAY_NAME. Assert what it will
        // actually show, rather than assuming the nesting under StockReady survives.
        val buckets = uris.map { bucketDisplayNameOf(it) }
        events.forEach { event ->
            val expected = MediaStoreExporter.albumNameFor(event)
            assertTrue("expected album '$expected', got $buckets", buckets.contains(expected))
        }

        // Deliberately left in place so the albums can be eyeballed in a real picker.
    }

    @Test
    fun deletesAnEntireEventAlbum() {
        val source = sourceJpeg("delete-source.jpg")
        val destination = File(store.exports, "delete-out.jpg")
        writer.embed(source, destination, sampleMetadata(8))

        val exporter = MediaStoreExporter(context)
        val event = "Disposable Event"
        exporter.export(destination, "disposable-${System.currentTimeMillis()}.jpg", event)
        assertTrue("nothing published", exporter.listEvent(event).isNotEmpty())

        exporter.deleteEvent(event)
        assertEquals(emptyList<Any>(), exporter.listEvent(event))
    }

    private fun bucketDisplayNameOf(uri: android.net.Uri): String? =
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
            null, null, null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun sampleMetadata(keywordCount: Int) = StockMetadata(
        title = "Golden hour over the harbour",
        description = "Warm evening light across moored fishing boats in a calm harbour.",
        keywords = List(keywordCount) { "keyword$it" },
        category = "Nature",
    ).normalized()

    private fun sourceJpeg(name: String, width: Int = 640, height: Int = 480): File {
        val file = File(store.originals, name)
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
