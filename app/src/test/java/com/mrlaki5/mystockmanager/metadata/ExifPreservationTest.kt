package com.mrlaki5.mystockmanager.metadata

import com.mrlaki5.mystockmanager.metadata.model.StockMetadata
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The capture date in the editorial caption comes from EXIF, and generation rewrites the
 * album file in place. If embedding metadata dropped the EXIF segment, every generated
 * image would lose the date it needs — silently, and visibly only much later. So the
 * segment is pinned here.
 */
class ExifPreservationTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `embedding preserves the EXIF segment byte for byte`() {
        val fixture = checkNotNull(javaClass.classLoader?.getResourceAsStream("exif-dated.jpg")) {
            "exif-dated.jpg is missing from src/test/resources"
        }
        val source = File(temp.root, "in.jpg").apply { writeBytes(fixture.use { it.readBytes() }) }
        val destination = File(temp.root, "out.jpg")

        val before = exifSegment(source.readBytes())
        assertNotNull("fixture has no EXIF to begin with", before)

        CommonsImagingMetadataWriter().embed(
            source,
            destination,
            StockMetadata("t", "d", listOf("a", "b")).normalized(),
        )

        val after = exifSegment(destination.readBytes())
        assertNotNull("EXIF segment was dropped by embedding", after)
        assertArrayEquals(before, after)
    }

    /** Returns the APP1 payload carrying the EXIF identifier, if the file has one. */
    private fun exifSegment(data: ByteArray): ByteArray? {
        var i = 2
        while (i < data.size - 1) {
            if (data[i] != 0xFF.toByte()) return null
            val marker = data[i + 1].toInt() and 0xFF
            if (marker == 0xD8) {
                i += 2
                continue
            }
            if (marker == 0xDA || marker == 0xD9) return null

            val length = ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
            val payload = data.copyOfRange(i + 4, i + 2 + length)
            if (marker == 0xE1 &&
                payload.size >= 6 &&
                String(payload, 0, 4, Charsets.US_ASCII) == "Exif"
            ) {
                return payload
            }
            i += 2 + length
        }
        return null
    }
}
