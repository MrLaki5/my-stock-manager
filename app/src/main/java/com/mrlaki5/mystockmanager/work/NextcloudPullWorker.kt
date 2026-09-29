package com.mrlaki5.mystockmanager.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mrlaki5.mystockmanager.nextcloud.NextcloudPull
import com.mrlaki5.mystockmanager.nextcloud.NextcloudSyncEngine
import com.mrlaki5.mystockmanager.nextcloud.PullOutcome
import com.mrlaki5.mystockmanager.nextcloud.PullSummary
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class NextcloudPullWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val pull: NextcloudPull,
    private val engine: NextcloudSyncEngine,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Holding the sync engine's lock keeps an upload run from interleaving with the pull's writes.
        val outcome = engine.exclusive {
            pull.run { done, total -> setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total)) }
        }
        val finishedAt = System.currentTimeMillis()
        return when (outcome) {
            is PullOutcome.Done -> Result.success(
                workDataOf(
                    KEY_NEW_EVENTS to outcome.summary.newEvents,
                    KEY_ADDED to outcome.summary.added,
                    KEY_REPLACED to outcome.summary.replaced,
                    KEY_UNCHANGED to outcome.summary.unchanged,
                    KEY_SKIPPED to outcome.summary.skipped,
                    KEY_FINISHED_AT to finishedAt,
                )
            )
            is PullOutcome.Retry -> Result.retry()
            is PullOutcome.Failed -> Result.failure(workDataOf(KEY_ERROR to outcome.message, KEY_FINISHED_AT to finishedAt))
        }
    }

    companion object {
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_NEW_EVENTS = "newEvents"
        const val KEY_ADDED = "added"
        const val KEY_REPLACED = "replaced"
        const val KEY_UNCHANGED = "unchanged"
        const val KEY_SKIPPED = "skipped"
        const val KEY_ERROR = "error"
        const val KEY_FINISHED_AT = "finishedAt"

        fun summaryOf(data: Data) = PullSummary(
            newEvents = data.getInt(KEY_NEW_EVENTS, 0),
            added = data.getInt(KEY_ADDED, 0),
            replaced = data.getInt(KEY_REPLACED, 0),
            unchanged = data.getInt(KEY_UNCHANGED, 0),
            skipped = data.getInt(KEY_SKIPPED, 0),
        )
    }
}
