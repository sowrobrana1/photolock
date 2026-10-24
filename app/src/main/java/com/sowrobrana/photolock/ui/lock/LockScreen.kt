package com.sowrobrana.photolock.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sowrobrana.photolock.R
import com.sowrobrana.photolock.ui.theme.PhotoLockTheme

/** PIN length for the MVP. Phase 1: make it a user setting (4 or 6). */
private const val PIN_LENGTH = 4

/**
 * PIN-pad lock screen (placeholder logic).
 *
 * TODO(Phase 1) — real unlock flow:
 *  1. First run: "create PIN" + confirm flow; store only a salted hash
 *     (e.g. PBKDF2/BCrypt) in the settings store — never the PIN itself.
 *  2. Verify the entered PIN against the hash; on success call [onUnlocked].
 *  3. On failure: clear the pad, shake/vibrate, and record a break-in attempt
 *     (timestamp + optional front-camera snapshot encrypted into the vault —
 *     ON-DEVICE ONLY, never uploaded; see PLAN.md).
 *  4. Biometric button: androidx.biometric BiometricPrompt; requires the host
 *     activity to become a FragmentActivity (see MainActivity note).
 *  5. Exponential back-off after repeated failures (e.g. 30 s after 5 tries).
 */
@Composable
fun LockScreen(
    onUnlocked: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pin by remember { mutableStateOf("") }

    fun onDigit(digit: Char) {
        if (pin.length >= PIN_LENGTH) return
        pin += digit
        if (pin.length == PIN_LENGTH) {
            // PLACEHOLDER: any complete PIN "unlocks" in the scaffold so the
            // navigation flow can be exercised on a real device. Phase 1
            // replaces this with hash verification (see TODO above) and only
            // calls onUnlocked() on a match.
            onUnlocked()
        }
    }

    fun onDelete() {
        pin = pin.dropLast(1)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.lock_title),
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.lock_enter_pin),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // PIN dots
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(PIN_LENGTH) { index ->
                val filled = index < pin.length
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(
                            if (filled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Keypad: 1-9, then (biometric placeholder) 0 (delete)
        val keys = listOf(
            listOf('1', '2', '3'),
            listOf('4', '5', '6'),
            listOf('7', '8', '9'),
            listOf(null, '0', '⌫')
        )
        keys.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.padding(vertical = 6.dp)
            ) {
                row.forEach { key ->
                    when (key) {
                        null -> Spacer(modifier = Modifier.size(72.dp))
                        '⌫' -> FilledTonalButton(
                            onClick = { onDelete() },
                            modifier = Modifier.size(72.dp),
                            shape = CircleShape
                        ) {
                            Text(
                                text = "⌫",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }

                        else -> FilledTonalButton(
                            onClick = { onDigit(key) },
                            modifier = Modifier.size(72.dp),
                            shape = CircleShape
                        ) {
                            Text(
                                text = key.toString(),
                                style = MaterialTheme.typography.headlineSmall
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(onClick = { /* TODO(Phase 1): BiometricPrompt */ }) {
            Text(text = stringResource(R.string.lock_use_biometric))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LockScreenPreview() {
    PhotoLockTheme {
        LockScreen(onUnlocked = {})
    }
}
