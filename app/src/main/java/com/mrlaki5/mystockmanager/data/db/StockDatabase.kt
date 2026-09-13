package com.mrlaki5.mystockmanager.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mrlaki5.mystockmanager.data.db.dao.FolderDao
import com.mrlaki5.mystockmanager.data.db.dao.ImageDao
import com.mrlaki5.mystockmanager.data.db.entity.FolderEntity
import com.mrlaki5.mystockmanager.data.db.entity.ImageEntity

@Database(
    entities = [FolderEntity::class, ImageEntity::class],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class StockDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun imageDao(): ImageDao

    companion object {
        const val NAME = "stock.db"

        /**
         * Adds album-publication bookkeeping. A real migration rather than a
         * destructive fallback: by the time this shipped there were already imported
         * and generated images on device worth keeping.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE images ADD COLUMN mediaStoreUri TEXT")
                db.execSQL("ALTER TABLE images ADD COLUMN exportedAt INTEGER")
            }
        }

        /** Adds the optional per-event shooting location used to steer generation. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folders ADD COLUMN location TEXT")
            }
        }
    }
}
