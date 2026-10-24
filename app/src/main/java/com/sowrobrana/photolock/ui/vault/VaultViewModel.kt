package com.sowrobrana.photolock.ui.vault

import android.app.PendingIntent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sowrobrana.photolock.data.VaultFolder
import com.sowrobrana.photolock.data.VaultItem
import com.sowrobrana.photolock.data.VaultRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which slice of the vault the grid is showing. */
sealed interface FolderFilter {
    data object All : FolderFilter
    data object Root : FolderFilter
    data class Folder(val id: String) : FolderFilter
}

data class VaultUiState(
    val folders: List<VaultFolder> = emptyList(),
    val filter: FolderFilter = FolderFilter.All,
    val items: List<VaultItem> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val isImporting: Boolean = false,
    val pendingOriginalsCount: Int = 0,
    val showDeleteOriginalsAsk: Boolean = false
) {
    val selectionMode: Boolean get() = selectedIds.isNotEmpty()
    val selectedItems: List<VaultItem>
        get() = items.filter { it.id in selectedIds }
}

/** One-shot actions the screen must perform (system UI / snackbars). */
sealed interface VaultEvent {
    /** Launch a MediaStore consent dialog via its IntentSender. */
    data class LaunchIntentSender(val pendingIntent: PendingIntent) : VaultEvent
    data class Message(val text: String) : VaultEvent
}

