package com.mrlaki5.mystockmanager.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnDeviceTextTest {

    @Test
    fun `sentence keeps the first sentence without labels or quotes`() {
        assertEquals("A dog wears a cone.", OnDeviceText.sentence("Description: \"A dog wears a cone. It looks sad.\""))
        assertEquals("A dog wears a cone.", OnDeviceText.sentence("A dog wears a cone"))
    }

    @Test
    fun `title is cleaned, or cut from the description when unusable`() {
        assertEquals("Dog in cone", OnDeviceText.title("Title: \"Dog in cone.\"", "A dog wears a cone.", 64))
        assertEquals("Dog wearing a white shirt", OnDeviceText.title("", "A dog wearing a white shirt.", 64))
        assertEquals("Dog wearing a white", OnDeviceText.title("", "A dog wearing a white shirt.", 20))
    }

    @Test
    fun `keyword cleans one answer`() {
        assertEquals("seagull", OnDeviceText.keyword("Seagull.", emptyList()))
        assertEquals("parked cars", OnDeviceText.keyword("The main subject is parked cars.", emptyList()))
        assertEquals("bird of prey", OnDeviceText.keyword("A bird of prey", emptyList()))
        assertEquals("close-up", OnDeviceText.keyword("Close-up.", emptyList()))
    }

    @Test
    fun `keyword rejects non-answers, long phrases, repeats and garbled loops`() {
        assertNull(OnDeviceText.keyword("No action.", emptyList()))
        assertNull(OnDeviceText.keyword("None", emptyList()))
        assertNull(OnDeviceText.keyword("Seagull's wings are spread wide.", emptyList()))
        assertNull(OnDeviceText.keyword("Waves", listOf("wave")))
        assertNull(OnDeviceText.keyword("Screwsilhouette", listOf("screw")))
        assertNull(OnDeviceText.keyword("Book iz", listOf("book")))
        assertEquals("waves crashing", OnDeviceText.keyword("Waves crashing", listOf("waves")))
    }

    @Test
    fun `category accepts only Shutterstock's names`() {
        assertEquals("Transportation", OnDeviceText.category("Transportation."))
        assertEquals("Animals/Wildlife", OnDeviceText.category("animals"))
        assertEquals("Food and drink", OnDeviceText.category("Food"))
        assertEquals("Parks/Outdoor", OnDeviceText.category("Parks/Outdoor"))
        assertNull(OnDeviceText.category("Landscape"))
        assertNull(OnDeviceText.category(""))
    }

    @Test
    fun `keywords put hint terms first and drop duplicates`() {
        val keywords = OnDeviceText.keywords("Belgrade, Serbia", listOf("dog", "cone", "belgrade", "white"))
        assertEquals(listOf("belgrade", "serbia", "dog", "cone", "white"), keywords)
        assertEquals(listOf("dog", "grass"), OnDeviceText.keywords(null, listOf("dog", "grass", "dogs")))
    }
}
