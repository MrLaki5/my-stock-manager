package com.mrlaki5.mystockmanager.nextcloud

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncStatusTest {

    private val idle = SyncStatusInput(
        hasAccount = true,
        enabled = true,
        wifiOnly = true,
        work = SyncWork.NONE,
        pending = 0,
        failed = 0,
        lastSuccessAt = null,
        lastError = null,
    )

    private fun describe(input: SyncStatusInput) = SyncStatus.describe(input) { "14:02" }

    @Test
    fun `explains why nothing is happening before sync is set up or on`() {
        assertEquals("Not set up", describe(idle.copy(hasAccount = false)))
        assertEquals("Sync is off", describe(idle.copy(enabled = false, failed = 3)))
    }

    @Test
    fun `shows progress while a run is going`() {
        assertEquals("Syncing… 12 left", describe(idle.copy(work = SyncWork.RUNNING, pending = 12)))
        assertEquals("Syncing…", describe(idle.copy(work = SyncWork.RUNNING)))
    }

    @Test
    fun `names the network it is waiting for`() {
        assertEquals("Waiting for Wi-Fi", describe(idle.copy(work = SyncWork.QUEUED)))
        assertEquals("Waiting for a connection", describe(idle.copy(work = SyncWork.QUEUED, wifiOnly = false)))
    }

    @Test
    fun `surfaces the last error while retrying and after stopping`() {
        assertEquals(
            "Retrying soon: NextCloud is not responding right now (503).",
            describe(idle.copy(work = SyncWork.RETRYING, lastError = "NextCloud is not responding right now (503).")),
        )
        assertEquals("Stopped: Your NextCloud storage is full.", describe(idle.copy(lastError = "Your NextCloud storage is full.")))
    }

    @Test
    fun `reports up to date with the time and any files that could not go up`() {
        assertEquals("Up to date · last synced 14:02", describe(idle.copy(lastSuccessAt = 1L)))
        assertEquals("Up to date · 2 could not be uploaded", describe(idle.copy(failed = 2)))
        assertEquals("3 waiting to sync", describe(idle.copy(pending = 3)))
    }

    @Test
    fun `reports a pull before anything else, even with sync off`() {
        assertEquals(
            "Pulling from NextCloud… 3 of 10",
            describe(idle.copy(enabled = false, pull = PullState.Running(3, 10))),
        )
        assertEquals("Pull waiting for Wi-Fi", describe(idle.copy(pull = PullState.Waiting(retrying = false))))
        assertEquals(
            "Pull waiting for a connection",
            describe(idle.copy(wifiOnly = false, pull = PullState.Waiting(retrying = false))),
        )
        assertEquals("Pull will retry soon", describe(idle.copy(pull = PullState.Waiting(retrying = true))))
        assertEquals("Up to date", describe(idle.copy(pull = PullState.Finished("id", PullSummary()))))
    }
}
