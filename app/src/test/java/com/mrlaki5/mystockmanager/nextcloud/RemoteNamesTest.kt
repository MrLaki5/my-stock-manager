package com.mrlaki5.mystockmanager.nextcloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteNamesTest {

    @Test
    fun `replaces characters that NextCloud and desktop clients reject`() {
        assertEquals("Beach - day-1", RemoteNames.folderBase("Beach / day:1"))
        assertEquals("a-b-c", RemoteNames.folderBase("a*b?c"))
    }

    @Test
    fun `trims trailing dots and spaces, which Windows clients cannot sync`() {
        assertEquals("Trip", RemoteNames.folderBase("Trip... "))
    }

    @Test
    fun `never produces a dot path that would climb out of the sync folder`() {
        assertEquals("Unnamed event", RemoteNames.folderBase("."))
        assertEquals("Unnamed event", RemoteNames.folderBase(".."))
        assertEquals("Unnamed event", RemoteNames.folderBase("   "))
    }

    @Test
    fun `normalizes to NFC so a decomposed name matches what NextCloud lists`() {
        assertEquals("Café", RemoteNames.folderBase("Café"))
    }

    @Test
    fun `caps folder names at the album limit`() {
        assertEquals(64, RemoteNames.folderBase("a".repeat(100)).length)
    }

    @Test
    fun `sidesteps names NextCloud refuses outright`() {
        assertEquals("_.htaccess", RemoteNames.folderBase(".htaccess"))
        assertEquals("Shoot.part_", RemoteNames.folderBase("Shoot.part"))
    }

    @Test
    fun `resolves collisions case-insensitively with a numbered suffix`() {
        assertEquals("Beach", RemoteNames.folderName("Beach", listOf("Sunset")))
        assertEquals("Beach (2)", RemoteNames.folderName("Beach", listOf("beach")))
        assertEquals("Beach (3)", RemoteNames.folderName("Beach", listOf("Beach", "BEACH (2)")))
    }

    @Test
    fun `recognises a suffixed folder as still belonging to its event`() {
        assertTrue(RemoteNames.matchesBase("Beach", "Beach"))
        assertTrue(RemoteNames.matchesBase("Beach (2)", "Beach"))
        assertTrue(RemoteNames.matchesBase("a+b (3)", "a+b"))
        assertFalse(RemoteNames.matchesBase("Beach (2) x", "Beach"))
        assertFalse(RemoteNames.matchesBase("Beach2", "Beach"))
        assertFalse(RemoteNames.matchesBase("Sunset", "Beach"))
    }

    @Test
    fun `puts the id suffix for a clashing file before its extension`() {
        assertEquals("IMG_1.jpg", RemoteNames.fileName("IMG_1.jpg", 42, emptyList()))
        assertEquals("IMG_1 (42).jpg", RemoteNames.fileName("IMG_1.jpg", 42, listOf("img_1.JPG")))
        assertEquals("README (7)", RemoteNames.withIdSuffix("README", 7))
        assertEquals(".hidden (7)", RemoteNames.withIdSuffix(".hidden", 7))
    }

    @Test
    fun `keeps the extension when shortening a very long file name`() {
        val name = RemoteNames.fileBase("x".repeat(300) + ".jpeg")
        assertEquals(200, name.length)
        assertTrue(name.endsWith(".jpeg"))
    }

    @Test
    fun `sanitizes file names without the event name's length cap`() {
        assertEquals("a-b.jpg", RemoteNames.fileBase("a/b.jpg"))
        assertEquals("image.jpg", RemoteNames.fileBase(" "))
    }
}
