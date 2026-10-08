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
    fun `sentence drops talk about the photo itself`() {
        assertEquals("A dirt path through a forest.", OnDeviceText.sentence("The image shows a dirt path through a forest."))
        assertEquals("A bustling cityscape at night.", OnDeviceText.sentence("The image captures a bustling cityscape at night."))
        assertEquals("The car is a vintage Plymouth.", OnDeviceText.sentence("The car in the image is a vintage Plymouth."))
        assertEquals("The sky is blue.", OnDeviceText.sentence("The sky is blue in the photo."))
        assertEquals("Two gulls fly over the sea.", OnDeviceText.sentence("In this photo, two gulls fly over the sea."))
        assertEquals("A black and white photo of a pier.", OnDeviceText.sentence("A black and white photo of a pier."))
        assertEquals("The Statue of Liberty under a blue sky.", OnDeviceText.sentence("A photo of the Statue of Liberty under a blue sky."))
    }

    @Test
    fun `sentence drops filler praise and fixes the article`() {
        assertEquals("A sunset over a mountain with clouds and a lake.", OnDeviceText.sentence("A stunning sunset over a mountain with clouds and a lake."))
        assertEquals("An ocean view at dusk.", OnDeviceText.sentence("A serene ocean view at dusk."))
        assertEquals("A landscape with rolling hills.", OnDeviceText.sentence("An idyllic landscape with rolling hills."))
        assertEquals("Two hikers stand below mountains.", OnDeviceText.sentence("Two hikers stand below majestic mountains."))
        assertEquals("A winding road with a sunset behind it.", OnDeviceText.sentence("A winding road with a beautiful sunset behind it."))
        assertEquals("The lake is calm.", OnDeviceText.sentence("The lake is calm."))
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
