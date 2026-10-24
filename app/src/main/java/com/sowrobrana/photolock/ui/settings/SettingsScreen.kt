package com.sowrobrana.photolock.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sowrobrana.photolock.R
import com.sowrobrana.photolock.ui.theme.PhotoLockTheme

/**
 * Settings screen (placeholder rows).
 *
 * TODO(Phase 1): back every row with the settings store —
 *  - Change PIN: re-verify current PIN, then set a new salted hash.
 *  - Biometric unlock toggle: persisted; checked against device capability.
 *  - Break-in log: opens the on-device log (timestamped failed attempts,
 *    optional encrypted snapshots) with a "clear log" action. The log is
 *    NEVER uploaded anywhere — it exists only inside the vault.
 *  - Auto-lock timeout, vault storage usage, export-all, and a clearly
 *    worded "reset vault" danger action (double confirmation; destroys the
 *    Keystore key — see CryptoManager.deleteKey).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var biometricEnabled by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            SettingsRow(
                title = stringResource(R.string.settings_change_pin),
                onClick = { /* TODO(Phase 1): change-PIN flow */ }
            )
            HorizontalDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_biometric),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(R.string.settings_biometric_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = biometricEnabled,
                    onCheckedChange = { biometricEnabled = it /* TODO(Phase 1): persist */ }
                )
            }
            HorizontalDivider()

            SettingsRow(
                title = stringResource(R.string.settings_break_in_log),
                summary = stringResource(R.string.settings_break_in_log_summary),
                onClick = { /* TODO(Phase 1): break-in log screen */ }
            )
            HorizontalDivider()

            SettingsRow(
                title = stringResource(R.string.settings_about),
                onClick = { /* TODO(Phase 1): about / licences / privacy policy */ }
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    summary: String? = null,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge
        )
        if (summary != null) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    PhotoLockTheme {
        SettingsScreen(onBack = {})
    }
}
