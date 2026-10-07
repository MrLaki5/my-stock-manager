package com.mrlaki5.mystockmanager.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetadataParserTest {

    private fun payload(place: String, secondary: String) = """
        {"title":"Dog","description":"A dog.","keywords":["dog","pet","animal","black","indoor","cone","collar"],
         "shutterstock_category":"Animals/Wildlife","secondary_category":$secondary,"place":$place}
    """.trimIndent()

    @Test
    fun `the string null is no place`() {
        assertNull(MetadataParser.parsePlace(payload(place = "\"null\"", secondary = "null")))
        assertNull(MetadataParser.parse(payload(place = "null", secondary = "\"NULL\""))!!.secondaryCategory)
    }

    @Test
    fun `a real place is kept`() {
        assertEquals("Kotor, Montenegro", MetadataParser.parsePlace(payload(place = "\"Kotor, Montenegro\"", secondary = "null")))
    }
}
