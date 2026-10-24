package com.sowrobrana.photolock.data

import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import com.sowrobrana.photolock.crypto.CryptoManager
import com.sowrobrana.photolock.data.local.BreakInLogEntity
import com.sowrobrana.photolock.data.local.FolderEntity
import com.sowrobrana.photolock.data.local.PhotoLockDatabase
import com.sowrobrana.photolock.data.local.VaultItemEntity
import com.sowrobrana.photolock.data.local.toDomain
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Metadata for one item stored in the vault.
 *
 * The media bytes themselves live ONLY as an encrypted blob named
 * [encryptedFileName] inside the app-private `files/vault/` directory —
 * never in shared storage, never with a recognisable name or extension.
 * [thumbnailFileName] points at a second, small encrypted blob holding a
 * downscaled JPEG so the grid never decrypts full-size media.
 */
data class VaultItem(
    val id: String,
    val folderId: String?,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val encryptedFileName: String,
    val thumbnailFileName: String? = null,
    val importedAtEpochMs: Long,
    /** Video duration when known (from MediaMetadataRetriever at import). */
    val durationMs: Long? = null
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val isImage: Boolean get() = mimeType.startsWith("image/")
}

/** A user-created folder inside the vault. */
data class VaultFolder(
    val id: String,
    val name: String,
    val createdAtEpochMs: Long
)

/**
 * One failed unlock attempt. Stored on-device ONLY (Room table
 * `break_in_log`); never uploaded anywhere — see PLAN.md §1.5.
 */
data class BreakInAttempt(
    val id: Long,
    val timestampEpochMs: Long,
    /** Consecutive-failure streak at the moment this attempt failed. */
    val consecutiveFailures: Int
)

/**
 * Single source of truth for everything inside the vault.
 *
 * Storage layout:
 *  - `files/vault/<uuid>.pv`        — encrypted media blob (CryptoManager format)
 *  - `files/vault/<uuid>_thumb.pv`  — encrypted thumbnail blob (small JPEG)
 *  - `vault_index.db` (Room)        — metadata index + folders + break-in log
 *  - `cache/vault_preview/`         — SHORT-LIVED plaintext temp files used only
 *    by the video player (see [decryptToTempFile] for why), deleted on close
 *    and swept at app start.
 */
