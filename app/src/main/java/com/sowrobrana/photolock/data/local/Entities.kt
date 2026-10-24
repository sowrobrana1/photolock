package com.sowrobrana.photolock.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sowrobrana.photolock.data.BreakInAttempt
import com.sowrobrana.photolock.data.VaultFolder
import com.sowrobrana.photolock.data.VaultItem

/**
 * Room entities for the vault index.
 *
 * The index holds METADATA ONLY — media bytes live exclusively as encrypted
 * blobs in `files/vault/` (see VaultRepository). A folder delete nulls the
 * items' folderId so items fall back to the vault root (PLAN.md §1.3).
 */

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAtEpochMs: Long
)

@Entity(
    tableName = "vault_items",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("folderId")]
)
data class VaultItemEntity(
    @PrimaryKey val id: String,
    val folderId: String?,
    val displayName: String,
    val mimeType: String,
    /** Plaintext size of the original media, in bytes. */
    val sizeBytes: Long,
    /** Encrypted media blob file name inside files/vault/ (`<uuid>.pv`). */
    val encryptedFileName: String,
    /** Encrypted thumbnail blob file name, or null if generation failed. */
    val thumbnailFileName: String?,
    val importedAtEpochMs: Long,
    /** Video duration when known (from MediaMetadataRetriever at import). */
    val durationMs: Long? = null
)

/**
 * One row per failed unlock attempt. On-device ONLY — this table is never
 * synced or uploaded anywhere (PLAN.md §1.5).
 */
@Entity(tableName = "break_in_log")
data class BreakInLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampEpochMs: Long,
    /** Consecutive-failure streak at the moment this attempt failed. */
    val consecutiveFailures: Int
)

// ------------------------------------------------------------ mappers

fun FolderEntity.toDomain(): VaultFolder =
    VaultFolder(id = id, name = name, createdAtEpochMs = createdAtEpochMs)

fun VaultItemEntity.toDomain(): VaultItem = VaultItem(
    id = id,
    folderId = folderId,
    displayName = displayName,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    encryptedFileName = encryptedFileName,
    thumbnailFileName = thumbnailFileName,
    importedAtEpochMs = importedAtEpochMs,
    durationMs = durationMs
)

fun BreakInLogEntity.toDomain(): BreakInAttempt = BreakInAttempt(
    id = id,
    timestampEpochMs = timestampEpochMs,
    consecutiveFailures = consecutiveFailures
)
