package com.sowrobrana.photolock.ui.lock

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sowrobrana.photolock.R
import kotlin.math.roundToInt

/**
 * PIN-pad lock screen (real flow — see [LockViewModel]):
 *  - first run: create PIN (4 or 6 digits) + confirm; only a salted PBKDF2
 *    hash is stored;
 *  - later runs: verify against the hash; wrong PIN shakes the pad, logs a
 *    break-in attempt on-device, and backs off exponentially;
 *  - fingerprint button (and an automatic prompt on entry) when the user
 *    enabled biometric unlock and the device supports it. PIN always works
 *    as the fallback.
 */
@Composable
fun LockScreen(
    viewModel: LockViewModel,
    onUnlocked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(viewModel) {
        viewModel.unlockEvents.collect { onUnlocked() }
    }

    // Wrong-PIN feedback: shake the dots + a haptic buzz.
    val shakeX = remember { Animatable(0f) }
    LaunchedEffect(state.shakeTrigger) {
        if (state.shakeTrigger == 0) return@LaunchedEffect
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        shakeX.snapTo(0f)
        shakeX.animateTo(
            targetValue = 0f,
            animationSpec = keyframes {
                durationMillis = 400
                0f at 0
                (-14f) at 60
                12f at 120
                (-9f) at 180
                7f at 240
                (-4f) at 300
                0f at 360
            }
        )
    }

    fun launchBiometric() {
        val activity = context.findFragmentActivity() ?: return
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    viewModel.onBiometricSuccess()
                }
                // Errors (incl. user tapping "Use PIN" / cancel) fall back
                // to the PIN pad silently.
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(R.string.biometric_title))
            .setSubtitle(context.getString(R.string.biometric_subtitle))
            .setNegativeButtonText(context.getString(R.string.lock_use_pin))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info)
    }

    // Offer biometrics once when the pad appears (if opted in + available).
    var autoPromptDone by remember { mutableStateOf(false) }
    LaunchedEffect(state.mode, state.biometricEnabled, state.biometricAvailable) {
        if (!autoPromptDone &&
            state.mode == LockMode.ENTER &&
            state.biometricEnabled &&
            state.biometricAvailable
        ) {
            autoPromptDone = true
            launchBiometric()
        }
    }

    val lockedOut = state.lockoutSecondsLeft > 0
    val padEnabled = !lockedOut && !state.isBusy && state.isReady

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
            text = stringResource(
                when (state.mode) {
                    LockMode.CREATE -> R.string.lock_create_pin
                    LockMode.CONFIRM -> R.string.lock_confirm_pin
                    LockMode.ENTER -> R.string.lock_enter_pin
                }
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // PIN dots (shake on failure)
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.offset {
                IntOffset(shakeX.value.roundToInt(), 0)
            }
        ) {
            repeat(state.pinLength) { index ->
                val filled = index < state.entered.length
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

        // Error / lockout line (fixed height so the pad doesn't jump)
        Box(
            modifier = Modifier.height(32.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                lockedOut -> Text(
                    text = stringResource(
                        R.string.lock_lockout,
                        state.lockoutSecondsLeft
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )

                state.error != null -> Text(
                    text = stringResource(
                        when (state.error) {
                            LockError.WRONG_PIN -> R.string.lock_wrong_pin
                            LockError.PIN_MISMATCH -> R.string.lock_pin_mismatch
                            null -> R.string.lock_wrong_pin
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Keypad: 1-9, then (fingerprint | spacer) 0 (delete)
        val keys: List<List<Char?>> = listOf(
            listOf('1', '2', '3'),
            listOf('4', '5', '6'),
            listOf('7', '8', '9'),
            listOf(null, '0', '⌫')
        )
        keys.forEachIndexed { rowIndex, row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.padding(vertical = 6.dp)
            ) {
                row.forEachIndexed { colIndex, key ->
                    val isFingerprintSlot = rowIndex == 3 && colIndex == 0
                    when {
                        isFingerprintSlot &&
                            state.mode == LockMode.ENTER &&
                            state.biometricAvailable -> {
                            FilledTonalIconButton(
                                onClick = { launchBiometric() },
                                enabled = !lockedOut && !state.isBusy,
                                modifier = Modifier.size(72.dp),
                                shape = CircleShape
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Fingerprint,
                                    contentDescription = stringResource(
                                        R.string.lock_use_biometric
                                    ),
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }

                        key == null -> Spacer(modifier = Modifier.size(72.dp))

                        key == '⌫' -> FilledTonalButton(
                            onClick = { viewModel.onDelete() },
                            enabled = padEnabled,
                            modifier = Modifier.size(72.dp),
                            shape = CircleShape
                        ) {
                            Text(
                                text = "⌫",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }

                        else -> FilledTonalButton(
                            onClick = { viewModel.onDigit(key) },
                            enabled = padEnabled,
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

        if (state.mode != LockMode.ENTER) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = { viewModel.onTogglePinLength() }) {
                Text(
                    text = stringResource(
                        if (state.pinLength == 4) {
                            R.string.lock_use_6_digit
                        } else {
                            R.string.lock_use_4_digit
                        }
                    )
                )
            }
        }
    }
}

/** LocalContext inside MainActivity IS the FragmentActivity; walk up just in case. */
private fun Context.findFragmentActivity(): FragmentActivity? {
    var ctx: Context? = this
    while (ctx != null) {
        if (ctx is FragmentActivity) return ctx
        ctx = (ctx as? android.content.ContextWrapper)?.baseContext
    }
    return null
}
