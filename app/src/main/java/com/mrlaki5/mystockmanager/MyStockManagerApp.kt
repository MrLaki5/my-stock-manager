package com.mrlaki5.mystockmanager

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.mrlaki5.mystockmanager.data.repository.StockRepository
import com.mrlaki5.mystockmanager.nextcloud.SyncCoordinator
import com.mrlaki5.mystockmanager.storage.AlbumWatcher
import com.mrlaki5.mystockmanager.storage.PhoneCsv
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import javax.inject.Inject

@HiltAndroidApp
class MyStockManagerApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var repository: StockRepository
    @Inject lateinit var albumWatcher: AlbumWatcher
    @Inject lateinit var phoneCsv: PhoneCsv

    // Lazy so building it, and opening the Keystore-backed prefs it reads, happens off the main thread.
    @Inject lateinit var syncCoordinator: dagger.Lazy<SyncCoordinator>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            // Bounded so a 200-image event does not fire 200 concurrent OpenAI calls
            // straight into the rate limiter.
            .setExecutor(Executors.newFixedThreadPool(4))
            .build()

    // Thumbnails of MP4 clips are a decoded frame, which Coil only does with this decoder.
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).components { add(VideoFrameDecoder.Factory()) }.build()

    override fun onCreate() {
        super.onCreate()
        // A process death mid-generation leaves rows stuck in GENERATING with no worker
        // behind them; clear those so the UI never shows a permanent spinner.
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        appScope.launch {
            runCatching { repository.resetStuckGenerating() }
            runCatching { repository.migrateLegacyPrivateFiles() }
            runCatching { repository.backfillCaptureDates() }
            // After the capture dates, which a caption lead is recognised by.
            runCatching { repository.adoptEmbeddedMetadata() }
        }
        appScope.launch { syncCoordinator.get().start(appScope) }
        albumWatcher.start(appScope)
        phoneCsv.start(appScope)
    }
}
