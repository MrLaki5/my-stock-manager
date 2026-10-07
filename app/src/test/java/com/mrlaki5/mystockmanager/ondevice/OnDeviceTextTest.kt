package com.mrlaki5.mystockmanager.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceTextTest {

    private val tags = KeywordTagger.Tags(
        ranked = listOf("dog", "pet", "canine", "dogs", "puppy", "indoor", "cone", "dog toy", "dog bed", "dog food"),
        scores = mapOf(
            "dog" to 9f, "pet" to 7f, "canine" to 6f, "dogs" to 5f, "puppy" to 4f, "indoor" to 3f, "cone" to 2.5f,
            "plastic cone" to 2f, "plastic" to 1.6f, "shirt" to 0.2f, "white" to -1f,
            "dog toy" to 2f, "dog bed" to 2f, "dog food" to 2f,
        ),
        category = "Animals/Wildlife",
        vocabulary = setOf(
            "dog", "pet", "canine", "dogs", "puppy", "indoor", "cone", "plastic", "white", "shirt", "plastic cone",
            "dog toy", "dog bed", "dog food",
        ),
    )

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
    fun `keywords put hint first, then caption words the tagger agrees with, then its ranking`() {
        val keywords = OnDeviceText.keywords("Belgrade, Serbia", "A dog wearing a white shirt and a plastic cone.", "Dog in cone", tags)
        assertEquals(listOf("belgrade", "serbia", "dog", "cone", "plastic cone", "plastic"), keywords.take(6))
        assertTrue("white" !in keywords && "shirt" !in keywords)
        assertTrue("dogs" !in keywords)
        assertTrue("the" !in keywords && "wearing" !in keywords)
    }

    @Test
    fun `at most two breeds are kept`() {
        val breeds = listOf("irish setter", "sussex spaniel", "poodle", "dachshund")
        val dogTags = KeywordTagger.Tags(
            ranked = breeds + listOf("grass", "lawn"),
            scores = (breeds + listOf("grass", "lawn")).associateWith { 5f },
            category = "Animals/Wildlife",
            vocabulary = (breeds + listOf("grass", "lawn")).toSet(),
            breeds = breeds.toSet(),
        )
        val keywords = OnDeviceText.keywords(null, "A dog lies on the grass.", "Dog on grass", dogTags)
        assertEquals(listOf("grass", "irish setter", "sussex spaniel", "lawn"), keywords)
    }

    @Test
    fun `one word cannot fill the list`() {
        val keywords = OnDeviceText.keywords(null, "A dog.", "Dog", tags)
        assertEquals(3, keywords.count { "dog" in it.split(' ') })
    }
}
