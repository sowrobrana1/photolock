package com.sowrobrana.photolock.ui.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sowrobrana.photolock.R
import com.sowrobrana.photolock.data.VaultItem
import com.sowrobrana.photolock.ui.theme.PhotoLockTheme

/**
 * Vault contents grid (placeholder data).
 *
 * TODO(Phase 1):
 *  - Observe items from VaultRepository via a ViewModel (StateFlow).
 *  - Render each cell from the item's ENCRYPTED thumbnail blob (decrypt in
 *    memory, e.g. through Coil with a custom fetcher) — never the original.
 *  - FAB launches the system Photo Picker
 *    (ActivityResultContracts.PickMultipleVisualMedia) → import pipeline →
 *    optional "remove originals?" consent step (MediaStore dialog).
 *  - Tap → in-app viewer route; long-press → multi-select (export / delete /
 *    move to folder).
 *  - Folder chips row above the grid once folders exist.
 *  - NO ads on this screen while browsing private media is fine per plan —
 *    ads, if any, belong in Settings/folder list only (see PLAN.md). The lock
 *    screen and viewer must stay ad-free.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultGridScreen(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    // PLACEHOLDER: empty until the repository is wired to a ViewModel.
    val items = remember { emptyList<VaultItem>() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.vault_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.settings_title)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { /* TODO(Phase 1): launch Photo Picker import flow */ }
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.vault_import)
                )
            }
        }
    ) { innerPadding ->
        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.vault_empty_title),
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
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    Card(modifier = Modifier.aspectRatio(1f)) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = item.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun VaultGridScreenPreview() {
    PhotoLockTheme {
        VaultGridScreen(onOpenSettings = {})
    }
}
