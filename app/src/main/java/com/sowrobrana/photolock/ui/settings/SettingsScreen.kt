package com.sowrobrana.photolock.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sowrobrana.photolock.R
import com.sowrobrana.photolock.data.SettingsStore

/**
 * Settings screen (wired to [SettingsViewModel]):
 *  - Change PIN — old PIN verified first (see dialog below).
 *  - Biometric unlock toggle — persisted; disabled when the device has no
 *    strong biometric enrolled.
 *  - Auto-lock timeout — immediately / 1 min / 5 min.
 *  - Lock now.
 *  - Break-in log — opens the on-device log screen. Never uploaded.
 *  - Storage usage + About (encryption + key-loss honesty notes).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenBreakInLog: () -> Unit,
    onLockNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showChangePin by remember { mutableStateOf(false) }
    var showAutoLock by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    if (showChangePin) {
        ChangePinDialog(
            viewModel = viewModel,
            onDismiss = { showChangePin = false }
        )
    }

    if (showAutoLock) {
        AutoLockDialog(
            currentSeconds = state.autoLockSeconds,
            onSelect = { seconds ->
                viewModel.setAutoLockSeconds(seconds)
                showAutoLock = false
            },
            onDismiss = { showAutoLock = false }
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text(stringResource(R.string.settings_about)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_about_body,
                        stringResource(R.string.app_version)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) {
                    Text(stringResource(R.string.action_ok))
                }
            }
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
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
                summary = stringResource(
                    R.string.settings_change_pin_summary,
                    state.pinLength
                ),
                onClick = { showChangePin = true }
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
                        text = stringResource(
                            if (state.biometricAvailable) {
                                R.string.settings_biometric_summary
                            } else {
                                R.string.settings_biometric_unavailable
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.biometricEnabled,
                    enabled = state.biometricAvailable,
                    onCheckedChange = { viewModel.setBiometricEnabled(it) }
                )
            }
            HorizontalDivider()

            SettingsRow(
                title = stringResource(R.string.settings_auto_lock),
                summary = stringResource(
                    when (state.autoLockSeconds) {
                        SettingsStore.AUTO_LOCK_IMMEDIATELY ->
                            R.string.settings_auto_lock_immediately

                        SettingsStore.AUTO_LOCK_5_MIN ->
                            R.string.settings_auto_lock_5min

                        else -> R.string.settings_auto_lock_1min
                    }
                ),
                onClick = { showAutoLock = true }
            )
            HorizontalDivider()

            SettingsRow(
                title = stringResource(R.string.settings_lock_now),
                onClick = onLockNow
            )
            HorizontalDivider()

            SettingsRow(
                title = stringResource(R.string.settings_break_in_log),
                summary = stringResource(R.string.settings_break_in_log_summary),
                onClick = onOpenBreakInLog
            )
            HorizontalDivider()

            SettingsRow(
                title = stringResource(R.string.settings_storage),
                summary = stringResource(
                    R.string.settings_storage_summary,
                    state.itemCount,
                    Formatter.formatShortFileSize(context, state.vaultSizeBytes)
                ),
                onClick = { viewModel.refreshStorage() }
            )
            HorizontalDivider()

            SettingsRow(
                title = stringResource(R.string.settings_about),
                onClick = { showAbout = true }
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

private enum class ChangePinStep { OLD, NEW, CONFIRM, DONE }

/**
 * Change-PIN flow: current PIN → new PIN → confirm. The old PIN is only
 * checked when the flow completes (inside PinManager.changePin, which
 * verifies before replacing); a wrong current PIN lands back on step one
 * with an error.
 */
@Composable
private fun ChangePinDialog(
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit
) {
    var step by remember { mutableStateOf(ChangePinStep.OLD) }
    var oldPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val wrongOld = stringResource(R.string.change_pin_wrong_old)
    val mismatch = stringResource(R.string.change_pin_mismatch)
    val invalid = stringResource(R.string.change_pin_invalid)

    fun pinFieldValid(pin: String): Boolean =
        (pin.length == 4 || pin.length == 6) && pin.all { it.isDigit() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_change_pin)) },
        text = {
            Column {
                if (step == ChangePinStep.DONE) {
                    Text(stringResource(R.string.change_pin_done))
                } else {
                    val (value, onChange, label) = when (step) {
                        ChangePinStep.OLD -> Triple(
                            oldPin,
                            { v: String -> oldPin = v },
                            stringResource(R.string.change_pin_old)
                        )

                        ChangePinStep.NEW -> Triple(
                            newPin,
                            { v: String -> newPin = v },
                            stringResource(R.string.change_pin_new)
                        )

                        else -> Triple(
                            confirmPin,
                            { v: String -> confirmPin = v },
                            stringResource(R.string.change_pin_confirm)
                        )
                    }
                    OutlinedTextField(
                        value = value,
                        onValueChange = { input ->
                            if (input.length <= 6 && input.all { it.isDigit() }) {
                                onChange(input)
                            }
                            error = null
                        },
                        label = { Text(label) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword
                        )
                    )
                    if (error != null) {
                        Text(
                            text = error ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (step) {
                ChangePinStep.DONE -> TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_ok))
                }

                ChangePinStep.OLD -> TextButton(
                    onClick = {
                        if (pinFieldValid(oldPin)) {
                            step = ChangePinStep.NEW
                        } else {
                            error = invalid
                        }
                    }
                ) { Text(stringResource(R.string.action_next)) }

                ChangePinStep.NEW -> TextButton(
                    onClick = {
                        if (pinFieldValid(newPin)) {
                            step = ChangePinStep.CONFIRM
                        } else {
                            error = invalid
                        }
                    }
                ) { Text(stringResource(R.string.action_next)) }

                ChangePinStep.CONFIRM -> TextButton(
                    enabled = !busy,
                    onClick = {
                        if (confirmPin != newPin) {
                            error = mismatch
                        } else {
                            busy = true
                            viewModel.changePin(oldPin, newPin) { ok ->
                                busy = false
                                if (ok) {
                                    step = ChangePinStep.DONE
                                } else {
                                    error = wrongOld
                                    step = ChangePinStep.OLD
                                    oldPin = ""
                                    newPin = ""
                                    confirmPin = ""
                                }
                            }
                        }
                    }
                ) { Text(stringResource(R.string.action_save)) }
            }
        },
        dismissButton = {
            if (step != ChangePinStep.DONE) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    )
}

@Composable
private fun AutoLockDialog(
    currentSeconds: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        SettingsStore.AUTO_LOCK_IMMEDIATELY to
            stringResource(R.string.settings_auto_lock_immediately),
        SettingsStore.AUTO_LOCK_1_MIN to
            stringResource(R.string.settings_auto_lock_1min),
        SettingsStore.AUTO_LOCK_5_MIN to
            stringResource(R.string.settings_auto_lock_5min)
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_auto_lock)) },
        text = {
            Column {
                options.forEach { (seconds, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(seconds) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentSeconds == seconds,
                            onClick = { onSelect(seconds) }
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
