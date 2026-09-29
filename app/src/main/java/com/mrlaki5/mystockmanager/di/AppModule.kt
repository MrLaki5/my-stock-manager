package com.mrlaki5.mystockmanager.di

import android.content.Context
import androidx.room.Room
import com.mrlaki5.mystockmanager.data.db.StockDatabase
import com.mrlaki5.mystockmanager.data.db.dao.FolderDao
import com.mrlaki5.mystockmanager.data.db.dao.ImageDao
import com.mrlaki5.mystockmanager.data.db.dao.PullDao
import com.mrlaki5.mystockmanager.data.db.dao.SyncDao
import com.mrlaki5.mystockmanager.data.prefs.SecureKeyStore
import com.mrlaki5.mystockmanager.metadata.CommonsImagingMetadataWriter
import com.mrlaki5.mystockmanager.metadata.MetadataWriter
import com.mrlaki5.mystockmanager.nextcloud.AlbumFiles
import com.mrlaki5.mystockmanager.nextcloud.LocalFiles
import com.mrlaki5.mystockmanager.nextcloud.NextcloudSettings
import com.mrlaki5.mystockmanager.nextcloud.PullAlbum
import com.mrlaki5.mystockmanager.nextcloud.PullAlbumFiles
import com.mrlaki5.mystockmanager.nextcloud.PullStore
import com.mrlaki5.mystockmanager.nextcloud.RemoteDriveFactory
import com.mrlaki5.mystockmanager.nextcloud.SyncFlags
import com.mrlaki5.mystockmanager.nextcloud.SyncStore
import com.mrlaki5.mystockmanager.nextcloud.WebDavDrives
import com.mrlaki5.mystockmanager.openai.OpenAiClient
import com.mrlaki5.mystockmanager.storage.AppFileStore
import com.mrlaki5.mystockmanager.storage.MediaStoreExporter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): StockDatabase =
        Room.databaseBuilder(context, StockDatabase::class.java, StockDatabase.NAME)
            .addMigrations(
                StockDatabase.MIGRATION_1_2,
                StockDatabase.MIGRATION_2_3,
                StockDatabase.MIGRATION_3_4,
                StockDatabase.MIGRATION_4_5,
            )
            .build()

    @Provides
    fun folderDao(database: StockDatabase): FolderDao = database.folderDao()

    @Provides
    fun imageDao(database: StockDatabase): ImageDao = database.imageDao()

    @Provides
    fun syncDao(database: StockDatabase): SyncDao = database.syncDao()

    @Provides
    fun pullDao(database: StockDatabase): PullDao = database.pullDao()

    @Provides
    @Singleton
    fun fileStore(@ApplicationContext context: Context): AppFileStore = AppFileStore(context)

    @Provides
    @Singleton
    fun mediaStoreExporter(@ApplicationContext context: Context): MediaStoreExporter =
        MediaStoreExporter(context)

    @Provides
    @Singleton
    fun secureKeyStore(@ApplicationContext context: Context): SecureKeyStore =
        SecureKeyStore(context)

    @Provides
    @Singleton
    fun openAiClient(): OpenAiClient = OpenAiClient()

    /** Interfaced so the Commons Imaging implementation can be swapped wholesale. */
    @Provides
    @Singleton
    fun metadataWriter(): MetadataWriter = CommonsImagingMetadataWriter()

    // The sync engine sees only these narrow interfaces, so its tests can run on the JVM.
    @Provides
    fun syncStore(dao: SyncDao): SyncStore = dao

    @Provides
    fun syncFlags(settings: NextcloudSettings): SyncFlags = settings

    @Provides
    fun remoteDriveFactory(drives: WebDavDrives): RemoteDriveFactory = drives

    @Provides
    fun localFiles(files: AlbumFiles): LocalFiles = files

    @Provides
    fun pullStore(dao: PullDao): PullStore = dao

    @Provides
    fun pullAlbum(files: PullAlbumFiles): PullAlbum = files
}
