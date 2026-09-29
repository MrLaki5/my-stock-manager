package com.mrlaki5.mystockmanager.nextcloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudMarkTest {

    private fun row(fileVersion: Long = 2, synced: Long? = null, failed: Long? = null, error: String? = null) =
        ImageCloudRow(imageId = 1, fileVersion = fileVersion, syncedVersion = synced, failedVersion = failed, error = error)

    @Test
    fun `the current version on the cloud is synced, even with sync off`() {
        assertEquals(CloudMark.SYNCED, row(synced = 2).mark(syncEnabled = true))
        assertEquals(CloudMark.SYNCED, row(synced = 2).mark(syncEnabled = false))
    }

    @Test
    fun `an edit not yet uploaded is pending while sync is on`() {
        assertEquals(CloudMark.PENDING, row(synced = 1).mark(syncEnabled = true))
        assertEquals(CloudMark.PENDING, row().mark(syncEnabled = true))
    }

    @Test
    fun `nothing is marked when it is neither on the cloud nor on its way`() {
        assertNull(row(synced = 1).mark(syncEnabled = false))
        assertNull(row().mark(syncEnabled = false))
    }

    @Test
    fun `a refused version is failed, and the reason is kept only then`() {
        assertEquals(CloudStatus(CloudMark.FAILED, "413"), row(failed = 2, error = "413").status(syncEnabled = true))
        assertEquals(CloudMark.PENDING, row(failed = 1, error = "413").mark(syncEnabled = true))
        assertEquals(CloudStatus(CloudMark.SYNCED, null), row(synced = 2, error = "old").status(syncEnabled = true))
    }
}
