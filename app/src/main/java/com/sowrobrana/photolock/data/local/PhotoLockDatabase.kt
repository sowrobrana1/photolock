package com.sowrobrana.photolock.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The vault index database (`vault_index.db`, in the app's private databases
 * directory). Holds item/folder metadata and the on-device break-in log —
 * never media bytes (those are encrypted blobs in files/vault/).
 */
@Database(
    entities = [VaultItemEntity::class, FolderEntity::class, BreakInLogEntity::class],
    version = 1,
    exportSchema = false
)
abstract class PhotoLockDatabase : RoomDatabase() {

    abstract fun itemDao(): VaultItemDao
    abstract fun folderDao(): FolderDao
    abstract fun breakInLogDao(): BreakInLogDao

    companion object {
        @Volatile
        private var instance: PhotoLockDatabase? = null

        fun getInstance(context: Context): PhotoLockDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PhotoLockDatabase::class.java,
                    "vault_index.db"
                ).build().also { instance = it }
            }
    }
}
