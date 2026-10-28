package com.mrlaki5.mystockmanager.nextcloud

import com.mrlaki5.mystockmanager.metadata.AgencyCsv
import com.mrlaki5.mystockmanager.metadata.CsvRow
import com.mrlaki5.mystockmanager.metadata.EmbeddedMetadata
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

    private val beachShot = InspectedImage(widthPx = 1600, heightPx = 1200, capturedOn = "2026-05-23", embedded = null)

    private val olderVersionShot = beachShot.copy(
        embedded = EmbeddedMetadata(
            title = "Old waves",
            caption = "Belgrade, Serbia - May 23, 2026: Waves from before the CSV.",
            keywords = listOf("beach", "old"),
            category = "Nature",
        ),
    )

    private fun row(fileName: String, caption: String = "Belgrade, Serbia - May 23, 2026: Waves roll onto an empty beach.") =
        CsvRow(fileName, "Waves at dusk", caption, listOf("beach", "waves", "dusk"), "Parks/Outdoor")

    private fun putCsv(folder: String, vararg rows: CsvRow) {
        drive.putFile("$folder/${AgencyCsv.FILE_NAME}", String(AgencyCsv.write(rows.toList())))
    }

    private fun run(): PullOutcome = runBlocking { pull.run() }

    private fun summary(outcome: PullOutcome) = (outcome as PullOutcome.Done).summary

    private fun imageNamed(name: String) = store.images.values.single { it.displayName == name }

    private fun eventNamed(name: String) = store.events.values.single { it.name == name }

    /** A local image already in sync with [key] on the cloud, as a previous upload would leave it. */
    private fun syncedLocal(imageId: Long, folderId: Long, key: String, content: String) {
        store.addImage(imageId, folderId, key.substringAfter('/'))
        album.entries[store.images.getValue(imageId).uri] = content.toByteArray()
        store.syncImages[imageId] = FakeSyncStore.SyncImage(
            key.substringAfter('/'), syncedVersion = 1, remoteSize = content.length.toLong(), csvVersion = 1,
        )
    }

    @Test
    fun `a new phone gets every event and image, with metadata, and nothing to upload`() {
        drive.putFile("Beach/a.jpg", "A")
        drive.putFile("Beach/b.jpg", "BB")
        drive.putFile("Sunset/c.jpg", "CCC")
        putCsv("Beach", row("a.jpg"))
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
        assertEquals("Parks/Outdoor", a.category)
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
        putCsv("Beach", row("a.jpg"))
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
        putCsv(
            "Trip",
            row("a.jpg", "Sremska Mitrovica, Serbia - May 23, 2026: A bridge."),
            row("b.jpg", "Šabac, Serbia - May 23, 2026: A fair."),
            row("c.jpg", "Šabac, Serbia - May 23, 2026: A parrot."),
        )
        listOf("A", "B", "C").forEach { album.inspectsAs(it, beachShot) }

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
        putCsv("Trip", row("a.jpg"))
        album.inspectsAs("A", beachShot)

        run()
        assertEquals("Novi Sad", store.events.getValue(1).location)
    }

    @Test
    fun `strips a known location from an undated caption`() {
        store.addEvent(1, "Vevey", location = "Vevey, Switzerland")
        drive.putFile("Vevey/a.jpg", "A")
        putCsv("Vevey", row("a.jpg", "Vevey, Switzerland: The lake at noon."))
        album.inspectsAs("A", beachShot.copy(capturedOn = null))

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
    fun `a CSV changed on the cloud updates metadata without downloading the image`() {
        store.addEvent(1, "Beach")
        store.syncFolders[1] = FakeSyncStore.SyncFolder("Beach", "Beach", csvSyncedVersion = 1)
        syncedLocal(10, 1, "Beach/a.jpg", "same")
        store.describe(10, "Old title", listOf("old"))
        store.syncImages.getValue(10).csvVersion = store.images.getValue(10).metadataVersion
        drive.putFile("Beach/a.jpg", "same")
        putCsv("Beach", row("a.jpg"))

        assertEquals(PullSummary(updated = 1), summary(run()))
        assertEquals("Waves at dusk", store.images.getValue(10).title)
        assertEquals(listOf("GET Beach/${AgencyCsv.FILE_NAME}"), drive.calls.filter { it.startsWith("GET") })
        assertNull(runBlocking { store.nextAction() })

        drive.calls.clear()
        assertEquals(PullSummary(unchanged = 1), summary(run()))
    }

    @Test
    fun `a folder without a CSV keeps the phone's metadata, even for a replaced file`() {
        store.addEvent(1, "Beach")
        store.syncFolders[1] = FakeSyncStore.SyncFolder("Beach", "Beach")
        syncedLocal(10, 1, "Beach/a.jpg", "old")
        store.describe(10, "Phone title", listOf("phone"))
        drive.putFile("Beach/a.jpg", "newer")
        album.inspectsAs("newer", beachShot)

        assertEquals(PullSummary(replaced = 1), summary(run()))
        assertEquals("Phone title", store.images.getValue(10).title)
        assertEquals(listOf("phone"), store.images.getValue(10).keywords)
    }

    @Test
    fun `a CSV without some columns keeps the phone's values for them`() {
        store.addEvent(1, "Beach")
        store.syncFolders[1] = FakeSyncStore.SyncFolder("Beach", "Beach")
        syncedLocal(10, 1, "Beach/a.jpg", "same")
        store.describe(10, "Phone title", listOf("phone"), description = "Phone body.", category = "Nature")
        drive.putFile("Beach/a.jpg", "same")
        drive.putFile("Beach/${AgencyCsv.FILE_NAME}", "Filename,Title,Keywords,Category\na.jpg,Adobe title,,11\nb.jpg,,x,11\n")

        assertEquals(PullSummary(updated = 1), summary(run()))
        val image = store.images.getValue(10)
        assertEquals("Adobe title", image.title)
        assertEquals("Phone body.", image.description)
        assertEquals(listOf("phone"), image.keywords)
        assertEquals("Nature", image.category)
    }

    @Test
    fun `an image without a description makes the round trip unchanged`() {
        store.addEvent(1, "Beach")
        store.syncFolders[1] = FakeSyncStore.SyncFolder("Beach", "Beach")
        syncedLocal(10, 1, "Beach/a.jpg", "same")
        store.images.getValue(10).capturedOn = "2026-05-23"
        store.describe(10, "Waves", listOf("beach"), description = "")
        val engine = NextcloudSyncEngine(store, flags, { drive }, FakeFiles())
        drive.putFile("Beach/a.jpg", "same")
        runBlocking { engine.run() }

        assertEquals("May 23, 2026:", AgencyCsv.read(drive.bodies.getValue("Beach/${AgencyCsv.FILE_NAME}")).getValue("a.jpg").description)
        assertEquals(PullSummary(unchanged = 1), summary(run()))
        assertEquals("", store.images.getValue(10).description.orEmpty())
    }

    @Test
    fun `the CSV is never imported as an image`() {
        drive.putFile("Beach/a.jpg", "A")
        putCsv("Beach", row("a.jpg"))
        album.inspectsAs("A")

        assertEquals(PullSummary(newEvents = 1, added = 1), summary(run()))
        assertEquals(listOf("a.jpg"), store.images.values.map { it.displayName })
    }

    @Test
    fun `a folder from before the CSV brings its embedded metadata, and sync writes it into a CSV`() {
        drive.putFile("Beach/a.jpg", "A")
        album.inspectsAs("A", olderVersionShot)

        assertEquals(PullSummary(newEvents = 1, added = 1), summary(run()))
        val a = imageNamed("a.jpg")
        assertEquals("Old waves", a.title)
        assertEquals("Waves from before the CSV.", a.description)
        assertEquals("Belgrade, Serbia", a.captionPlace)
        assertTrue(runBlocking { store.nextAction() } is SyncAction.WriteCsv)
    }

    @Test
    fun `a CSV row beats what the file embeds`() {
        drive.putFile("Beach/a.jpg", "A")
        putCsv("Beach", row("a.jpg"))
        album.inspectsAs("A", olderVersionShot)

        run()
        assertEquals("Waves at dusk", imageNamed("a.jpg").title)
        assertNull(runBlocking { store.nextAction() })
    }

    @Test
    fun `embedded metadata never overrides the phone's own`() {
        store.addEvent(1, "Beach")
        store.syncFolders[1] = FakeSyncStore.SyncFolder("Beach", "Beach")
        syncedLocal(10, 1, "Beach/a.jpg", "old")
        store.describe(10, "Phone title", listOf("phone"))
        drive.putFile("Beach/a.jpg", "newer")
        album.inspectsAs("newer", olderVersionShot)

        assertEquals(PullSummary(replaced = 1), summary(run()))
        assertEquals("Phone title", store.images.getValue(10).title)
    }

    @Test
    fun `a replaced file fills a phone image that had no metadata`() {
        store.addEvent(1, "Beach")
        store.syncFolders[1] = FakeSyncStore.SyncFolder("Beach", "Beach")
        syncedLocal(10, 1, "Beach/a.jpg", "old")
        drive.putFile("Beach/a.jpg", "newer")
        album.inspectsAs("newer", olderVersionShot)

        run()
        assertEquals("Old waves", store.images.getValue(10).title)
        assertTrue(store.isCsvStale(1))
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
        assertEquals("Updated the metadata of 2", PullSummary(updated = 2, unchanged = 5).describe())
    }
}