/**
 * Vault grid logic: folder filtering, Photo Picker import pipeline,
 * multi-select actions (export / move / delete), folder management, and
 * the post-import "remove originals?" consent choreography.
 *
 * Originals are NEVER deleted silently: on API 30+ the system trash dialog
 * is launched; on API 29 the recoverable-security consent intent is
 * launched; on API 26–28 the UI requests the legacy write permission first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewModel(
    private val repository: VaultRepository
) : ViewModel() {

    /** UI-owned slice of state; repository data arrives via flows below. */
    private data class InternalState(
        val filter: FolderFilter = FolderFilter.All,
        val selectedIds: Set<String> = emptySet(),
        val isImporting: Boolean = false,
        val pendingOriginals: List<Uri> = emptyList(),
        val showDeleteOriginalsAsk: Boolean = false
    )

    private val internal = MutableStateFlow(InternalState())

    /** Set when API 29 consent was launched and deletes must be retried. */
    private var retryLegacyDeleteAfterConsent = false

    private val itemsFlow = internal
        .map { it.filter }
        .distinctUntilChanged()
        .flatMapLatest { filter ->
            when (filter) {
                FolderFilter.All -> repository.observeAllItems()
                FolderFilter.Root -> repository.observeItems(null)
                is FolderFilter.Folder -> repository.observeItems(filter.id)
            }
        }

    val state: StateFlow<VaultUiState> = combine(
        repository.observeFolders(),
        internal,
        itemsFlow
    ) { folders, internalState, items ->
        VaultUiState(
            folders = folders,
            filter = internalState.filter,
            items = items,
            selectedIds = internalState.selectedIds,
            isImporting = internalState.isImporting,
            pendingOriginalsCount = internalState.pendingOriginals.size,
            showDeleteOriginalsAsk = internalState.showDeleteOriginalsAsk
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = VaultUiState()
    )

    private val _events = MutableSharedFlow<VaultEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<VaultEvent> = _events.asSharedFlow()

    // ------------------------------------------------------------- filtering

    fun onFilterSelected(filter: FolderFilter) {
        internal.update { it.copy(filter = filter, selectedIds = emptySet()) }
    }

    // ------------------------------------------------------------- selection

    /** In selection mode a tap toggles; otherwise the screen navigates. */
    fun toggleSelection(item: VaultItem) {
        internal.update { current ->
            val next = if (item.id in current.selectedIds) {
                current.selectedIds - item.id
            } else {
                current.selectedIds + item.id
            }
            current.copy(selectedIds = next)
        }
    }

    fun clearSelection() {
        internal.update { it.copy(selectedIds = emptySet()) }
    }

    // ---------------------------------------------------------------- import

    /** Called with the Photo Picker result. Imports into the open folder. */
    fun onImportPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val targetFolderId =
            (internal.value.filter as? FolderFilter.Folder)?.id
        viewModelScope.launch {
            internal.update { it.copy(isImporting = true) }
            val importedFrom = mutableListOf<Uri>()
            var failures = 0
            for (uri in uris) {
                try {
                    repository.importMedia(uri, targetFolderId)
                    importedFrom += uri
                } catch (t: Throwable) {
                    failures++
                }
            }
            internal.update {
                it.copy(
                    isImporting = false,
                    pendingOriginals = importedFrom,
                    showDeleteOriginalsAsk = importedFrom.isNotEmpty()
                )
            }
            if (importedFrom.isNotEmpty()) {
                _events.tryEmit(
                    VaultEvent.Message(
                        if (failures == 0) {
                            "Imported ${importedFrom.size} item(s) into the vault"
                        } else {
                            "Imported ${importedFrom.size} item(s); $failures failed"
                        }
                    )
                )
            } else {
                _events.tryEmit(VaultEvent.Message("Import failed"))
            }
        }
    }

    // ------------------------------------------------- delete-originals flow

    /** User tapped "Remove originals" on the post-import dialog. */
    fun onDeleteOriginalsConfirmed() {
        val uris = internal.value.pendingOriginals
        internal.update { it.copy(showDeleteOriginalsAsk = false) }
        if (uris.isEmpty()) return
        val request = repository.buildOriginalsRemovalRequest(uris)
        if (request != null) {
            // API 30+: system trash-consent dialog; originals stay put
            // unless the user confirms there.
            internal.update { it.copy(pendingOriginals = emptyList()) }
            _events.tryEmit(VaultEvent.LaunchIntentSender(request))
            return
        }
        // API 26–29 legacy path.
        viewModelScope.launch {
            val consent = repository.deleteOriginalsLegacy(uris)
            if (consent != null) {
                // API 29: system consent needed for at least one file.
                retryLegacyDeleteAfterConsent = true
                _events.tryEmit(VaultEvent.LaunchIntentSender(consent))
            } else {
                internal.update { it.copy(pendingOriginals = emptyList()) }
            }
        }
    }

    /** User tapped "Keep originals". */
    fun onDeleteOriginalsDeclined() {
        internal.update {
            it.copy(showDeleteOriginalsAsk = false, pendingOriginals = emptyList())
        }
    }

    /** A launched consent dialog finished (any result). */
    fun onConsentDialogFinished() {
        if (!retryLegacyDeleteAfterConsent) return
        retryLegacyDeleteAfterConsent = false
        val uris = internal.value.pendingOriginals
        internal.update { it.copy(pendingOriginals = emptyList()) }
        if (uris.isEmpty()) return
        viewModelScope.launch {
            // Best-effort second pass now that consent was (maybe) granted.
            repository.deleteOriginalsLegacy(uris)
        }
    }

    /** True on API < 29, where the UI must hold WRITE_EXTERNAL_STORAGE. */
    fun needsLegacyWritePermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    // ------------------------------------------------------- selection actions

    fun exportSelected() {
        val items = state.value.selectedItems
        if (items.isEmpty()) return
        viewModelScope.launch {
            var exported = 0
            for (item in items) {
                runCatching { repository.exportItem(item) }
                    .onSuccess { exported++ }
            }
            clearSelection()
            _events.tryEmit(
                VaultEvent.Message(
                    if (exported == items.size) {
                        "Exported $exported item(s) to your gallery"
                    } else {
                        "Exported $exported of ${items.size} item(s)"
                    }
                )
            )
        }
    }

    fun deleteSelected() {
        val items = state.value.selectedItems
        if (items.isEmpty()) return
        viewModelScope.launch {
            for (item in items) {
                runCatching { repository.deleteItem(item) }
            }
            clearSelection()
            _events.tryEmit(VaultEvent.Message("Deleted ${items.size} item(s)"))
        }
    }

    fun moveSelectedTo(folderId: String?) {
        val items = state.value.selectedItems
        if (items.isEmpty()) return
        viewModelScope.launch {
            for (item in items) {
                runCatching { repository.moveItemToFolder(item.id, folderId) }
            }
            clearSelection()
            _events.tryEmit(VaultEvent.Message("Moved ${items.size} item(s)"))
        }
    }

    // --------------------------------------------------------------- folders

    fun createFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { repository.createFolder(trimmed) }
    }

    fun renameFolder(folderId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { repository.renameFolder(folderId, trimmed) }
    }

    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            repository.deleteFolder(folderId)
            if ((internal.value.filter as? FolderFilter.Folder)?.id == folderId) {
                internal.update { it.copy(filter = FolderFilter.All) }
            }
        }
    }
}
