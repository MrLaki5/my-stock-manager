package com.mrlaki5.mystockmanager.storage

import com.mrlaki5.mystockmanager.data.db.dao.CsvImageRow
import com.mrlaki5.mystockmanager.data.db.dao.EventCsvRow
import com.mrlaki5.mystockmanager.metadata.AgencyCsv
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneCsvTest {

    private fun row(event: String, id: Long, fileName: String, keywords: List<String> = listOf("beach")) = EventCsvRow(
        event,
        "content://media/external_primary/images/media/$id",
        CsvImageRow(id, fileName, 1, "Title $id", "Waves.", keywords, null, "Belgrade, Serbia", "2026-05-23"),
    )

    @Test
    fun `one CSV per event, in Documents next to the album's folder name, rows named as the album names them`() {
        val files = PhoneCsv.filesFor(
            listOf(row("Beach", 1, "IMG_1.jpg"), row("Beach", 2, "IMG_2.jpg"), row("Slavija 10.2026", 3, "IMG_3.jpg")),
            names = emptyMap(),
        )

        assertEquals(
            setOf("Documents/StockReady/StockReady - Beach/", "Documents/StockReady/StockReady - Slavija 10.2026/"),
            files.keys,
        )
        val beach = AgencyCsv.read(files.getValue("Documents/StockReady/StockReady - Beach/"))
        assertEquals(setOf("IMG_1.jpg", "IMG_2.jpg"), beach.keys)
        assertEquals("Belgrade, Serbia - May 23, 2026: Waves.", beach.getValue("IMG_1.jpg").description)
    }

    @Test
    fun `an event with no complete row gets no CSV`() {
        assertEquals(emptySet<String>(), PhoneCsv.filesFor(listOf(row("Beach", 1, "IMG_1.jpg", keywords = emptyList())), emptyMap()).keys)
    }

    @Test
    fun `rows use the name MediaStore gave the file, not the one the app asked for`() {
        val files = PhoneCsv.filesFor(listOf(row("Beach", 1, "Belgrade: \"rally\".JPG")), mapOf(1L to "Belgrade_ _rally_.JPG"))

        assertEquals(setOf("Belgrade_ _rally_.JPG"), AgencyCsv.read(files.values.single()).keys)
    }
}
