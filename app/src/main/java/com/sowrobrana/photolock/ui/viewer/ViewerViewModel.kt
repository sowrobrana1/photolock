package com.sowrobrana.photolock.ui.viewer

import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sowrobrana.photolock.data.VaultItem
import com.sowrobrana.photolock.data.VaultRepository
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ViewerUiState(
    val item: VaultItem? = null,
    /** Decoded photo (downsampled to a sane display size), when loaded. */
    val photoBitmap: Bitmap? = null,
    /** Decrypted temp file for the video player, when ready. */
    val videoFile: File? = null,
    val isLoading: Boolean = true,
    val error: String? = null
)

/**
 * Viewer logic.
 *
 *  - Photos: decrypted fully into memory and decoded with sampling
 *    (typical phone photos are a few MB; the plaintext never hits disk).
 *  - Videos: decrypted to a short-lived file in app-private cacheDir —
 *    the v1 GCM stream format is not seekable, and ExoPlayer needs a
 *    seekable source (see VaultRepository.decryptToTempFile). The file is
 *    deleted when this ViewModel is cleared (viewer closed) and swept at
 *    app start as a backstop.
 *
 * FLAG_SECURE for this screen is handled centrally: MainActivity applies
 * it whenever the vault session is unlocked.
 */
class ViewerViewModel(
    private val repository: VaultRepository,
    private val itemId: String
) : ViewModel() {

    private val _state = MutableStateFlow(ViewerUiState())
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _deleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val deleted: SharedFlow<Unit> = _deleted.asSharedFlow()

    /** MediaStore consent requests the screen must launch (system dialog). */
    private val _consentRequests =
        MutableSharedFlow<PendingIntent>(extraBufferCapacity = 1)
    val consentRequests: SharedFlow<PendingIntent> =
        _consentRequests.asSharedFlow()

    init {
        viewModelScope.launch {
            val item = repository.getItem(itemId)
            if (item == null) {
                _state.update {
                    it.copy(isLoading = false, error = "This item no longer exists")
                }
                return@launch
            }
            _state.update { it.copy(item = item) }
            try {
                if (item.isVideo) {
                    val file = repository.decryptToTempFile(item)
                    _state.update { it.copy(videoFile = file, isLoading = false) }
                } else {
                    val bytes = repository.loadItemBytes(item)
                    val bitmap = decodeSampled(bytes, MAX_DISPLAY_DIM_PX)
                    _state.update {
                        it.copy(
                            photoBitmap = bitmap,
                            isLoading = false,
                            error = if (bitmap == null) {
                                "Could not decode this image"
                            } else {
                                null
                            }
                        )
                    }
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(isLoading = false, error = "Could not open this item")
                }
            }
        }
    }

    fun exportItem() {
        val item = _state.value.item ?: return
        viewModelScope.launch {
            runCatching { repository.exportItem(item) }
                .onSuccess { _messages.tryEmit("Exported to your gallery") }
                .onFailure { _messages.tryEmit("Export failed") }
        }
    }

    /**
     * "Remove original from gallery" (menu action, shown only when the
     * item's source MediaStore URI was recorded at import): builds the
     * same system trash-consent request as the post-import flow and asks
     * the screen to launch it. Never deletes silently — if the request
     * cannot be built, the user is told instead.
     */
    fun removeOriginalFromGallery() {
        val item = _state.value.item ?: return
        val stored = item.sourceMediaUri ?: return
        val request = repository.buildOriginalsRemovalRequest(
            listOf(Uri.parse(stored))
        )
        if (request != null) {
            _consentRequests.tryEmit(request)
        } else {
            _messages.tryEmit("Could not remove the original from your gallery")
        }
    }

    fun deleteItem() {
        val item = _state.value.item ?: return
        viewModelScope.launch {
            runCatching { repository.deleteItem(item) }
            _deleted.tryEmit(Unit)
        }
    }

    override fun onCleared() {
        // Viewer closed: remove the plaintext temp video file immediately.
        _state.value.item?.let { repository.deleteTempFile(it) }
        super.onCleared()
    }

    private fun decodeSampled(bytes: ByteArray, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim &&
            bounds.outHeight / (sample * 2) >= maxDim
        ) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    companion object {
        private const val MAX_DISPLAY_DIM_PX = 2048
    }
}
