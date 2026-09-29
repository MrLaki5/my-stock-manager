package com.mrlaki5.mystockmanager.work

import androidx.work.WorkInfo.State
import com.mrlaki5.mystockmanager.work.EnqueueDecision.APPEND
import com.mrlaki5.mystockmanager.work.EnqueueDecision.KEEP
import com.mrlaki5.mystockmanager.work.EnqueueDecision.REPLACE
import com.mrlaki5.mystockmanager.work.EnqueueDecision.SKIP
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncSchedulerTest {

    private fun decide(trigger: SyncTrigger, live: LiveWork) = SyncScheduler.decide(trigger, live)

    @Test
    fun `a change never queues more than one run behind the current one`() {
        assertEquals(KEEP, decide(SyncTrigger.CHANGE, LiveWork.NONE))
        assertEquals(APPEND, decide(SyncTrigger.CHANGE, LiveWork.RUNNING))
        assertEquals(SKIP, decide(SyncTrigger.CHANGE, LiveWork.CHAINED))
        assertEquals(SKIP, decide(SyncTrigger.CHANGE, LiveWork.WAITING))
    }

    @Test
    fun `sync now skips the backoff but never cuts off a running upload`() {
        assertEquals(REPLACE, decide(SyncTrigger.SYNC_NOW, LiveWork.NONE))
        assertEquals(REPLACE, decide(SyncTrigger.SYNC_NOW, LiveWork.WAITING))
        assertEquals(APPEND, decide(SyncTrigger.SYNC_NOW, LiveWork.RUNNING))
        assertEquals(SKIP, decide(SyncTrigger.SYNC_NOW, LiveWork.CHAINED))
    }

    @Test
    fun `reconfiguring replaces live work, because queued work keeps its old constraints`() {
        assertEquals(REPLACE, decide(SyncTrigger.RECONFIGURE, LiveWork.WAITING))
        assertEquals(REPLACE, decide(SyncTrigger.RECONFIGURE, LiveWork.RUNNING))
        assertEquals(REPLACE, decide(SyncTrigger.RECONFIGURE, LiveWork.CHAINED))
    }

    @Test
    fun `reconfiguring with nothing queued does not start an empty run`() {
        assertEquals(SKIP, decide(SyncTrigger.RECONFIGURE, LiveWork.NONE))
    }

    @Test
    fun `reads the live state from the unique work's chain`() {
        assertEquals(LiveWork.NONE, SyncScheduler.liveWorkOf(emptyList()))
        assertEquals(LiveWork.NONE, SyncScheduler.liveWorkOf(listOf(State.SUCCEEDED, State.FAILED)))
        assertEquals(LiveWork.WAITING, SyncScheduler.liveWorkOf(listOf(State.ENQUEUED)))
        assertEquals(LiveWork.RUNNING, SyncScheduler.liveWorkOf(listOf(State.SUCCEEDED, State.RUNNING)))
        assertEquals(LiveWork.CHAINED, SyncScheduler.liveWorkOf(listOf(State.RUNNING, State.BLOCKED)))
    }
}
