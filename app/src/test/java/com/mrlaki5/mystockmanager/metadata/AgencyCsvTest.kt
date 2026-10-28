package com.mrlaki5.mystockmanager.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgencyCsvTest {

    private val row = CsvRow(
        fileName = "IMG_0001.jpg",
        title = "Waves at dusk, Belgrade",
        description = "Belgrade, Serbia - May 23, 2026: A \"quiet\" beach,\nat dusk.",
        keywords = listOf("beach", "waves", "sea shore"),
        category = "Parks/Outdoor",
    )

    @Test
    fun `carries both agencies' columns under one header`() {
        val text = String(AgencyCsv.write(listOf(row)))

        assertTrue(text.startsWith("Filename,Title,Description,Keywords,Category,Categories\r\n"))
        assertTrue(text.contains("\"beach, waves, sea shore\",11,Parks/Outdoor\r\n"))
    }

    @Test
    fun `reads back what it wrote, commas, quotes and line breaks included`() {
        assertEquals(mapOf(row.fileName to row), AgencyCsv.read(AgencyCsv.write(listOf(row))))
    }

    @Test
    fun `an uncategorised image leaves both category columns empty`() {
        val text = String(AgencyCsv.write(listOf(row.copy(category = null))))

        assertTrue(text.endsWith(",,\r\n"))
        assertNull(AgencyCsv.read(text.toByteArray()).getValue(row.fileName).category)
    }

    @Test
    fun `reads a CSV saved by a spreadsheet`() {
        val text = "﻿filename,title,keywords,categories,description\n" +
            "a.jpg,Waves,\"beach,waves\",\"Nature,Parks/Outdoor\",A beach.\n\n"

        val read = AgencyCsv.read(text.toByteArray()).getValue("a.jpg")
        assertEquals(listOf("beach", "waves"), read.keywords)
        assertEquals("Nature", read.category)
        assertEquals("A beach.", read.description)
    }

    @Test
    fun `a file that is not the app's CSV yields no rows`() {
        assertEquals(emptyMap<String, CsvRow>(), AgencyCsv.read("Name,Notes\na.jpg,hello\n".toByteArray()))
        assertEquals(emptyMap<String, CsvRow>(), AgencyCsv.read(ByteArray(0)))
    }

    @Test
    fun `every Shutterstock category the app offers but Miscellaneous and Vintage maps to an Adobe one`() {
        val unmapped = com.mrlaki5.mystockmanager.openai.SHUTTERSTOCK_CATEGORIES.filter { AgencyCsv.adobeCategory(it) == null }
        assertEquals(listOf("Miscellaneous", "Vintage"), unmapped)
    }
}
