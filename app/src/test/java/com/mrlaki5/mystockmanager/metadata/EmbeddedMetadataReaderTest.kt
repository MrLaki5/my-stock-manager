package com.mrlaki5.mystockmanager.metadata

import com.mrlaki5.mystockmanager.metadata.model.MAX_KEYWORDS
import com.mrlaki5.mystockmanager.metadata.model.ParsedMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class EmbeddedMetadataReaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val embedded = EmbeddedMetadata(
        title = "Waves at dusk",
        caption = "Belgrade, Serbia - May 23, 2026: Waves roll onto an empty beach.",
        keywords = listOf("beach", "waves", "Šabac"),
        category = "Parks/Outdoor",
    )

    private fun jpeg(): File {
        val file = File(temp.root, "plain-${System.nanoTime()}.jpg")
        ImageIO.write(BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB), "jpg", file)
        return file
    }

    private fun embeddedJpeg(metadata: EmbeddedMetadata = embedded, iptc: Boolean = true, xmp: Boolean = true): File =
        File(temp.root, "embedded-${System.nanoTime()}.jpg").also { EmbeddedFixture.embed(jpeg(), it, metadata, iptc, xmp) }

    @Test
    fun `reads what older versions of the app embedded`() {
        assertEquals(embedded, EmbeddedMetadataReader.read(embeddedJpeg()))
    }

    @Test
    fun `falls back to XMP when a tool wrote no IPTC`() {
        assertEquals(embedded, EmbeddedMetadataReader.read(embeddedJpeg(iptc = false)))
    }

    @Test
    fun `a photo without metadata, or a file that is not a JPEG, yields nothing`() {
        assertNull(EmbeddedMetadataReader.read(jpeg()))
        assertNull(EmbeddedMetadataReader.read(File(temp.root, "notes.jpg").apply { writeText("not an image") }))
    }

    @Test
    fun `parsing splits off the caption lead and clamps to agency limits`() {
        val read = EmbeddedMetadataReader.read(embeddedJpeg(embedded.copy(keywords = List(60) { "kw$it" })))!!
        val parsed = ParsedMetadata.of(read.title, read.caption, read.keywords, read.category, "2026-05-23", null)

        assertEquals("Waves roll onto an empty beach.", parsed.description)
        assertEquals("Belgrade, Serbia", parsed.captionPlace)
        assertEquals(MAX_KEYWORDS, parsed.keywords.size)
    }
}