class VaultRepository(
    context: Context,
    private val cryptoManager: CryptoManager,
    private val database: PhotoLockDatabase
) {

    private val appContext: Context = context.applicationContext
    private val itemDao = database.itemDao()
    private val folderDao = database.folderDao()
    private val breakInDao = database.breakInLogDao()

    /** Directory holding the encrypted blobs. App-private: no permission needed. */
    val vaultDir: File = File(appContext.filesDir, VAULT_DIR_NAME).apply { mkdirs() }

    /** App-private cache for short-lived decrypted video files (viewer only). */
    val previewCacheDir: File =
        File(appContext.cacheDir, PREVIEW_DIR_NAME).apply { mkdirs() }

    // ------------------------------------------------------------------ read

    /** All folders, newest first. */
    fun observeFolders(): Flow<List<VaultFolder>> =
        folderDao.observeAll().map { list -> list.map { it.toDomain() } }

    /** All items regardless of folder, newest first. */
    fun observeAllItems(): Flow<List<VaultItem>> =
        itemDao.observeAll().map { list -> list.map { it.toDomain() } }

    /** Items in [folderId] (null = vault root), newest first. */
    fun observeItems(folderId: String?): Flow<List<VaultItem>> =
        if (folderId == null) {
            itemDao.observeRoot().map { list -> list.map { it.toDomain() } }
        } else {
            itemDao.observeInFolder(folderId).map { list -> list.map { it.toDomain() } }
        }

    /** Live item count (Settings storage summary). */
    fun observeItemCount(): Flow<Int> = itemDao.observeCount()

    suspend fun getItem(id: String): VaultItem? =
        itemDao.getById(id)?.toDomain()

    /** Total bytes currently used by encrypted blobs (media + thumbnails). */
    suspend fun totalVaultSizeBytes(): Long = withContext(Dispatchers.IO) {
        vaultDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    // --------------------------------------------------------- break-in log

    fun observeBreakInLog(): Flow<List<BreakInAttempt>> =
        breakInDao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun recordBreakInAttempt(consecutiveFailures: Int) {
        breakInDao.insert(
            BreakInLogEntity(
                timestampEpochMs = System.currentTimeMillis(),
                consecutiveFailures = consecutiveFailures
            )
        )
    }

    suspend fun clearBreakInLog() = breakInDao.clearAll()

    // ---------------------------------------------------------------- import

    /**
     * Imports one media item:
     *  1. stream the source [sourceUri] through [CryptoManager.encrypt]
     *     into a fresh blob file (`<uuid>.pv`) in [vaultDir];
     *  2. verify the blob by decrypting it to a sink (integrity + size
     *     check) — a failed import deletes the partial blob and throws;
     *  3. generate an encrypted thumbnail (photo frame / video frame);
     *  4. record metadata in the Room index and return the [VaultItem].
     *
     * Removing the original from the device gallery is a SEPARATE, explicit
     * step (see [buildOriginalsRemovalRequest]) — never part of import,
     * never silent.
     */
    suspend fun importMedia(sourceUri: Uri, folderId: String?): VaultItem =
        withContext(Dispatchers.IO) {
            val resolver = appContext.contentResolver
            val meta = querySourceMeta(sourceUri)
            val id = UUID.randomUUID().toString()
            val blobName = "$id.pv"
            val thumbName = "${id}_thumb.pv"
            val blobFile = File(vaultDir, blobName)
            val thumbFile = File(vaultDir, thumbName)

            try {
                val input = resolver.openInputStream(sourceUri)
                    ?: throw IOException("Could not open selected media")
                input.use { stream ->
                    FileOutputStream(blobFile).use { out ->
                        cryptoManager.encrypt(stream, out)
                    }
                }

                // Integrity check before the item becomes visible in the vault.
                verifyBlob(blobFile, meta.sizeBytes)

                var durationMs: Long? = null
                val thumbOk = when {
                    meta.mimeType.startsWith("image/") ->
                        generateThumbnailBlob(
                            decodeImageThumbnail(sourceUri), thumbFile
                        )

                    meta.mimeType.startsWith("video/") -> {
                        val (frame, duration) = readVideoMeta(sourceUri)
                        durationMs = duration
                        generateThumbnailBlob(frame, thumbFile)
                    }

                    else -> false
                }

                val entity = VaultItemEntity(
                    id = id,
                    folderId = folderId,
                    displayName = meta.displayName,
                    mimeType = meta.mimeType,
                    sizeBytes = if (meta.sizeBytes >= 0) meta.sizeBytes else blobPlainSize(blobFile),
                    encryptedFileName = blobName,
                    thumbnailFileName = if (thumbOk) thumbName else null,
                    importedAtEpochMs = System.currentTimeMillis(),
                    durationMs = durationMs
                )
                itemDao.insert(entity)
                entity.toDomain()
            } catch (t: Throwable) {
                blobFile.delete()
                thumbFile.delete()
                throw t
            }
        }

    // ------------------------------------------------------------ decryption

    /**
     * Opens a decrypted read stream for an item. The plaintext is piped
     * through memory from a background writer thread — it is never written
     * to disk. Suitable for photos and sequential readers; NOT seekable,
     * so the video player uses [decryptToTempFile] instead.
     */
    fun openDecryptedStream(item: VaultItem): InputStream {
        val blob = File(vaultDir, item.encryptedFileName)
        val pipeIn = PipedInputStream(PIPE_BUFFER_SIZE)
        val pipeOut = PipedOutputStream(pipeIn)
        val writer = Thread(
            {
                try {
                    FileInputStream(blob).use { input ->
                        cryptoManager.decrypt(input, pipeOut)
                    }
                } catch (t: Throwable) {
                    // Dropping the pipe signals EOF to the reader.
                } finally {
                    runCatching { pipeOut.close() }
                }
            },
            "photolock-decrypt"
        )
        writer.isDaemon = true
        writer.start()
        return pipeIn
    }

    /** Decrypts an item fully into memory (photo viewer path). */
    suspend fun loadItemBytes(item: VaultItem): ByteArray =
        withContext(Dispatchers.IO) {
            val out = ByteArrayOutputStream()
            FileInputStream(File(vaultDir, item.encryptedFileName)).use { input ->
                cryptoManager.decrypt(input, out)
            }
            out.toByteArray()
        }

    /** Decrypts an item's thumbnail blob into a Bitmap, or null. */
    suspend fun loadThumbnailBitmap(item: VaultItem): Bitmap? =
        withContext(Dispatchers.IO) {
            val thumbName = item.thumbnailFileName ?: return@withContext null
            val thumbFile = File(vaultDir, thumbName)
            if (!thumbFile.exists()) return@withContext null
            try {
                val out = ByteArrayOutputStream()
                FileInputStream(thumbFile).use { input ->
                    cryptoManager.decrypt(input, out)
                }
                val bytes = out.toByteArray()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (t: Throwable) {
                null
            }
        }

    /**
     * Decrypts an item to a plaintext file inside the app-private cache —
     * used ONLY for video playback.
     *
     * Why not stream? The v1 CryptoManager format is a single GCM stream
     * with no random access, and ExoPlayer needs a seekable source for
     * container formats like MP4. A pipe would break seeking and stall on
     * many files. The temp file lives in app-private cacheDir (invisible
     * to other apps and the gallery), the viewer deletes it on close via
     * [deleteTempFile], and [sweepTempFiles] clears orphans at app start.
     * PLAN.md Phase 2 (chunked-AEAD format) is what unlocks true streaming.
     */
    suspend fun decryptToTempFile(item: VaultItem): File =
        withContext(Dispatchers.IO) {
            val target = File(previewCacheDir, item.id + TEMP_FILE_SUFFIX)
            FileInputStream(File(vaultDir, item.encryptedFileName)).use { input ->
                FileOutputStream(target).use { out ->
                    cryptoManager.decrypt(input, out)
                }
            }
            target
        }

    /** Deletes the temp playback file for [item], if any. */
    fun deleteTempFile(item: VaultItem) {
        File(previewCacheDir, item.id + TEMP_FILE_SUFFIX).delete()
    }

    /** Removes any leftover preview temp files (called at app start). */
    fun sweepTempFiles() {
        previewCacheDir.listFiles()?.forEach { it.delete() }
    }

    // ---------------------------------------------------------------- export

    /**
     * Exports (restores) one item back to the shared gallery:
     * decrypt → MediaStore insert into `Pictures/PhotoLock` (or
     * `Movies/PhotoLock`) using the IS_PENDING flow on API 29+; on API
     * 26–28 writes to the public Pictures/Movies folder and triggers a
     * media scan (that path needs WRITE_EXTERNAL_STORAGE, which the UI
     * requests first). Always user-initiated.
     */
    suspend fun exportItem(item: VaultItem) {
        withContext(Dispatchers.IO) {
            val blob = File(vaultDir, item.encryptedFileName)
            require(blob.exists()) { "Vault blob missing for item ${item.id}" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportViaMediaStore(item, blob)
            } else {
                exportLegacy(item, blob)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun exportViaMediaStore(item: VaultItem, blob: File) {
        val resolver = appContext.contentResolver
        val collection = if (item.isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relativePath = if (item.isVideo) {
            "${Environment.DIRECTORY_MOVIES}/PhotoLock"
        } else {
            "${Environment.DIRECTORY_PICTURES}/PhotoLock"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore insert failed for ${item.displayName}")
        try {
            val out = resolver.openOutputStream(uri)
                ?: throw IOException("Could not open export stream for ${item.displayName}")
            out.use { stream ->
                FileInputStream(blob).use { input ->
                    cryptoManager.decrypt(input, stream)
                }
            }
            val done = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    private fun exportLegacy(item: VaultItem, blob: File) {
        val publicDir = Environment.getExternalStoragePublicDirectory(
            if (item.isVideo) Environment.DIRECTORY_MOVIES
            else Environment.DIRECTORY_PICTURES
        )
        val dir = File(publicDir, "PhotoLock").apply { mkdirs() }
        val target = uniqueFile(dir, item.displayName)
        FileOutputStream(target).use { out ->
            FileInputStream(blob).use { input ->
                cryptoManager.decrypt(input, out)
            }
        }
        MediaScannerConnection.scanFile(
            appContext,
            arrayOf(target.absolutePath),
            arrayOf(item.mimeType),
            null
        )
    }

    private fun uniqueFile(dir: File, displayName: String): File {
        var candidate = File(dir, displayName)
        if (!candidate.exists()) return candidate
        val dot = displayName.lastIndexOf('.')
        val base = if (dot > 0) displayName.substring(0, dot) else displayName
        val ext = if (dot > 0) displayName.substring(dot) else ""
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($counter)$ext")
            counter++
        }
        return candidate
    }

    // ------------------------------------------------- delete original files

    /**
     * Builds the SYSTEM consent request for removing the original gallery
     * files at [uris] after import. On API 30+ this is a
     * [MediaStore.createTrashRequest] PendingIntent (recoverable trash —
     * preferred per PLAN.md §8); the UI launches its IntentSender and the
     * system shows the dialog naming the files. The app cannot and must
     * not delete silently.
     *
     * Returns null below API 30 — use [deleteOriginalsLegacy] there.
     */
    fun buildOriginalsRemovalRequest(uris: List<Uri>): PendingIntent? {
        if (uris.isEmpty()) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return MediaStore.createTrashRequest(
            appContext.contentResolver,
            uris,
            true
        )
    }

    /**
     * Legacy original-removal for API 26–29.
     *  - API 29: deletes via ContentResolver; if the system demands user
     *    consent it throws [RecoverableSecurityException] — the returned
     *    PendingIntent is that exception's user action, which the UI
     *    launches; the caller should retry once after consent.
     *  - API 26–28: plain delete under WRITE_EXTERNAL_STORAGE (the UI
     *    requests the permission first); failures are skipped silently.
     *
     * Returns a consent PendingIntent to launch, or null when finished.
     */
    suspend fun deleteOriginalsLegacy(uris: List<Uri>): PendingIntent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return deleteOriginalsApi29(uris)
        }
        withContext(Dispatchers.IO) {
            val resolver = appContext.contentResolver
            for (uri in uris) {
                runCatching { resolver.delete(uri, null, null) }
            }
        }
        return null
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun deleteOriginalsApi29(uris: List<Uri>): PendingIntent? =
        withContext(Dispatchers.IO) {
            val resolver = appContext.contentResolver
            var consent: PendingIntent? = null
            for (uri in uris) {
                try {
                    resolver.delete(uri, null, null)
                } catch (e: RecoverableSecurityException) {
                    if (consent == null) consent = e.userAction.actionIntent
                } catch (e: SecurityException) {
                    // Not our media and no consent path — skip this one.
                }
            }
            consent
        }

    // ----------------------------------------------------------------- write

    /** Permanently removes an item's encrypted blobs + index row. */
    suspend fun deleteItem(item: VaultItem) {
        withContext(Dispatchers.IO) {
            File(vaultDir, item.encryptedFileName).delete()
            item.thumbnailFileName?.let { File(vaultDir, it).delete() }
            deleteTempFile(item)
        }
        itemDao.deleteById(item.id)
    }

    /** Moves an item into [folderId] (null = vault root). */
    suspend fun moveItemToFolder(itemId: String, folderId: String?) {
        itemDao.setItemFolder(itemId, folderId)
    }

    /** Creates a folder. */
    suspend fun createFolder(name: String): VaultFolder {
        val folder = FolderEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            createdAtEpochMs = System.currentTimeMillis()
        )
        folderDao.insert(folder)
        return folder.toDomain()
    }

    /** Renames a folder. */
    suspend fun renameFolder(folderId: String, newName: String) {
        val current = folderDao.getById(folderId) ?: return
        folderDao.update(current.copy(name = newName))
    }

    /** Deletes a folder; its items move back to the vault root. */
    suspend fun deleteFolder(folderId: String) {
        itemDao.clearFolder(folderId)
        folderDao.deleteById(folderId)
    }

    // --------------------------------------------------------------- helpers

    private data class SourceMeta(
        val displayName: String,
        val mimeType: String,
        /** -1 when the provider doesn't report a size. */
        val sizeBytes: Long
    )

    private fun querySourceMeta(uri: Uri): SourceMeta {
        val resolver = appContext.contentResolver
        var name: String? = null
        var size = -1L
        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) name = cursor.getString(nameIdx)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        size = cursor.getLong(sizeIdx)
                    }
                }
            }
        }
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
            ?: "application/octet-stream"
        return SourceMeta(
            displayName = name ?: "media_${System.currentTimeMillis()}",
            mimeType = mime,
            sizeBytes = size
        )
    }

    /** Decrypts [blob] to a counting sink; optionally asserts plaintext size. */
    private fun verifyBlob(blob: File, expectedPlainSize: Long) {
        var total = 0L
        val sink = object : OutputStream() {
            override fun write(b: Int) {
                total++
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                total += len
            }
        }
        FileInputStream(blob).use { input -> cryptoManager.decrypt(input, sink) }
        if (expectedPlainSize >= 0) {
            check(total == expectedPlainSize) {
                "Import verification failed: decrypted size $total != expected $expectedPlainSize"
            }
        }
    }

    /** Plaintext size of a blob, measured by decrypting it to a sink. */
    private fun blobPlainSize(blob: File): Long {
        var total = 0L
        val sink = object : OutputStream() {
            override fun write(b: Int) {
                total++
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                total += len
            }
        }
        FileInputStream(blob).use { input -> cryptoManager.decrypt(input, sink) }
        return total
    }

    private fun generateThumbnailBlob(source: Bitmap?, thumbFile: File): Boolean {
        if (source == null) return false
        return try {
            val jpeg = ByteArrayOutputStream()
            source.compress(Bitmap.CompressFormat.JPEG, THUMB_JPEG_QUALITY, jpeg)
            val bytes = jpeg.toByteArray()
            FileOutputStream(thumbFile).use { out ->
                cryptoManager.encrypt(ByteArrayInputStream(bytes), out)
            }
            true
        } catch (t: Throwable) {
            thumbFile.delete()
            false
        } finally {
            source.recycle()
        }
    }

    private fun decodeImageThumbnail(uri: Uri): Bitmap? {
        val resolver = appContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= THUMBNAIL_SIZE_PX &&
            bounds.outHeight / (sample * 2) >= THUMBNAIL_SIZE_PX
        ) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, opts)
        } ?: return null
        return scaleDown(decoded, THUMBNAIL_SIZE_PX)
    }

    /** First usable frame + duration for a video, best-effort. */
    private fun readVideoMeta(uri: Uri): Pair<Bitmap?, Long?> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
            val frame = retriever.getFrameAtTime(
                VIDEO_FRAME_AT_US,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.getFrameAtTime(
                0,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
            val scaled = frame?.let { scaleDown(it, THUMBNAIL_SIZE_PX) }
            if (scaled !== frame) frame?.recycle()
            scaled to duration
        } catch (t: Throwable) {
            null to null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxDim: Int): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / largest.toFloat()
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    companion object {
        /** Sub-directory of `filesDir` holding encrypted blobs (`*.pv`). */
        const val VAULT_DIR_NAME = "vault"

        /** Sub-directory of `cacheDir` for short-lived video temp files. */
        const val PREVIEW_DIR_NAME = "vault_preview"

        private const val TEMP_FILE_SUFFIX = ".tmp"
        private const val THUMBNAIL_SIZE_PX = 384
        private const val THUMB_JPEG_QUALITY = 82
        private const val VIDEO_FRAME_AT_US = 500_000L
        private const val PIPE_BUFFER_SIZE = 64 * 1024
    }
}
