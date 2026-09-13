package com.mrlaki5.mystockmanager.di

import android.content.Context
import androidx.room.Room
import com.mrlaki5.mystockmanager.data.db.StockDatabase
import com.mrlaki5.mystockmanager.data.db.dao.FolderDao
import com.mrlaki5.mystockmanager.data.db.dao.ImageDao
import com.mrlaki5.mystockmanager.data.prefs.SecureKeyStore
import com.mrlaki5.mystockmanager.metadata.CommonsImagingMetadataWriter
import com.mrlaki5.mystockmanager.metadata.MetadataWriter
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
            )
            .build()

    @Provides
    fun folderDao(database: StockDatabase): FolderDao = database.folderDao()

    @Provides
    fun imageDao(database: StockDatabase): ImageDao = database.imageDao()

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
}
