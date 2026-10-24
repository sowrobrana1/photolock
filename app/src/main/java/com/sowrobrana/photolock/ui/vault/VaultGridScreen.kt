package com.sowrobrana.photolock.ui.vault

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sowrobrana.photolock.R
import com.sowrobrana.photolock.data.VaultFolder
import com.sowrobrana.photolock.data.VaultItem
import com.sowrobrana.photolock.ui.components.VaultThumbnail
import com.sowrobrana.photolock.ui.components.formatDuration

/**
 * Vault contents grid (real implementation).
 *
 *  - Items come from [VaultViewModel] (Room index); each cell renders the
 *    item's encrypted thumbnail, decrypted in memory only.
 *  - FAB launches the system Photo Picker (no storage permission on
 *    Android 13+); after import the user is ASKED about removing the
 *    originals — removal always goes through the system consent dialog.
 *  - Tap opens the viewer; long-press starts multi-select (export / move /
 *    delete). Folder chips filter the grid; long-press a folder chip to
 *    rename/delete it.
 *  - No ads here (PLAN.md §5) — ads never sit on private-media screens.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VaultGridScreen(
    viewModel: VaultViewModel,
    repository: com.sowrobrana.photolock.data.VaultRepository,
    onOpenSettings: () -> Unit,
    onOpenItem: (String) -> Unit,
    onLockNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // ------------------------------------------------------------ launchers

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> viewModel.onImportPicked(uris) }

    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { viewModel.onConsentDialogFinished() }

    // Legacy (API 26–28) write-permission gate for export / original removal.
    var pendingLegacyAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pendingLegacyAction?.invoke()
        pendingLegacyAction = null
    }

    fun withLegacyWritePermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingLegacyAction = action
            legacyPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is VaultEvent.Message ->
                    snackbarHostState.showSnackbar(event.text)

                is VaultEvent.LaunchIntentSender -> consentLauncher.launch(
                    IntentSenderRequest.Builder(
                        event.pendingIntent.intentSender
                    ).build()
                )
            }
        }
    }

    // -------------------------------------------------------------- dialogs

    var showCreateFolder by remember { mutableStateOf(false) }
    var folderNameInput by remember { mutableStateOf("") }
    var folderToManage by remember { mutableStateOf<VaultFolder?>(null) }
    var folderToRename by remember { mutableStateOf<VaultFolder?>(null) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showDeleteItemsConfirm by remember { mutableStateOf(false) }

    if (showCreateFolder) {
        AlertDialog(
            onDismissRequest = { showCreateFolder = false },
            title = { Text(stringResource(R.string.folder_new)) },
            text = {
                OutlinedTextField(
                    value = folderNameInput,
                    onValueChange = { folderNameInput = it },
                    label = { Text(stringResource(R.string.folder_name)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.createFolder(folderNameInput)
                        folderNameInput = ""
                        showCreateFolder = false
                    }
                ) { Text(stringResource(R.string.action_create)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolder = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    folderToRename?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToRename = null },
            title = { Text(stringResource(R.string.folder_rename)) },
            text = {
                OutlinedTextField(
                    value = folderNameInput,
                    onValueChange = { folderNameInput = it },
                    label = { Text(stringResource(R.string.folder_name)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameFolder(folder.id, folderNameInput)
                        folderNameInput = ""
                        folderToRename = null
                    }
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { folderToRename = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    folderToManage?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToManage = null },
            title = { Text(folder.name) },
            text = { Text(stringResource(R.string.folder_manage_hint)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        folderNameInput = folder.name
                        folderToRename = folder
                        folderToManage = null
                    }
                ) { Text(stringResource(R.string.folder_rename)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteFolder(folder.id)
                        folderToManage = null
                    }
                ) {
                    Text(
                        text = stringResource(R.string.folder_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        )
    }

    if (showMoveDialog) {
        AlertDialog(
            onDismissRequest = { showMoveDialog = false },
            title = { Text(stringResource(R.string.vault_move_to)) },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            viewModel.moveSelectedTo(null)
                            showMoveDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.vault_root)) }
                    state.folders.forEach { folder ->
                        TextButton(
                            onClick = {
                                viewModel.moveSelectedTo(folder.id)
                                showMoveDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(folder.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showMoveDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showDeleteItemsConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteItemsConfirm = false },
            title = { Text(stringResource(R.string.vault_delete_items_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.vault_delete_items_body,
                        state.selectedIds.size
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSelected()
                        showDeleteItemsConfirm = false
                    }
                ) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteItemsConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (state.showDeleteOriginalsAsk) {
        AlertDialog(
            onDismissRequest = { viewModel.onDeleteOriginalsDeclined() },
            title = { Text(stringResource(R.string.vault_remove_originals_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.vault_remove_originals_body,
                        state.pendingOriginalsCount
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (viewModel.needsLegacyWritePermission()) {
                            withLegacyWritePermission {
                                viewModel.onDeleteOriginalsConfirmed()
                            }
                        } else {
                            viewModel.onDeleteOriginalsConfirmed()
                        }
                    }
                ) { Text(stringResource(R.string.vault_remove_originals_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.onDeleteOriginalsDeclined() }) {
                    Text(stringResource(R.string.vault_remove_originals_no))
                }
            }
        )
    }

    // --------------------------------------------------------------- scaffold

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (state.selectionMode) {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(
                                R.string.vault_selected_count,
                                state.selectedIds.size
                            )
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.action_cancel)
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                withLegacyWritePermission {
                                    viewModel.exportSelected()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FileDownload,
                                contentDescription = stringResource(R.string.vault_export)
                            )
                        }
                        IconButton(onClick = { showMoveDialog = true }) {
                            Icon(
                                imageVector = Icons.Filled.DriveFileMove,
                                contentDescription = stringResource(R.string.vault_move_to)
                            )
                        }
                        IconButton(onClick = { showDeleteItemsConfirm = true }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.action_delete)
                            )
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.vault_title)) },
                    actions = {
                        IconButton(onClick = onLockNow) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = stringResource(R.string.settings_lock_now)
                            )
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.settings_title)
                            )
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (!state.isImporting) {
                        pickerLauncher.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageAndVideo
                            )
                        )
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.vault_import)
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (state.isImporting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Folder filter chips (long-press a folder to manage it)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FolderFilterChip(
                        label = stringResource(R.string.vault_all),
                        selected = state.filter == FolderFilter.All,
                        onClick = { viewModel.onFilterSelected(FolderFilter.All) }
                    )
                }
                item {
                    FolderFilterChip(
                        label = stringResource(R.string.vault_root),
                        selected = state.filter == FolderFilter.Root,
                        onClick = { viewModel.onFilterSelected(FolderFilter.Root) }
                    )
                }
                items(state.folders, key = { it.id }) { folder ->
                    FolderFilterChip(
                        label = folder.name,
                        selected =
                            (state.filter as? FolderFilter.Folder)?.id == folder.id,
                        onClick = {
                            viewModel.onFilterSelected(FolderFilter.Folder(folder.id))
                        },
                        onLongClick = { folderToManage = folder }
                    )
                }
                item {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clip(RoundedCornerShape(50))
                            .combinedClickable(onClick = {
                                folderNameInput = ""
                                showCreateFolder = true
                            })
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CreateNewFolder,
                                contentDescription = stringResource(R.string.folder_new),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = stringResource(R.string.folder_new_short),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    }
                }
            }

            if (state.items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(
                                if (state.filter == FolderFilter.All) {
                                    R.string.vault_empty_title
                                } else {
                                    R.string.vault_empty_folder_title
                                }
                            ),
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = stringResource(R.string.vault_empty_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.items, key = { it.id }) { item ->
                        VaultGridCell(
                            item = item,
                            repository = repository,
                            selected = item.id in state.selectedIds,
                            onClick = {
                                if (state.selectionMode) {
                                    viewModel.toggleSelection(item)
                                } else {
                                    onOpenItem(item.id)
                                }
                            },
                            onLongClick = { viewModel.toggleSelection(item) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultGridCell(
    item: VaultItem,
    repository: com.sowrobrana.photolock.data.VaultRepository,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        VaultThumbnail(
            item = item,
            repository = repository,
            modifier = Modifier.fillMaxSize()
        )

        if (item.isVideo) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    val duration = item.durationMs
                    if (duration != null) {
                        Text(
                            text = formatDuration(duration),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 2.dp)
                        )
                    }
                }
            }
        }

        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    )
            )
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(
                        MaterialTheme.colorScheme.surface,
                        CircleShape
                    )
                    .size(22.dp)
            )
        }
    }
}
