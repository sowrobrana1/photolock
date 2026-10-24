package com.sowrobrana.photolock.ui.settings

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sowrobrana.photolock.data.BreakInAttempt
import com.sowrobrana.photolock.data.SettingsStore
import com.sowrobrana.photolock.data.VaultRepository
import com.sowrobrana.photolock.security.PinManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val biometricEnabled: Boolean = false,
    val biometricAvailable: Boolean = false,
    val autoLockSeconds: Int = SettingsStore.DEFAULT_AUTO_LOCK_SECONDS,
    val itemCount: Int = 0,
    val vaultSizeBytes: Long = 0L,
    val pinLength: Int = PinManager.DEFAULT_PIN_LENGTH
)

/**
 * Settings logic: biometric opt-in, auto-lock option, storage summary,
 * change-PIN (old PIN verified first, inside [PinManager.changePin]), and
 * the on-device break-in log.
 */
class SettingsViewModel(
    context: Context,
    private val pinManager: PinManager,
    private val settingsStore: SettingsStore,
    private val repository: VaultRepository
) : ViewModel() {

    private val biometricAvailable: Boolean =
        BiometricManager.from(context.applicationContext).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS

    private val sizeFlow = MutableStateFlow(0L)
    private val pinLengthFlow =
        MutableStateFlow(PinManager.DEFAULT_PIN_LENGTH)

    val state: StateFlow<SettingsUiState> = combine(
        settingsStore.biometricEnabled,
        settingsStore.autoLockSeconds,
        repository.observeItemCount(),
        sizeFlow,
        pinLengthFlow
    ) { biometric, autoLock, count, size, pinLength ->
        SettingsUiState(
            biometricEnabled = biometric,
            biometricAvailable = biometricAvailable,
            autoLockSeconds = autoLock,
            itemCount = count,
            vaultSizeBytes = size,
            pinLength = pinLength
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(biometricAvailable = biometricAvailable)
    )

    /** Break-in log (newest first) — on-device only, never uploaded. */
    val breakInLog: StateFlow<List<BreakInAttempt>> =
        repository.observeBreakInLog().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    init {
        viewModelScope.launch {
            pinLengthFlow.value = withContext(Dispatchers.Default) {
                pinManager.pinLength()
            }
        }
        refreshStorage()
    }

    fun refreshStorage() {
        viewModelScope.launch {
            sizeFlow.value = repository.totalVaultSizeBytes()
        }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setBiometricEnabled(enabled) }
    }

    fun setAutoLockSeconds(seconds: Int) {
        viewModelScope.launch { settingsStore.setAutoLockSeconds(seconds) }
    }

    /**
     * Changes the PIN. [PinManager.changePin] verifies the old PIN before
     * replacing the verifier; [onResult] receives whether it succeeded.
     */
    fun changePin(oldPin: String, newPin: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.Default) {
                pinManager.changePin(oldPin, newPin)
            }
            if (ok) pinLengthFlow.value = newPin.length
            onResult(ok)
        }
    }

    fun clearBreakInLog() {
        viewModelScope.launch { repository.clearBreakInLog() }
    }
}
