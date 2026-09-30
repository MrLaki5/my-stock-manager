package com.mrlaki5.mystockmanager.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mrlaki5.mystockmanager.data.db.dao.FolderDao
import com.mrlaki5.mystockmanager.data.db.dao.ImageDao
import com.mrlaki5.mystockmanager.data.db.dao.PullDao
import com.mrlaki5.mystockmanager.data.db.dao.SyncDao
import com.mrlaki5.mystockmanager.data.db.entity.FolderEntity
import com.mrlaki5.mystockmanager.data.db.entity.ImageEntity
import com.mrlaki5.mystockmanager.data.db.entity.RemoteDeletionEntity
import com.mrlaki5.mystockmanager.data.db.entity.SyncFolderEntity
import com.mrlaki5.mystockmanager.data.db.entity.SyncImageEntity

@Database(
    entities = [
        FolderEntity::class,
        ImageEntity::class,
        SyncFolderEntity::class,
        SyncImageEntity::class,
        RemoteDeletionEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class StockDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun imageDao(): ImageDao
    abstract fun syncDao(): SyncDao
    abstract fun pullDao(): PullDao

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

        /**
         * Adds the EXIF capture date, which the editorial caption is built from. Rows
         * imported before this are backfilled from their album files at startup rather
         * than here: SQL cannot read a JPEG header.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE images ADD COLUMN capturedOn TEXT")
            }
        }

        /** NextCloud sync bookkeeping, kept out of `images` so uploads do not re-run the grid queries. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE images ADD COLUMN fileVersion INTEGER NOT NULL DEFAULT 1")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_folders` (`folderId` INTEGER NOT NULL, " +
                        "`remoteName` TEXT NOT NULL, `localName` TEXT NOT NULL, PRIMARY KEY(`folderId`), " +
                        "FOREIGN KEY(`folderId`) REFERENCES `folders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_images` (`imageId` INTEGER NOT NULL, " +
                        "`remoteFileName` TEXT NOT NULL, `syncedVersion` INTEGER, `remoteSize` INTEGER, " +
                        "`failedVersion` INTEGER, `error` TEXT, PRIMARY KEY(`imageId`), " +
                        "FOREIGN KEY(`imageId`) REFERENCES `images`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `remote_deletions` (`path` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`path`))"
                )
            }
        }

        /** Adds the user-arranged event order, seeded from the old newest-first sort. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folders ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE folders SET position = (SELECT COUNT(*) FROM folders f2 " +
                        "WHERE f2.createdAt > folders.createdAt " +
                        "OR (f2.createdAt = folders.createdAt AND f2.id > folders.id))"
                )
            }
        }

        /** Adds the per-image caption place; existing rows are read back from their files at startup. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE images ADD COLUMN captionPlace TEXT")
            }
        }
    }
}
