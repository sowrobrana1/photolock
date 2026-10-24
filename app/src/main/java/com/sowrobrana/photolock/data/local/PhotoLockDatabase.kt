package com.sowrobrana.photolock.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The vault index database (`vault_index.db`, in the app's private databases
 * directory). Holds item/folder metadata and the on-device break-in log —
 * never media bytes (those are encrypted blobs in files/vault/).
 */
@Database(
    entities = [VaultItemEntity::class, FolderEntity::class, BreakInLogEntity::class],
    version = 2,
    exportSchema = false
)
abstract class PhotoLockDatabase : RoomDatabase() {

    abstract fun itemDao(): VaultItemDao
    abstract fun folderDao(): FolderDao
    abstract fun breakInLogDao(): BreakInLogDao

    companion object {
        @Volatile
        private var instance: PhotoLockDatabase? = null

        /**
         * v1 → v2: `vault_items` gains the nullable `sourceMediaUri`
         * column (MediaStore URI of the gallery original, recorded at
         * import). Existing rows get NULL — their originals simply can't
         * be offered for later removal.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE vault_items ADD COLUMN sourceMediaUri TEXT"
                )
            }
        }

        fun getInstance(context: Context): PhotoLockDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PhotoLockDatabase::class.java,
                    "vault_index.db"
                ).addMigrations(MIGRATION_1_2)
                    .build().also { instance = it }
            }
    }
}
