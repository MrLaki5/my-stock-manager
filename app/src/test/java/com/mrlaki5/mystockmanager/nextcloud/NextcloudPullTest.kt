package com.mrlaki5.mystockmanager.nextcloud

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NextcloudPullTest {

    @get:Rule val temp = TemporaryFolder()

    private val store = FakeSyncStore()
    private val drive = FakeDrive()
    private val flags = FakeFlags(
        NextcloudAccount("https://cloud.example.com/".toHttpUrl(), "milan", "pw", "milan", listOf("my-stock-manager")),
    )
    private val album by lazy { FakeAlbum(temp.newFolder()) }
    private val pull by lazy { NextcloudPull(store, flags, { drive }, album) }

    private val beachShot = InspectedImage(
        widthPx = 1600,
        heightPx = 1200,
        capturedOn = "2026-05-23",
        title = "Waves at dusk",
        caption = "Belgrade, Serbia - May 23, 2026: Waves roll onto an empty beach.",
        keywords = listOf("beach", "waves", "dusk"),
        category = "Travel",
    )

    private fun run(): PullOutcome = runBlocking { pull.run() }

    private fun summary(outcome: PullOutcome) = (outcome as PullOutcome.Done).summary

    private fun imageNamed(name: String) = store.images.values.single { it.displayName == name }

    private fun eventNamed(name: String) = store.events.values.single { it.name == name }

    /** A local image already in sync with [key] on the cloud, as a previous upload would leave it. */
    private fun syncedLocal(imageId: Long, folderId: Long, key: String, content: String) {
        store.addImage(imageId, folderId, key.substringAfter('/'))
        album.entries[store.images.getValue(imageId).uri] = content.toByteArray()
        store.syncImages[imageId] = FakeSyncStore.SyncImage(key.substringAfter('/'), syncedVersion = 1, remoteSize = content.length.toLong())
    }

    @Test
    fun `a new phone gets every event and image, with metadata, and nothing to upload`() {
        drive.putFile("Beach/a.jpg", "A")
        drive.putFile("Beach/b.jpg", "BB")
        drive.putFile("Sunset/c.jpg", "CCC")
        album.inspectsAs("A", beachShot)
        album.inspectsAs("BB")
        album.inspectsAs("CCC")
        val progress = mutableListOf<Pair<Int, Int>>()

        val outcome = runBlocking { pull.run { done, total -> progress += done to total } }

        assertEquals(PullSummary(newEvents = 2, added = 3), summary(outcome))
        assertEquals(setOf("Beach", "Sunset"), store.events.values.map { it.name }.toSet())
        assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), progress)

        val a = imageNamed("a.jpg")
        assertEquals("Waves at dusk", a.title)
        assertEquals("Waves roll onto an empty beach.", a.description)
        assertEquals(listOf("beach", "waves", "dusk"), a.keywords)
        assertEquals("Travel", a.category)
        assertEquals("2026-05-23", a.capturedOn)
        assertEquals("Belgrade, Serbia", eventNamed("Beach").location)
        assertNull(imageNamed("b.jpg").title)

        assertNull(runBlocking { store.nextAction() })
    }

    @Test
    fun `pulling again downloads nothing`() {
        drive.putFile("Beach/a.jpg", "A")
        album.inspectsAs("A")
        run()
        drive.calls.clear()

        assertEquals(PullSummary(unchanged = 1), summary(run()))
        assertTrue(drive.calls.none { it.startsWith("GET") })
    }

    @Test
    fun `a file changed on the cloud replaces the phone's copy`() {
        store.addEvent(1, "Beach")
        store.syncFolders[1] = FakeSyncStore.SyncFolder("Beach", "Beach")
        syncedLocal(10, 1, "Beach/a.jpg", "old")
        drive.putFile("Beach/a.jpg", "newer")
        album.inspectsAs("newer", beachShot)

        assertEquals(PullSummary(replaced = 1), summary(run()))
        val image = store.images.getValue(10)
        assertEquals("newer", String(album.entries.getValue(image.uri)))
        assertEquals("Waves at dusk", image.title)
        assertEquals(2L, image.fileVersion)
        assertNull(runBlocking { store.nextAction() })
    }

    @Test
    fun `a phone edit not yet uploaded is replaced by the cloud's version`() {
        store.addEvent(1, "Beach")
        store.syncFolders[1] = FakeSyncStore.SyncFolder("Beach", "Beach")
        syncedLocal(10, 1, "Beach/a.jpg", "same")
        store.rewrite(10)
        drive.putFile("Beach/a.jpg", "same")
        album.inspectsAs("same")

        assertEquals(PullSummary(replaced = 1), summary(run()))
        assertNull(runBlocking { store.nextAction() })
    }

    @Test
    fun `an event that was never synced is joined, not duplicated`() {
        store.addEvent(1, "Beach")
        store.addImage(10, 1, "a.jpg")
        album.entries[store.images.getValue(10).uri] = "A".toByteArray()
        store.addImage(11, 1, "b.jpg")
        album.entries[store.images.getValue(11).uri] = "old".toByteArray()
        drive.putFile("Beach/a.jpg", "A")
        drive.putFile("Beach/b.jpg", "fresh")
        album.inspectsAs("fresh")

        assertEquals(PullSummary(replaced = 1, unchanged = 1), summary(run()))
        assertEquals(1, store.events.size)
        assertEquals("Beach", store.syncFolders.getValue(1).remoteName)
        assertTrue("GET Beach/a.jpg" !in drive.calls)
        assertEquals("fresh", String(album.entries.getValue(store.images.getValue(11).uri)))
    }

    @Test
    fun `paths queued for deletion are not pulled back`() {
        drive.putFile("Beach/a.jpg", "A")
        drive.putFile("Beach/b.jpg", "B")
        drive.putFile("Old/c.jpg", "C")
        album.inspectsAs("B")
        store.deletions += "Beach/a.jpg"
        store.deletions += "Old"

        assertEquals(PullSummary(newEvents = 1, added = 1), summary(run()))
        assertEquals(listOf("b.jpg"), store.images.values.map { it.displayName })
    }

    @Test
    fun `skips subfolders, hidden files and anything that is not an image`() {
        drive.putFile("Beach/a.jpg", "A")
        drive.putFile("Beach/.DS_Store", "x")
        drive.putFile("Beach/notes.txt", "x")
        drive.folders += "Beach/raw"
        drive.folders += ".trash"
        album.inspectsAs("A")

        assertEquals(PullSummary(newEvents = 1, added = 1), summary(run()))
        assertEquals(listOf("GET Beach/a.jpg"), drive.calls.filter { it.startsWith("GET") })
    }

    @Test
    fun `a downloaded file that does not decode is skipped`() {
        drive.putFile("Beach/broken.jpg", "not really a jpeg")

        assertEquals(PullSummary(newEvents = 1, skipped = 1), summary(run()))
        assertTrue(store.images.isEmpty())
    }

    @Test
    fun `a folder whose name another event already holds gets a suffixed event`() {
        store.addEvent(1, "beach")
        store.syncFolders[1] = FakeSyncStore.SyncFolder("Somewhere else", "beach")
        drive.putFile("Beach/a.jpg", "A")
        album.inspectsAs("A")

        run()
        assertEquals("Beach (2)", store.events.values.single { it.id != 1L }.name)
    }

    @Test
    fun `an image whose album file was deleted is restored`() {
        store.addEvent(1, "Beach")
        store.syncFolders[1] = FakeSyncStore.SyncFolder("Beach", "Beach")
        store.addImage(10, 1, "a.jpg")
        store.syncImages[10] = FakeSyncStore.SyncImage("a.jpg", syncedVersion = 1, remoteSize = 1)
        drive.putFile("Beach/a.jpg", "A")
        album.inspectsAs("A")

        assertEquals(PullSummary(replaced = 1), summary(run()))
        val uri = store.images.getValue(10).uri
        assertNotEquals("content://images/10", uri)
        assertEquals("A", String(album.entries.getValue(uri)))
    }

    @Test
    fun `an event spanning places takes the location most captions name`() {
        drive.putFile("Trip/a.jpg", "A")
        drive.putFile("Trip/b.jpg", "B")
        drive.putFile("Trip/c.jpg", "C")
        album.inspectsAs("A", beachShot.copy(caption = "Sremska Mitrovica, Serbia - May 23, 2026: A bridge."))
        album.inspectsAs("B", beachShot.copy(caption = "Šabac, Serbia - May 23, 2026: A fair."))
        album.inspectsAs("C", beachShot.copy(caption = "Šabac, Serbia - May 23, 2026: A parrot."))

        run()
        assertEquals("Šabac, Serbia", eventNamed("Trip").location)
        assertEquals("A bridge.", imageNamed("a.jpg").description)
        assertEquals("Sremska Mitrovica, Serbia", imageNamed("a.jpg").captionPlace)
        assertEquals("Šabac, Serbia", imageNamed("b.jpg").captionPlace)
    }

    @Test
    fun `keeps a location the event already has`() {
        store.addEvent(1, "Trip", location = "Novi Sad")
        drive.putFile("Trip/a.jpg", "A")
        album.inspectsAs("A", beachShot)

        run()
        assertEquals("Novi Sad", store.events.getValue(1).location)
    }

    @Test
    fun `strips a known location from an undated caption`() {
        store.addEvent(1, "Vevey", location = "Vevey, Switzerland")
        drive.putFile("Vevey/a.jpg", "A")
        album.inspectsAs("A", beachShot.copy(capturedOn = null, caption = "Vevey, Switzerland: The lake at noon."))

        run()
        assertEquals("The lake at noon.", imageNamed("a.jpg").description)
    }

    @Test
    fun `rejected credentials stop the pull and later syncs`() {
        drive.putFile("Beach/a.jpg", "A")
        drive.intercept = { DavResult.AuthRejected("no") }

        assertTrue(run() is PullOutcome.Failed)
        assertTrue(flags.authRejected)
    }

    @Test
    fun `a dropped connection resumes where it stopped`() {
        drive.putFile("Beach/a.jpg", "A")
        drive.putFile("Beach/b.jpg", "B")
        album.inspectsAs("A")
        album.inspectsAs("B")
        var dropped = false
        drive.intercept = { call ->
            if (call == "GET Beach/b.jpg" && !dropped) {
                dropped = true
                DavResult.Transient("reset")
            } else {
                null
            }
        }

        assertTrue(run() is PullOutcome.Retry)
        drive.calls.clear()
        assertEquals(PullSummary(added = 1, unchanged = 1), summary(run()))
        assertEquals(listOf("GET Beach/b.jpg"), drive.calls.filter { it.startsWith("GET") })
    }

    @Test
    fun `summaries read naturally`() {
        assertEquals("Already up to date with NextCloud", PullSummary(unchanged = 4).describe())
        assertEquals("Added 33 images into 4 new events", PullSummary(newEvents = 4, added = 33).describe())
        assertEquals("Added 1 image", PullSummary(added = 1).describe())
        assertEquals(
            "Added 2 images · replaced 1 with the cloud's version · skipped 1 file that could not be used",
            PullSummary(added = 2, replaced = 1, skipped = 1).describe(),
        )
        assertEquals("Replaced 3 with the cloud's version", PullSummary(replaced = 3).describe())
    }
}
