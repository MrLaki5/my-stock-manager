package com.mrlaki5.mystockmanager.nextcloud

import com.mrlaki5.mystockmanager.metadata.AgencyCsv
import com.mrlaki5.mystockmanager.metadata.CsvRow
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextcloudSyncEngineTest {

    private val store = FakeSyncStore()
    private val drive = FakeDrive()
    private val files = FakeFiles()
    private val flags = FakeFlags(
        NextcloudAccount("https://cloud.example.com/".toHttpUrl(), "milan", "pw", "milan", listOf("my-stock-manager")),
    )
    private val engine = NextcloudSyncEngine(store, flags, { drive }, files)

    private fun event(id: Long, name: String, vararg images: Pair<Long, String>) {
        store.addEvent(id, name)
        images.forEach { (imageId, displayName) ->
            store.addImage(imageId, id, displayName)
            files.put(imageId, size = 10)
        }
    }

    private fun sync(): SyncOutcome = runBlocking { engine.run() }

    private fun syncFresh(): SyncOutcome {
        drive.calls.clear()
        return sync()
    }

    private val csvPath = "Beach/${AgencyCsv.FILE_NAME}"

    private fun cloudCsv(): Map<String, CsvRow> = AgencyCsv.read(drive.bodies.getValue(csvPath))

    @Test
    fun `creates the event folder and uploads its images`() {
        event(1, "Beach", 10L to "a.jpg", 11L to "b.jpg")

        assertEquals(SyncOutcome.Done, sync())
        assertEquals(
            listOf("ROOT", "MKCOL Beach", "STAT Beach/a.jpg", "PUT Beach/a.jpg", "STAT Beach/b.jpg", "PUT Beach/b.jpg"),
            drive.calls,
        )
        assertEquals(mapOf("Beach/a.jpg" to 10L, "Beach/b.jpg" to 10L), drive.files)
        assertEquals(SyncOutcome.Done, syncFresh())
        assertEquals(emptyList<String>(), drive.calls)
    }

    @Test
    fun `re-uploads a rewritten image in place`() {
        event(1, "Beach", 10L to "a.jpg")
        sync()
        store.rewrite(10)
        files.put(10, size = 25)

        assertEquals(SyncOutcome.Done, syncFresh())
        assertEquals(listOf("ROOT", "PUT Beach/a.jpg"), drive.calls)
        assertEquals(25L, drive.files["Beach/a.jpg"])
    }

    @Test
    fun `an edit made during the upload is uploaded again`() {
        event(1, "Beach", 10L to "a.jpg")
        var edited = false
        drive.intercept = { call ->
            if (call == "PUT Beach/a.jpg" && !edited) {
                edited = true
                store.rewrite(10)
            }
            null
        }

        assertEquals(SyncOutcome.Done, sync())
        assertEquals(2, drive.calls.count { it == "PUT Beach/a.jpg" })
        assertFalse(store.isDirty(store.images.getValue(10)))
    }

    @Test
    fun `a rename moves the folder rather than uploading it again`() {
        event(1, "Beach", 10L to "a.jpg")
        sync()
        store.rename(1, "Sunset")

        assertEquals(SyncOutcome.Done, syncFresh())
        assertEquals(listOf("ROOT", "MOVE Beach -> Sunset"), drive.calls)
        assertEquals(mapOf("Sunset/a.jpg" to 10L), drive.files)
    }

    @Test
    fun `an event renamed and then deleted offline is deleted under its old name`() {
        event(1, "Beach", 10L to "a.jpg")
        sync()
        store.rename(1, "Sunset")
        store.deleteEvent(1)

        assertEquals(SyncOutcome.Done, syncFresh())
        assertEquals(listOf("ROOT", "DELETE Beach"), drive.calls)
        assertTrue(drive.folders.isEmpty())
    }

    @Test
    fun `an image deleted during a folder move is deleted at the new path`() {
        event(1, "Beach", 10L to "a.jpg")
        sync()
        store.rename(1, "Sunset")
        drive.intercept = { call ->
            if (call.startsWith("MOVE")) store.deleteImage(10)
            null
        }

        assertEquals(SyncOutcome.Done, syncFresh())
        assertEquals(listOf("ROOT", "MOVE Beach -> Sunset", "DELETE Sunset/a.jpg"), drive.calls)
        assertTrue(drive.files.isEmpty())
    }

    @Test
    fun `an event deleted while its folder is being created leaves nothing behind`() {
        event(1, "Beach")
        drive.intercept = { call ->
            if (call == "MKCOL Beach") store.deleteEvent(1)
            null
        }

        assertEquals(SyncOutcome.Done, sync())
        assertEquals(listOf("ROOT", "MKCOL Beach", "DELETE Beach"), drive.calls)
        assertTrue(drive.folders.isEmpty())
    }

    @Test
    fun `an image deleted during its first upload is removed from the cloud`() {
        event(1, "Beach", 10L to "a.jpg")
        drive.intercept = { call ->
            if (call == "PUT Beach/a.jpg") store.deleteImage(10)
            null
        }

        assertEquals(SyncOutcome.Done, sync())
        assertTrue(drive.calls.last() == "DELETE Beach/a.jpg")
        assertTrue(drive.files.isEmpty())
    }

    @Test
    fun `a rename onto a name the cloud already uses takes the next free one`() {
        event(1, "Beach", 10L to "a.jpg")
        sync()
        drive.folders += "Sunset"
        store.rename(1, "Sunset")

        assertEquals(SyncOutcome.Done, syncFresh())
        assertEquals(listOf("ROOT", "MOVE Beach -> Sunset", "MOVE Beach -> Sunset (2)"), drive.calls)
        assertEquals("Sunset (2)", store.syncFolders.getValue(1).remoteName)
    }

    @Test
    fun `a folder removed from the cloud before a rename is recreated and refilled`() {
        event(1, "Beach", 10L to "a.jpg")
        sync()
        drive.folders.clear()
        drive.files.clear()
        store.rename(1, "Sunset")

        assertEquals(SyncOutcome.Done, syncFresh())
        assertEquals(mapOf("Sunset/a.jpg" to 10L), drive.files)
    }

    @Test
    fun `adopts an existing folder but leaves its other files alone`() {
        drive.folders += "Beach"
        drive.files["Beach/theirs.jpg"] = 3
        event(1, "Beach", 10L to "a.jpg")

        assertEquals(SyncOutcome.Done, sync())
        assertEquals("Beach", store.syncFolders.getValue(1).remoteName)
        assertEquals(mapOf("Beach/theirs.jpg" to 3L, "Beach/a.jpg" to 10L), drive.files)
    }

    @Test
    fun `adopts a same-sized file from an earlier install and sidesteps a different one`() {
        drive.folders += "Beach"
        drive.files["Beach/a.jpg"] = 10
        drive.files["Beach/b.jpg"] = 99
        event(1, "Beach", 10L to "a.jpg", 11L to "b.jpg")

        assertEquals(SyncOutcome.Done, sync())
        assertFalse("PUT Beach/a.jpg" in drive.calls)
        assertEquals(99L, drive.files["Beach/b.jpg"])
        assertEquals(10L, drive.files["Beach/b (11).jpg"])
    }

    @Test
    fun `a full check re-uploads files deleted or replaced on the cloud`() {
        event(1, "Beach", 10L to "a.jpg", 11L to "b.jpg")
        sync()
        drive.files.remove("Beach/a.jpg")
        drive.files["Beach/b.jpg"] = 1
        flags.fullCheckRequested = true

        assertEquals(SyncOutcome.Done, syncFresh())
        assertEquals(mapOf("Beach/b.jpg" to 10L, "Beach/a.jpg" to 10L), drive.files)
        assertFalse(flags.fullCheckRequested)
        assertEquals(1, flags.fullChecks)
    }

    @Test
    fun `a full check recreates a folder deleted on the cloud`() {
        event(1, "Beach", 10L to "a.jpg")
        sync()
        drive.folders.clear()
        drive.files.clear()
        flags.fullCheckRequested = true

        assertEquals(SyncOutcome.Done, syncFresh())
        assertEquals(mapOf("Beach/a.jpg" to 10L), drive.files)
    }

    @Test
    fun `a deletion made with sync off is never sent`() {
        event(1, "Beach", 10L to "a.jpg")
        sync()
        store.deleteImage(10, tombstone = false)

        assertEquals(SyncOutcome.Done, syncFresh())
        assertEquals(emptyList<String>(), drive.calls)
        assertEquals(mapOf("Beach/a.jpg" to 10L), drive.files)
    }

    @Test
    fun `rejected credentials stop this run and every later one`() {
        event(1, "Beach")
        drive.intercept = { DavResult.AuthRejected("no") }

        assertTrue(sync() is SyncOutcome.Failed)
        assertTrue(flags.authRejected)
        assertTrue(syncFresh() is SyncOutcome.Failed)
        assertEquals(emptyList<String>(), drive.calls)
    }

    @Test
    fun `a file NextCloud refuses is skipped until it changes`() {
        event(1, "Beach", 10L to "a.jpg", 11L to "b.jpg")
        drive.intercept = { call -> if (call == "PUT Beach/a.jpg") DavResult.ItemRejected("413") else null }

        assertEquals(SyncOutcome.Done, sync())
        assertEquals(setOf("Beach/b.jpg"), drive.files.keys)
        assertEquals(SyncOutcome.Done, syncFresh())
        assertEquals(emptyList<String>(), drive.calls)

        store.rewrite(10)
        drive.intercept = { null }
        assertEquals(SyncOutcome.Done, syncFresh())
        assertEquals(setOf("Beach/b.jpg", "Beach/a.jpg"), drive.files.keys)
    }

    @Test
    fun `an image missing from the album is marked failed rather than retried`() {
        event(1, "Beach", 10L to "a.jpg")
        files.bytes.clear()

        assertEquals(SyncOutcome.Done, sync())
        assertEquals(1L, store.syncImages.getValue(10).failedVersion)
        assertTrue(drive.files.isEmpty())
    }

    @Test
    fun `a transient failure asks WorkManager to retry`() {
        event(1, "Beach", 10L to "a.jpg")
        drive.intercept = { call -> if (call.startsWith("PUT")) DavResult.Transient("503") else null }

        assertTrue(sync() is SyncOutcome.Retry)
    }

    @Test
    fun `an upload into a vanished folder recreates the folder`() {
        event(1, "Beach", 10L to "a.jpg")
        sync()
        drive.folders.clear()
        drive.files.clear()
        store.rewrite(10)

        assertEquals(SyncOutcome.Done, syncFresh())
        assertEquals(mapOf("Beach/a.jpg" to 10L), drive.files)
    }

    @Test
    fun `a new event cannot take a name still held by a renamed one`() {
        event(1, "Beach")
        sync()
        store.rename(1, "Sunset")
        event(2, "Beach")

        assertEquals(SyncOutcome.Done, syncFresh())
        assertEquals("Sunset", store.syncFolders.getValue(1).remoteName)
        assertEquals("Beach", store.syncFolders.getValue(2).remoteName)
    }

    @Test
    fun `writes the event's CSV once an uploaded image has metadata`() {
        event(1, "Beach", 10L to "a.jpg", 11L to "b.jpg")
        store.describe(10, "Waves at dusk", listOf("beach", "waves"), category = "Nature")

        assertEquals(SyncOutcome.Done, sync())
        assertEquals(listOf("LIST Beach", "PUT $csvPath"), drive.calls.takeLast(2))
        val row = cloudCsv().getValue("a.jpg")
        assertEquals("Waves at dusk", row.title)
        assertEquals(listOf("beach", "waves"), row.keywords)
        assertEquals("Nature", row.category)
        assertEquals(setOf("a.jpg"), cloudCsv().keys)

        assertEquals(SyncOutcome.Done, syncFresh())
        assertEquals(emptyList<String>(), drive.calls)
    }

    @Test
    fun `a metadata edit rewrites only the CSV, never the image`() {
        event(1, "Beach", 10L to "a.jpg")
        store.describe(10, "Waves", listOf("beach"))
        sync()
        store.describe(10, "Waves at dusk", listOf("beach", "dusk"))

        assertEquals(SyncOutcome.Done, syncFresh())
        assertEquals(listOf("ROOT", "LIST Beach", "GET $csvPath", "PUT $csvPath"), drive.calls)
        assertEquals("Waves at dusk", cloudCsv().getValue("a.jpg").title)
    }

    @Test
    fun `an image is left out of the CSV until it is on the cloud`() {
        event(1, "Beach", 10L to "a.jpg")
        store.describe(10, "Waves", listOf("beach"))
        drive.intercept = { call -> if (call == "PUT Beach/a.jpg") DavResult.Transient("reset") else null }

        assertTrue(sync() is SyncOutcome.Retry)
        assertTrue(drive.calls.none { it.endsWith(AgencyCsv.FILE_NAME) })
        drive.intercept = { null }
        assertEquals(SyncOutcome.Done, syncFresh())
        assertEquals(setOf("a.jpg"), cloudCsv().keys)
    }

    @Test
    fun `an event without metadata gets no CSV`() {
        event(1, "Beach", 10L to "a.jpg")

        assertEquals(SyncOutcome.Done, sync())
        assertNull(drive.files[csvPath])
        assertNull(runBlocking { store.nextAction() })
    }

    @Test
    fun `deleting an image with sync on drops its row`() {
        event(1, "Beach", 10L to "a.jpg", 11L to "b.jpg")
        store.describe(10, "A", listOf("a"))
        store.describe(11, "B", listOf("b"))
        sync()
        store.deleteImage(10)

        assertEquals(SyncOutcome.Done, syncFresh())
        assertEquals(setOf("b.jpg"), cloudCsv().keys)
    }

    @Test
    fun `an image deleted with sync off keeps its row while its file stays on the cloud`() {
        event(1, "Beach", 10L to "a.jpg", 11L to "b.jpg")
        store.describe(10, "A", listOf("a"))
        store.describe(11, "B", listOf("b"))
        sync()
        store.deleteImage(10, tombstone = false)
        store.describe(11, "B again", listOf("b"))

        assertEquals(SyncOutcome.Done, syncFresh())
        assertEquals(setOf("a.jpg", "b.jpg"), cloudCsv().keys)
        assertEquals("A", cloudCsv().getValue("a.jpg").title)

        drive.files.remove("Beach/a.jpg")
        store.describe(11, "B once more", listOf("b"))
        assertEquals(SyncOutcome.Done, syncFresh())
        assertEquals(setOf("b.jpg"), cloudCsv().keys)
    }

    @Test
    fun `deleting the last described image settles even when the CSV is already gone from the cloud`() {
        event(1, "Beach", 10L to "a.jpg", 11L to "b.jpg")
        store.describe(10, "A", listOf("a"))
        sync()
        drive.files.remove(csvPath)
        drive.bodies.remove(csvPath)
        store.deleteImage(10)

        assertEquals(SyncOutcome.Done, syncFresh())
        assertNull(runBlocking { store.nextAction() })
    }

    @Test
    fun `keeps the cloud's row for an image this phone has no metadata for`() {
        event(1, "Beach", 10L to "a.jpg", 11L to "b.jpg")
        store.describe(11, "B", listOf("b"))
        sync()
        drive.putFile(csvPath, String(AgencyCsv.write(listOf(CsvRow("a.jpg", "From the other phone", "", listOf("a"), null)))))
        store.describe(11, "B again", listOf("b"))

        assertEquals(SyncOutcome.Done, syncFresh())
        assertEquals("From the other phone", cloudCsv().getValue("a.jpg").title)
        assertEquals("B again", cloudCsv().getValue("b.jpg").title)
    }

    @Test
    fun `the CSV waits until the event has finished generating`() {
        event(1, "Beach", 10L to "a.jpg", 11L to "b.jpg")
        sync()
        store.describe(10, "A", listOf("a"))
        store.images.getValue(11).generating = true

        assertEquals(SyncOutcome.Done, syncFresh())
        assertNull(drive.files[csvPath])

        store.images.getValue(11).generating = false
        store.describe(11, "B", listOf("b"))
        assertEquals(SyncOutcome.Done, syncFresh())
        assertEquals(listOf("ROOT", "LIST Beach", "PUT $csvPath"), drive.calls)
        assertEquals(setOf("a.jpg", "b.jpg"), cloudCsv().keys)
    }

    @Test
    fun `a full check rewrites a CSV deleted on the cloud`() {
        event(1, "Beach", 10L to "a.jpg")
        store.describe(10, "Waves", listOf("beach"))
        sync()
        drive.files.remove(csvPath)
        drive.bodies.remove(csvPath)
        flags.fullCheckRequested = true

        assertEquals(SyncOutcome.Done, syncFresh())
        assertEquals("Waves", cloudCsv().getValue("a.jpg").title)
    }

    @Test
    fun `a renamed event keeps its CSV in the moved folder, and later deletions still reach it`() {
        event(1, "Beach", 10L to "a.jpg", 11L to "b.jpg")
        store.describe(10, "Waves", listOf("beach"))
        store.describe(11, "Sand", listOf("sand"))
        sync()
        store.rename(1, "Sunset")

        assertEquals(SyncOutcome.Done, syncFresh())
        assertEquals(listOf("ROOT", "MOVE Beach -> Sunset"), drive.calls)
        val moved = "Sunset/${AgencyCsv.FILE_NAME}"
        assertEquals("Waves", AgencyCsv.read(drive.bodies.getValue(moved)).getValue("a.jpg").title)

        store.deleteImage(10)
        assertEquals(SyncOutcome.Done, syncFresh())
        assertEquals(setOf("b.jpg"), AgencyCsv.read(drive.bodies.getValue(moved)).keys)
    }
}
