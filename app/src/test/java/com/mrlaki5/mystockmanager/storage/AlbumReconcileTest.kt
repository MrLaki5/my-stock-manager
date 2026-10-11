package com.mrlaki5.mystockmanager.storage

import com.mrlaki5.mystockmanager.data.db.dao.AlbumRow
import com.mrlaki5.mystockmanager.data.db.entity.AlbumState
import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumReconcileTest {

    private fun row(id: Long, state: AlbumState = AlbumState.PRESENT, expiresAt: Long? = null) =
        AlbumRow(id, "content://media/external_primary/images/media/${id + 100}", state, expiresAt)

    @Test
    fun `an unchanged album produces no writes`() {
        val rows = listOf(row(1), row(2, AlbumState.TRASHED, 5_000))
        val entries = mapOf(101L to AlbumEntry(false, null), 102L to AlbumEntry(true, 5_000))
        assertEquals(emptyList<AlbumChange>(), AlbumWatcher.reconcile(rows, entries))
    }

    @Test
    fun `a file deleted in the gallery is marked trashed with its purge time`() {
        val changes = AlbumWatcher.reconcile(listOf(row(1)), mapOf(101L to AlbumEntry(true, 9_000)))
        assertEquals(listOf(AlbumChange.Mark(1, AlbumState.TRASHED, 9_000)), changes)
    }

    @Test
    fun `a file restored outside the app is present again`() {
        val changes = AlbumWatcher.reconcile(listOf(row(1, AlbumState.TRASHED, 9_000)), mapOf(101L to AlbumEntry(false, null)))
        assertEquals(listOf(AlbumChange.Mark(1, AlbumState.PRESENT, null)), changes)
    }

    @Test
    fun `only a row seen in the trash is forgotten when its file disappears`() {
        val rows = listOf(row(1, AlbumState.TRASHED, 9_000), row(2), row(3, AlbumState.MISSING))
        val changes = AlbumWatcher.reconcile(rows, emptyMap())
        assertEquals(listOf(AlbumChange.Forget(1), AlbumChange.Mark(2, AlbumState.MISSING, null)), changes)
    }

    @Test
    fun `a missing file that comes back is present again`() {
        val changes = AlbumWatcher.reconcile(listOf(row(1, AlbumState.MISSING)), mapOf(101L to AlbumEntry(false, null)))
        assertEquals(listOf(AlbumChange.Mark(1, AlbumState.PRESENT, null)), changes)
    }

    @Test
    fun `a row whose uri has no id is left alone`() {
        val rows = listOf(AlbumRow(1, "content://media/external/images/media/", AlbumState.TRASHED, null))
        assertEquals(emptyList<AlbumChange>(), AlbumWatcher.reconcile(rows, emptyMap()))
    }
}
