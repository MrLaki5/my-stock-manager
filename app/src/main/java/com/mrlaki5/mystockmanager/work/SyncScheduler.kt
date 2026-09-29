package com.mrlaki5.mystockmanager.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.mrlaki5.mystockmanager.nextcloud.NextcloudSettings
import com.mrlaki5.mystockmanager.nextcloud.PullState
import com.mrlaki5.mystockmanager.nextcloud.SyncWork
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class SyncTrigger {
    /** Something in the app changed and needs uploading or deleting. */
    CHANGE,

    /** The user asked, or a full check is due. */
    SYNC_NOW,

    /** Constraints changed, which work already queued would not pick up. */
    RECONFIGURE,
}

enum class LiveWork { NONE, WAITING, RUNNING, CHAINED }

enum class EnqueueDecision { SKIP, KEEP, APPEND, REPLACE }

@Singleton
class SyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: NextcloudSettings,
) {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    // Serialised so two triggers cannot both see "nothing live" and enqueue twice.
    private val mutex = Mutex()

    suspend fun request(trigger: SyncTrigger) = mutex.withLock {
        if (!settings.enabled.value) return@withLock
        val live = liveWorkOf(workManager.getWorkInfosForUniqueWorkFlow(NAME).first().map { it.state })
        val policy = when (decide(trigger, live)) {
            EnqueueDecision.SKIP -> return@withLock
            EnqueueDecision.KEEP -> ExistingWorkPolicy.KEEP
            EnqueueDecision.APPEND -> ExistingWorkPolicy.APPEND_OR_REPLACE
            EnqueueDecision.REPLACE -> ExistingWorkPolicy.REPLACE
        }
        workManager.enqueueUniqueWork(NAME, policy, newRequest())
    }

    fun cancel() {
        workManager.cancelUniqueWork(NAME)
    }

    fun observeWork(): Flow<SyncWork> =
        workManager.getWorkInfosForUniqueWorkFlow(NAME).map { infos -> syncWorkOf(infos) }

    /** Not gated on sync being on: pulling onto a new phone comes before turning sync on. */
    suspend fun requestPull() = mutex.withLock {
        // KEEP, so tapping Pull again while one is queued or running changes nothing.
        workManager.enqueueUniqueWork(PULL_NAME, ExistingWorkPolicy.KEEP, newRequest<NextcloudPullWorker>(PULL_NAME))
    }

    suspend fun reconfigurePull() = mutex.withLock {
        val live = liveWorkOf(workManager.getWorkInfosForUniqueWorkFlow(PULL_NAME).first().map { it.state })
        if (live != LiveWork.NONE) {
            workManager.enqueueUniqueWork(PULL_NAME, ExistingWorkPolicy.REPLACE, newRequest<NextcloudPullWorker>(PULL_NAME))
        }
    }

    fun cancelPull() {
        workManager.cancelUniqueWork(PULL_NAME)
    }

    fun observePull(): Flow<PullState> =
        workManager.getWorkInfosForUniqueWorkFlow(PULL_NAME).map { infos -> pullStateOf(infos) }

    private fun newRequest(): OneTimeWorkRequest = newRequest<NextcloudSyncWorker>(TAG)

    private inline fun <reified W : ListenableWorker> newRequest(tag: String): OneTimeWorkRequest {
        val network = if (settings.wifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED
        return OneTimeWorkRequestBuilder<W>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(tag)
            .build()
    }

    companion object {
        const val NAME = "nextcloud-sync"
        const val TAG = "nextcloud-sync"
        const val PULL_NAME = "nextcloud-pull"

        /** At most one run in flight and one behind it, however many changes arrive. */
        fun decide(trigger: SyncTrigger, live: LiveWork): EnqueueDecision = when (trigger) {
            SyncTrigger.CHANGE -> when (live) {
                LiveWork.NONE -> EnqueueDecision.KEEP
                LiveWork.RUNNING -> EnqueueDecision.APPEND
                LiveWork.WAITING, LiveWork.CHAINED -> EnqueueDecision.SKIP
            }
            // Appending rather than replacing a running sync avoids killing an upload halfway through.
            SyncTrigger.SYNC_NOW -> when (live) {
                LiveWork.NONE, LiveWork.WAITING -> EnqueueDecision.REPLACE
                LiveWork.RUNNING -> EnqueueDecision.APPEND
                LiveWork.CHAINED -> EnqueueDecision.SKIP
            }
            // Nothing queued means nothing to re-constrain; a new change will enqueue with the new rules.
            SyncTrigger.RECONFIGURE -> if (live == LiveWork.NONE) EnqueueDecision.SKIP else EnqueueDecision.REPLACE
        }

        fun liveWorkOf(states: List<WorkInfo.State>): LiveWork {
            val running = WorkInfo.State.RUNNING in states
            val queued = WorkInfo.State.ENQUEUED in states || WorkInfo.State.BLOCKED in states
            return when {
                running && queued -> LiveWork.CHAINED
                running -> LiveWork.RUNNING
                queued -> LiveWork.WAITING
                else -> LiveWork.NONE
            }
        }

        private fun pullStateOf(infos: List<WorkInfo>): PullState {
            infos.firstOrNull { it.state == WorkInfo.State.RUNNING }?.let {
                return PullState.Running(
                    done = it.progress.getInt(NextcloudPullWorker.KEY_DONE, 0),
                    total = it.progress.getInt(NextcloudPullWorker.KEY_TOTAL, 0),
                )
            }
            infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }?.let {
                return PullState.Waiting(retrying = it.runAttemptCount > 0)
            }
            val last = infos.filter { it.state.isFinished }
                .maxByOrNull { it.outputData.getLong(NextcloudPullWorker.KEY_FINISHED_AT, 0) }
                ?: return PullState.Idle
            return when (last.state) {
                WorkInfo.State.SUCCEEDED -> PullState.Finished(last.id.toString(), NextcloudPullWorker.summaryOf(last.outputData))
                WorkInfo.State.FAILED -> PullState.Failed(
                    last.id.toString(),
                    last.outputData.getString(NextcloudPullWorker.KEY_ERROR) ?: "The pull from NextCloud failed.",
                )
                else -> PullState.Idle
            }
        }

        private fun syncWorkOf(infos: List<WorkInfo>): SyncWork = when {
            infos.any { it.state == WorkInfo.State.RUNNING } -> SyncWork.RUNNING
            infos.any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 } -> SyncWork.RETRYING
            infos.any { it.state == WorkInfo.State.ENQUEUED } -> SyncWork.QUEUED
            else -> SyncWork.NONE
        }
    }
}
