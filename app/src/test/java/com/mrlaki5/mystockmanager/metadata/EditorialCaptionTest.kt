package com.mrlaki5.mystockmanager.metadata

import com.mrlaki5.mystockmanager.metadata.model.EditorialCaption
import com.mrlaki5.mystockmanager.metadata.model.IPTC_CAPTION_MAX
import com.mrlaki5.mystockmanager.metadata.model.IPTC_OBJECT_NAME_MAX
import com.mrlaki5.mystockmanager.metadata.model.StockMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorialCaptionTest {

    private val body =
        "Large Serbian national flags wave above crowds marching toward Slavija Square " +
            "during a student-led anti-government rally demanding early parliamentary elections."

    @Test
    fun `builds location, date and caption in the agency format`() {
        assertEquals(
            "Belgrade, Serbia - May 23, 2026: $body",
            EditorialCaption.build("Belgrade, Serbia", "2026-05-23", body),
        )
    }

    @Test
    fun `drops the location and its separator when none was given`() {
        assertEquals(
            "May 23, 2026: $body",
            EditorialCaption.build(null, "2026-05-23", body),
        )
        assertEquals(
            "May 23, 2026: $body",
            EditorialCaption.build("   ", "2026-05-23", body),
        )
    }

    @Test
    fun `omits the date rather than guessing when EXIF carried none`() {
        assertEquals(
            "Belgrade, Serbia: $body",
            EditorialCaption.build("Belgrade, Serbia", null, body),
        )
    }

    @Test
    fun `falls back to the bare body when neither lead is known`() {
        assertEquals(body, EditorialCaption.build(null, null, body))
    }

    @Test
    fun `treats an unparseable stored date as absent`() {
        assertEquals(
            "Belgrade, Serbia: $body",
            EditorialCaption.build("Belgrade, Serbia", "not-a-date", body),
        )
    }

    @Test
    fun `formats the month in English regardless of the device locale`() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("sr-RS"))
            assertTrue(
                EditorialCaption.build(null, "2026-05-23", "x").startsWith("May 23, 2026:"),
            )
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun `a caption sits far inside the IPTC description limit`() {
        val caption = EditorialCaption.build("Belgrade, Serbia", "2026-05-23", body)
        val metadata = StockMetadata("Short title", caption, listOf("belgrade")).normalized()

        // The description is the roomy field; nothing is clamped away from a caption.
        assertEquals(caption, metadata.description)
        assertTrue(caption.length < IPTC_CAPTION_MAX)
    }

    @Test
    fun `the title is untouched by captioning and keeps its own limit`() {
        val long = "A marketable title that runs well past the sixty-four character cap " +
            "the IPTC object name field imposes on it"
        val metadata = StockMetadata(long, "d", listOf("k")).normalized()

        assertEquals(IPTC_OBJECT_NAME_MAX, metadata.title.length)
        assertTrue(long.startsWith(metadata.title))
    }
}
