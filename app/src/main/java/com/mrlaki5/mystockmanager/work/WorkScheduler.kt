package com.mrlaki5.mystockmanager.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.mrlaki5.mystockmanager.generation.GenerationProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    suspend fun enqueueGeneration(imageIds: List<Long>, hint: String?, provider: GenerationProvider) {
        val workManager = WorkManager.getInstance(context)
        if (provider == GenerationProvider.OPENAI) {
            for (imageId in imageIds) {
                // KEEP means double-tapping Generate cannot enqueue the same image twice.
                workManager.enqueueUniqueWork(uniqueNameFor(imageId), ExistingWorkPolicy.KEEP, request(imageId, hint, provider))
            }
            return
        }

        // One model instance serves every image, so on-device jobs run in sequence rather than
        // all waiting on it at once, where the later ones would hit WorkManager's time limit.
        val queued = unfinishedImageIds()
        val requests = imageIds.filterNot { it in queued }.map { request(it, hint, provider) }
        if (requests.isEmpty()) return
        requests.drop(1).fold(
            workManager.beginUniqueWork(ON_DEVICE_QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, requests.first())
        ) { chain, next -> chain.then(next) }.enqueue()
    }

    private fun request(imageId: Long, hint: String?, provider: GenerationProvider): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<GenerateMetadataWorker>()
            .setInputData(
                Data.Builder()
                    .putLong(GenerateMetadataWorker.KEY_IMAGE_ID, imageId)
                    .putString(GenerateMetadataWorker.KEY_HINT, hint)
                    .putString(GenerateMetadataWorker.KEY_PROVIDER, provider.id)
                    .build()
            )
            // No network constraint: offline work would wait silently, so the worker fails fast and says why.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_GENERATION)
            .addTag(imageTagFor(imageId))
            .build()

    /**
     * Drops queued generation for images that are going away. On-device jobs are left alone:
     * cancelling one link of their chain would cancel every image after it. The worker already bails
     * out on a missing row, but cancelling stops WorkManager from waking up and retrying
     * a job that has nothing left to act on.
     */
    fun cancelGeneration(imageIds: Collection<Long>) {
        val workManager = WorkManager.getInstance(context)
        for (imageId in imageIds) {
            workManager.cancelUniqueWork(uniqueNameFor(imageId))
        }
    }

    /** Cancels every unfinished generation except [runningId]; returns the image ids it stopped. */
    suspend fun cancelOtherGeneration(runningId: UUID): Set<Long> {
        val workManager = WorkManager.getInstance(context)
        val others = workManager.getWorkInfosByTagFlow(TAG_GENERATION).first()
            .filter { it.id != runningId && !it.state.isFinished }
        others.forEach { workManager.cancelWorkById(it.id) }
        return others.mapNotNull(::imageIdOf).toSet()
    }

    private suspend fun unfinishedImageIds(): Set<Long> =
        WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG_GENERATION).first()
            .filter { !it.state.isFinished }
            .mapNotNull(::imageIdOf)
            .toSet()

    private fun imageIdOf(info: WorkInfo): Long? = info.tags.firstNotNullOfOrNull { tag ->
        if (tag.startsWith(IMAGE_TAG_PREFIX)) tag.removePrefix(IMAGE_TAG_PREFIX).toLongOrNull() else null
    }

    private fun uniqueNameFor(imageId: Long) = "generate-$imageId"

    private fun imageTagFor(imageId: Long) = "$IMAGE_TAG_PREFIX$imageId"

    companion object {
        const val TAG_GENERATION = "generation"
        private const val IMAGE_TAG_PREFIX = "generate-image:"
        private const val ON_DEVICE_QUEUE = "generate-on-device"
    }
}
