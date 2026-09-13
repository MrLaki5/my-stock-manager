package com.mrlaki5.mystockmanager

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.mrlaki5.mystockmanager.data.repository.StockRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import javax.inject.Inject

@HiltAndroidApp
class MyStockManagerApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var repository: StockRepository

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            // Bounded so a 200-image event does not fire 200 concurrent OpenAI calls
            // straight into the rate limiter.
            .setExecutor(Executors.newFixedThreadPool(4))
            .build()

    override fun onCreate() {
        super.onCreate()
        // A process death mid-generation leaves rows stuck in GENERATING with no worker
        // behind them; clear those so the UI never shows a permanent spinner.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { repository.resetStuckGenerating() }
            runCatching { repository.migrateLegacyPrivateFiles() }
            runCatching { repository.backfillCaptureDates() }
        }
    }
}
