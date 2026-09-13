package com.mrlaki5.mystockmanager.metadata

import com.mrlaki5.mystockmanager.metadata.model.MAX_KEYWORDS
import com.mrlaki5.mystockmanager.metadata.model.StockMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Proves the Phase 0 metadata assumption on the JVM: that XMPCore + Commons Imaging
 * actually produce IPTC and XMP that read back identically.
 *
 * This runs on a desktop JVM, so it validates the *logic* but not ART behavior — the
 * on-device run of the spike app is still what settles the java.awt question for debug
 * builds. (Release builds are already settled: R8 tree-shakes the awt path out entirely.)
 */
class MetadataRoundTripTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val writer = CommonsImagingMetadataWriter()

    @Test
    fun `writes and reads back IPTC keywords, title and description`() {
        val source = sourceJpeg()
        val destination = File(temp.root, "out.jpg")
        val metadata = sampleMetadata(keywordCount = 30)

        writer.embed(source, destination, metadata)
        val read = MetadataReader.read(destination)

        assertEquals(metadata.keywords, read.iptcKeywords)
        assertEquals(metadata.title, read.iptcTitle)
        assertEquals(metadata.description, read.iptcDescription)
        assertTrue("verification reported: ${read.matches(metadata)}", read.matches(metadata).isEmpty())
    }

    @Test
    fun `writes dc subject as an XMP bag that parses back in order`() {
        val source = sourceJpeg()
        val destination = File(temp.root, "out.jpg")
        val metadata = sampleMetadata(keywordCount = 12)

        writer.embed(source, destination, metadata)
        val read = MetadataReader.read(destination)

        assertNotNull("no XMP packet was written", read.rawXmp)
        assertTrue("XMP is not a dc:subject bag", read.rawXmp!!.contains("dc:subject"))
        assertEquals(metadata.keywords, read.xmpSubjects)
        assertEquals(metadata.title, read.xmpTitle)
    }

    @Test
    fun `survives non-ASCII keywords via the UTF-8 IPTC marker`() {
        val source = sourceJpeg()
        val destination = File(temp.root, "out.jpg")
        val metadata = StockMetadata(
            title = "Café at dusk",
            description = "A quiet café in the Øresund evening light.",
            keywords = listOf("café", "Øresund", "naïve", "日本", "ελλάδα"),
        ).normalized()

        writer.embed(source, destination, metadata)
        val read = MetadataReader.read(destination)

        assertEquals(metadata.keywords, read.iptcKeywords)
        assertEquals(metadata.keywords, read.xmpSubjects)
    }

    @Test
    fun `leaves the source file untouched`() {
        val source = sourceJpeg()
        val before = source.readBytes()

        writer.embed(source, File(temp.root, "out.jpg"), sampleMetadata(keywordCount = 10))

        assertTrue("source was modified in place", before.contentEquals(source.readBytes()))
    }

    @Test
    fun `clamps over-long keyword lists and drops case-insensitive duplicates`() {
        val raw = StockMetadata(
            title = "t",
            description = "d",
            keywords = List(80) { "kw$it" } + listOf("KW1", "kw1", "  ", "kw2"),
        )

        val normalized = raw.normalized()

        assertEquals(MAX_KEYWORDS, normalized.keywords.size)
        assertEquals(normalized.keywords.distinctBy { it.lowercase() }.size, normalized.keywords.size)
        assertTrue(normalized.keywords.none { it.isBlank() })
    }

    @Test
    fun `re-embedding starts from the clean original rather than accreting segments`() {
        val source = sourceJpeg()
        val destination = File(temp.root, "out.jpg")

        writer.embed(source, destination, sampleMetadata(keywordCount = 8))
        // Re-generation overwrites the export from the pristine original, which is the
        // whole reason originals/ and exports/ are separate directories.
        writer.embed(source, destination, sampleMetadata(keywordCount = 20))

        val read = MetadataReader.read(destination)
        assertEquals(20, read.iptcKeywords.size)
        assertEquals(20, read.xmpSubjects.size)
    }

    private fun sampleMetadata(keywordCount: Int) = StockMetadata(
        title = "Golden hour over the harbour",
        description = "Warm evening light across moored fishing boats in a calm harbour.",
        keywords = List(keywordCount) { "keyword$it" },
        category = "Nature",
    ).normalized()

    private fun sourceJpeg(): File {
        val file = File(temp.root, "source.jpg")
        val image = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 64) for (y in 0 until 48) image.setRGB(x, y, (x * 4 shl 16) or (y * 5 shl 8) or 0x40)
        ImageIO.write(image, "jpg", file)
        return file
    }
}
