package com.mrlaki5.mystockmanager.metadata

import com.mrlaki5.mystockmanager.metadata.model.EditorialTitle
import com.mrlaki5.mystockmanager.metadata.model.IPTC_OBJECT_NAME_MAX
import com.mrlaki5.mystockmanager.metadata.model.StockMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorialTitleTest {

    private val caption =
        "Large Serbian national flags wave above crowds marching toward Slavija Square " +
            "during a student-led anti-government rally demanding early parliamentary elections."

    @Test
    fun `builds location, date and caption in the agency format`() {
        assertEquals(
            "Belgrade, Serbia - May 23, 2026: $caption",
            EditorialTitle.build("Belgrade, Serbia", "2026-05-23", caption),
        )
    }

    @Test
    fun `drops the location and its separator when none was given`() {
        assertEquals(
            "May 23, 2026: $caption",
            EditorialTitle.build(null, "2026-05-23", caption),
        )
        assertEquals(
            "May 23, 2026: $caption",
            EditorialTitle.build("   ", "2026-05-23", caption),
        )
    }

    @Test
    fun `omits the date rather than guessing when EXIF carried none`() {
        assertEquals(
            "Belgrade, Serbia: $caption",
            EditorialTitle.build("Belgrade, Serbia", null, caption),
        )
    }

    @Test
    fun `falls back to the bare caption when neither lead is known`() {
        assertEquals(caption, EditorialTitle.build(null, null, caption))
    }

    @Test
    fun `treats an unparseable stored date as absent`() {
        assertEquals(
            "Belgrade, Serbia: $caption",
            EditorialTitle.build("Belgrade, Serbia", "not-a-date", caption),
        )
    }

    @Test
    fun `formats the month in English regardless of the device locale`() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("sr-RS"))
            assertTrue(
                EditorialTitle.build(null, "2026-05-23", "x").startsWith("May 23, 2026:"),
            )
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun `object name trims the caption to the IPTC limit on a word boundary`() {
        val metadata = StockMetadata(
            title = EditorialTitle.build("Belgrade, Serbia", "2026-05-23", caption),
            description = caption,
            keywords = listOf("belgrade"),
        ).normalized()

        assertTrue("caption should exceed the IIM limit", metadata.title.length > IPTC_OBJECT_NAME_MAX)
        assertTrue(metadata.objectName.length <= IPTC_OBJECT_NAME_MAX)
        assertTrue(
            "cut mid-word: '${metadata.objectName}'",
            metadata.title.startsWith(metadata.objectName),
        )
        assertTrue(
            "should not end on a dangling separator: '${metadata.objectName}'",
            metadata.objectName.last().isLetterOrDigit(),
        )
    }

    @Test
    fun `a short title is its own object name`() {
        val metadata = StockMetadata("Harbour at dusk", "d", listOf("k")).normalized()
        assertEquals(metadata.title, metadata.objectName)
    }
}
