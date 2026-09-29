package com.mrlaki5.mystockmanager.nextcloud

import com.mrlaki5.mystockmanager.data.db.dao.SyncDao
import com.mrlaki5.mystockmanager.work.SyncScheduler
import com.mrlaki5.mystockmanager.work.SyncTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** Everything outside the engine that turns settings changes and app edits into sync runs. */
@Singleton
class SyncCoordinator @Inject constructor(
    private val settings: NextcloudSettings,
    private val scheduler: SyncScheduler,
    private val syncDao: SyncDao,
    private val engine: NextcloudSyncEngine,
    private val drives: WebDavDrives,
) {

    /** Watches the database rather than every place that edits it, so no editor needs to know about sync. */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            val fullCheckDue = System.currentTimeMillis() - settings.lastFullCheckAt > FULL_CHECK_INTERVAL
            if (settings.enabled.value && fullCheckDue) syncNow()

            settings.enabled.collectLatest { enabled ->
                if (!enabled) return@collectLatest
                var previous = 0
                syncDao.observeCounts().map { it.pending }.debounce(2.seconds).collect { pending ->
                    // Only a rise is new work; a falling count is a run making progress.
                    if (pending > previous) scheduler.request(SyncTrigger.CHANGE)
                    previous = pending
                }
            }
        }
    }

    /** Tests the connection and creates the sync folder before anything is saved. */
    suspend fun applyAccount(
        server: String,
        login: String,
        appPassword: String,
        folder: String,
    ): Result<NextcloudAccount> = withContext(Dispatchers.IO) {
        val baseUrl = NextcloudAccount.parseServer(server).getOrElse { return@withContext Result.failure(it) }
        val root = NextcloudAccount.parseRoot(folder).getOrElse { return@withContext Result.failure(it) }
        val user = login.trim().ifEmpty { return@withContext invalid("Enter your NextCloud username") }
        val password = appPassword.trim().ifEmpty { return@withContext invalid("Enter an app password") }

        val userId = when (val result = WebDavClient.resolveUserId(drives.http, baseUrl, user, password)) {
            is DavResult.Ok -> result.value
            is DavResult.Failure -> return@withContext invalid(result.message)
        }
        val account = NextcloudAccount(baseUrl, user, password, userId, root)
        when (val result = drives.create(account).ensureRoot()) {
            is DavResult.Ok -> Unit
            is DavResult.Failure -> return@withContext invalid(result.message)
        }

        scheduler.cancel()
        scheduler.cancelPull()
        engine.exclusive {
            // A new server, user or folder starts from scratch; the old location is left as it is.
            if (account.targetKey != settings.targetKey) syncDao.resetAll()
            settings.saveAccount(account)
        }
        if (settings.enabled.value) syncNow()
        Result.success(account)
    }

    /** Turning sync off keeps every record and queued deletion, so turning it back on resumes cleanly. */
    suspend fun setEnabled(on: Boolean) = withContext(Dispatchers.IO) {
        if (on && settings.account == null) return@withContext
        settings.setEnabled(on)
        if (on) syncNow() else scheduler.cancel()
    }

    suspend fun setWifiOnly(on: Boolean) = withContext(Dispatchers.IO) {
        settings.setWifiOnly(on)
        scheduler.request(SyncTrigger.RECONFIGURE)
        scheduler.reconfigurePull()
    }

    /** Works with sync off too, so a new phone can be filled before sync is turned on. */
    suspend fun pullNow() = withContext(Dispatchers.IO) {
        if (settings.account != null) scheduler.requestPull()
    }

    fun observePull(): Flow<PullState> = scheduler.observePull()

    /** Also re-checks the cloud, re-uploading anything deleted or replaced there, and retries failed images. */
    suspend fun syncNow() = withContext(Dispatchers.IO) {
        settings.fullCheckRequested = true
        scheduler.request(SyncTrigger.SYNC_NOW)
    }

    fun status(formatTime: (Long) -> String): Flow<String> {
        val prefs = combine(settings.enabled, settings.wifiOnly, settings.savedAccount) { enabled, wifiOnly, account ->
            Triple(enabled, wifiOnly, account != null)
        }
        val work = combine(scheduler.observeWork(), scheduler.observePull()) { sync, pull -> sync to pull }
        return combine(
            prefs,
            work,
            syncDao.observeCounts(),
            settings.lastSuccessAt,
            settings.lastError,
        ) { (enabled, wifiOnly, hasAccount), (sync, pull), counts, lastSuccessAt, lastError ->
            SyncStatus.describe(
                SyncStatusInput(
                    hasAccount = hasAccount,
                    enabled = enabled,
                    wifiOnly = wifiOnly,
                    work = sync,
                    pending = counts.pending,
                    failed = counts.failed,
                    lastSuccessAt = lastSuccessAt,
                    lastError = lastError,
                    pull = pull,
                ),
                formatTime,
            )
        }
    }

    private fun invalid(message: String): Result<Nothing> = Result.failure(IllegalArgumentException(message))

    private companion object {
        val FULL_CHECK_INTERVAL = 24.hours.inWholeMilliseconds
    }
}
