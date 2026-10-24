package com.sowrobrana.photolock.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultItemDao {

    @Query("SELECT * FROM vault_items ORDER BY importedAtEpochMs DESC")
    fun observeAll(): Flow<List<VaultItemEntity>>

    @Query(
        "SELECT * FROM vault_items WHERE folderId IS NULL " +
            "ORDER BY importedAtEpochMs DESC"
    )
    fun observeRoot(): Flow<List<VaultItemEntity>>

    @Query(
        "SELECT * FROM vault_items WHERE folderId = :folderId " +
            "ORDER BY importedAtEpochMs DESC"
    )
    fun observeInFolder(folderId: String): Flow<List<VaultItemEntity>>

    @Query("SELECT * FROM vault_items WHERE id = :id")
    suspend fun getById(id: String): VaultItemEntity?

    @Query("SELECT COUNT(*) FROM vault_items")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: VaultItemEntity)

    @Query("DELETE FROM vault_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE vault_items SET folderId = :folderId WHERE id = :itemId")
    suspend fun setItemFolder(itemId: String, folderId: String?)

    @Query("UPDATE vault_items SET folderId = NULL WHERE folderId = :folderId")
    suspend fun clearFolder(folderId: String)
}

@Dao
interface FolderDao {

    @Query("SELECT * FROM folders ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getById(id: String): FolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(folder: FolderEntity)

    @Update
    suspend fun update(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface BreakInLogDao {

    @Query("SELECT * FROM break_in_log ORDER BY timestampEpochMs DESC")
    fun observeAll(): Flow<List<BreakInLogEntity>>

    @Insert
    suspend fun insert(entry: BreakInLogEntity)

    @Query("DELETE FROM break_in_log")
    suspend fun clearAll()
}
