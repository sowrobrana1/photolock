package com.sowrobrana.photolock.data

import android.content.Context
import android.net.Uri
import com.sowrobrana.photolock.crypto.CryptoManager
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Metadata for one item stored in the vault.
 *
 * The media bytes themselves live ONLY as an encrypted blob named
 * [encryptedFileName] inside the app-private `files/vault/` directory —
 * never in shared storage, never with a recognisable name or extension.
 */
data class VaultItem(
    val id: String,
    val folderId: String?,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val encryptedFileName: String,
    val importedAtEpochMs: Long,
    /** Video duration when known (from MediaMetadataRetriever at import). */
    val durationMs: Long? = null
)

/** A user-created folder inside the vault. */
data class VaultFolder(
    val id: String,
    val name: String,
    val createdAtEpochMs: Long
)

/**
 * Single source of truth for everything inside the vault.
 *
 * SCAFFOLD STATE: method signatures and storage layout are final-shaped, but
 * persistence is not implemented yet — the index is planned as a Room
 * database (`vault_index.db`) next to the blob directory. Every TODO below
 * is a Phase-1 task; see PLAN.md for the full design (import pipeline,
 * scoped-storage consent flows, export via MediaStore).
 */
class VaultRepository(
    context: Context,
    private val cryptoManager: CryptoManager = CryptoManager()
) {

    private val appContext: Context = context.applicationContext

    /** Directory holding the encrypted blobs. App-private: no permission needed. */
    val vaultDir: File = File(appContext.filesDir, VAULT_DIR_NAME).apply { mkdirs() }

    // ------------------------------------------------------------------ read

    /** All folders, newest first. TODO(Phase 1): back with Room Flow. */
    fun observeFolders(): Flow<List<VaultFolder>> = flowOf(emptyList())

    /** Items in [folderId] (null = vault root), newest first. TODO(Phase 1): Room. */
    fun observeItems(folderId: String?): Flow<List<VaultItem>> = flowOf(emptyList())

    /** Total bytes currently used by encrypted blobs. TODO(Phase 1). */
    suspend fun totalVaultSizeBytes(): Long =
        vaultDir.listFiles()?.sumOf { it.length() } ?: 0L

    // ----------------------------------------------------------------- write

    /**
     * Imports one media item:
     *  1. stream the source [sourceUri] through [CryptoManager.encrypt]
     *     into a fresh blob file (`<uuid>.pv`) in [vaultDir];
     *  2. record its metadata in the index;
     *  3. return the created [VaultItem].
     *
     * Removing the original from the device gallery is a SEPARATE, explicit
     * step (see [requestDeleteOriginals]) — never part of import, never silent.
     *
     * TODO(Phase 1): implement with contentResolver.openInputStream on an IO
     * dispatcher; generate a thumbnail from the decrypted stream and store it
     * as a second, smaller blob so the grid never decrypts full files.
     */
    suspend fun importMedia(sourceUri: Uri, folderId: String?): VaultItem {
        TODO("Phase 1: streaming-encrypt the source into vaultDir and index it")
    }

    /**
     * Opens a decrypted read stream for viewing/playback inside the app.
     * The plaintext exists only in memory / piped to the player — it is never
     * written back to disk unencrypted.
     *
     * TODO(Phase 1): return a decrypting InputStream over the blob file;
     * for video, prefer a pipe or a ContentProvider-style stream the player
     * can consume.
     */
    fun openDecryptedStream(item: VaultItem): InputStream {
        TODO("Phase 1: CryptoManager.decrypt over the blob InputStream")
    }

    /**
     * Exports (restores) one item back to the shared gallery:
     * decrypt → insert into MediaStore.Images/Video under `Pictures/PhotoLock`
     * (or `Movies/PhotoLock`) using the IS_PENDING flow, then mark complete.
     *
     * TODO(Phase 1): implement; export is always user-initiated per item or
     * per selection — nothing leaves the vault automatically.
     */
    suspend fun exportItem(item: VaultItem) {
        TODO("Phase 1: decrypt and write back through MediaStore (IS_PENDING flow)")
    }

    /**
     * Asks the system to delete (or trash) the ORIGINAL gallery files of
     * already-imported items. On Android 11+ this builds a
     * MediaStore.createDeleteRequest / createTrashRequest PendingIntent and
     * the SYSTEM shows the consent dialog — the app cannot and must not
     * delete silently. Returns the intent the UI layer should launch.
     *
     * TODO(Phase 1): implement per-API-level (30+ createTrashRequest /
     * createDeleteRequest; 29 recoverable-security-exception flow; <=28 the
     * legacy permission flow).
     */
    fun buildDeleteOriginalsRequest(items: List<VaultItem>): Any {
        TODO("Phase 1: MediaStore consent request (PendingIntent) per API level")
    }

    /** Permanently removes an item's encrypted blob + index row. TODO(Phase 1). */
    suspend fun deleteItem(item: VaultItem) {
        TODO("Phase 1: delete blob file, delete thumbnail, delete index row")
    }

    /** Creates a folder. TODO(Phase 1): insert into index. */
    suspend fun createFolder(name: String): VaultFolder {
        TODO("Phase 1: insert folder row and return it")
    }

    /** Renames a folder. TODO(Phase 1). */
    suspend fun renameFolder(folderId: String, newName: String) {
        TODO("Phase 1: update folder row")
    }

    /** Deletes a folder; its items move back to the vault root. TODO(Phase 1). */
    suspend fun deleteFolder(folderId: String) {
        TODO("Phase 1: null out folderId on items, delete folder row")
    }

    companion object {
        /** Sub-directory of `filesDir` holding encrypted blobs (`*.pv`). */
        const val VAULT_DIR_NAME = "vault"
    }
}
