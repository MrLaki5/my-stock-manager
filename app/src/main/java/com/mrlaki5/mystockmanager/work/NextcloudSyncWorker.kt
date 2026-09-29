package com.mrlaki5.mystockmanager.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mrlaki5.mystockmanager.nextcloud.NextcloudSettings
import com.mrlaki5.mystockmanager.nextcloud.NextcloudSyncEngine
import com.mrlaki5.mystockmanager.nextcloud.SyncOutcome
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Thin: the engine decides what to do, this only maps its outcome onto WorkManager's retry policy. */
@HiltWorker
class NextcloudSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: NextcloudSyncEngine,
    private val settings: NextcloudSettings,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!settings.enabled.value) return Result.success()
        return when (val outcome = engine.run()) {
            SyncOutcome.Done -> {
                settings.recordSuccess(System.currentTimeMillis())
                Result.success()
            }
            is SyncOutcome.Retry -> {
                settings.recordError(outcome.message)
                Result.retry()
            }
            is SyncOutcome.Failed -> {
                settings.recordError(outcome.message)
                Result.failure()
            }
        }
    }
}
