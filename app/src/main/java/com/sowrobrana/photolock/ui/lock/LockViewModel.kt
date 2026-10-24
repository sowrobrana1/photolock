package com.sowrobrana.photolock.ui.lock

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sowrobrana.photolock.data.SettingsStore
import com.sowrobrana.photolock.data.VaultRepository
import com.sowrobrana.photolock.security.PinManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which step of the lock flow the pad is showing. */
enum class LockMode { CREATE, CONFIRM, ENTER }

enum class LockError { WRONG_PIN, PIN_MISMATCH }

data class LockUiState(
    val mode: LockMode = LockMode.ENTER,
    val pinLength: Int = PinManager.DEFAULT_PIN_LENGTH,
    val entered: String = "",
    val error: LockError? = null,
    /** Increments on every failed attempt — the UI shakes on change. */
    val shakeTrigger: Int = 0,
    /** >0 while a brute-force lockout is running (PIN entry disabled). */
    val lockoutSecondsLeft: Int = 0,
    val biometricEnabled: Boolean = false,
    val biometricAvailable: Boolean = false,
    val isBusy: Boolean = false,
    val isReady: Boolean = false
)

/**
 * Lock-screen logic: first-run PIN creation (create + confirm), salted-hash
 * verification, failed-attempt backoff, break-in logging, biometric unlock.
 *
 * Backoff (PLAN.md §1.1): the failure streak is persisted via
 * [SettingsStore]; from the 5th consecutive failure the pad locks for 30 s,
 * doubling per further lockout (60, 120, 240, max 480 s). Every failure is
 * written to the on-device break-in log. Any success (PIN or biometric)
 * resets the streak.
 */
class LockViewModel(
    context: Context,
    private val pinManager: PinManager,
    private val settingsStore: SettingsStore,
    private val repository: VaultRepository
) : ViewModel() {

    private val appContext: Context = context.applicationContext

    private val _state = MutableStateFlow(LockUiState())
    val state: StateFlow<LockUiState> = _state.asStateFlow()

    private val _unlockEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val unlockEvents: SharedFlow<Unit> = _unlockEvents.asSharedFlow()

    /** Candidate PIN between the CREATE and CONFIRM steps (memory only). */
    private var createCandidate: String? = null

    @Volatile
    private var lockoutUntilMs: Long = 0L

    init {
        viewModelScope.launch {
            val hasPin = withContext(Dispatchers.Default) { pinManager.hasPin() }
            val storedLength = withContext(Dispatchers.Default) {
                pinManager.pinLength()
            }
            _state.update {
                it.copy(
                    mode = if (hasPin) LockMode.ENTER else LockMode.CREATE,
                    pinLength = if (hasPin) storedLength
                    else PinManager.DEFAULT_PIN_LENGTH,
                    biometricAvailable = computeBiometricAvailable(),
                    isReady = true
                )
            }
        }
        viewModelScope.launch {
            settingsStore.biometricEnabled.collect { enabled ->
                _state.update { it.copy(biometricEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settingsStore.lockoutUntilEpochMs.collect { until ->
                lockoutUntilMs = until
            }
        }
        // Lockout countdown ticker.
        viewModelScope.launch {
            while (true) {
                val remainingMs = lockoutUntilMs - System.currentTimeMillis()
                val seconds = if (remainingMs > 0) {
                    ((remainingMs + 999) / 1000).toInt()
                } else {
                    0
                }
                _state.update { current ->
                    if (current.lockoutSecondsLeft != seconds) {
                        current.copy(lockoutSecondsLeft = seconds)
                    } else {
                        current
                    }
                }
                delay(500)
            }
        }
    }

    fun onDigit(digit: Char) {
        val current = _state.value
        if (!current.isReady || current.isBusy) return
        if (current.lockoutSecondsLeft > 0) return
        if (current.entered.length >= current.pinLength) return
        val next = current.entered + digit
        _state.update { it.copy(entered = next, error = null) }
        if (next.length == current.pinLength) {
            submit(next)
        }
    }

    fun onDelete() {
        _state.update { it.copy(entered = it.entered.dropLast(1)) }
    }

    /** 4 ⇄ 6 digit toggle, only meaningful while creating a PIN. */
    fun onTogglePinLength() {
        val current = _state.value
        if (current.mode == LockMode.ENTER) return
        createCandidate = null
        _state.update {
            it.copy(
                mode = LockMode.CREATE,
                pinLength = if (it.pinLength == PinManager.DEFAULT_PIN_LENGTH) {
                    PinManager.ALT_PIN_LENGTH
                } else {
                    PinManager.DEFAULT_PIN_LENGTH
                },
                entered = "",
                error = null
            )
        }
    }

    /** BiometricPrompt success — same effect as a correct PIN. */
    fun onBiometricSuccess() {
        viewModelScope.launch {
            settingsStore.resetFailureState()
            _unlockEvents.tryEmit(Unit)
        }
    }

    private fun submit(pin: String) {
        when (_state.value.mode) {
            LockMode.CREATE -> {
                createCandidate = pin
                _state.update {
                    it.copy(mode = LockMode.CONFIRM, entered = "", error = null)
                }
            }

            LockMode.CONFIRM -> {
                if (pin == createCandidate) {
                    _state.update { it.copy(isBusy = true) }
                    viewModelScope.launch {
                        try {
                            withContext(Dispatchers.Default) {
                                pinManager.setPin(pin)
                            }
                            createCandidate = null
                            settingsStore.resetFailureState()
                            _state.update { it.copy(isBusy = false) }
                            _unlockEvents.tryEmit(Unit)
                        } catch (t: Throwable) {
                            _state.update { it.copy(isBusy = false) }
                        }
                    }
                } else {
                    createCandidate = null
                    _state.update {
                        it.copy(
                            mode = LockMode.CREATE,
                            entered = "",
                            error = LockError.PIN_MISMATCH,
                            shakeTrigger = it.shakeTrigger + 1
                        )
                    }
                }
            }

            LockMode.ENTER -> {
                _state.update { it.copy(isBusy = true) }
                viewModelScope.launch {
                    val ok = try {
                        withContext(Dispatchers.Default) {
                            pinManager.verify(pin)
                        }
                    } catch (t: Throwable) {
                        _state.update { it.copy(isBusy = false, entered = "") }
                        return@launch
                    }
                    if (ok) {
                        settingsStore.resetFailureState()
                        _state.update { it.copy(isBusy = false, entered = "") }
                        _unlockEvents.tryEmit(Unit)
                    } else {
                        handleFailedAttempt()
                    }
                }
            }
        }
    }

    private suspend fun handleFailedAttempt() {
        val failures = settingsStore.consecutivePinFailures.first() + 1
        var lockoutCount = settingsStore.lockoutCount.first()
        var lockoutUntil = 0L
        if (failures >= FAILURES_BEFORE_LOCKOUT) {
            lockoutCount += 1
            val seconds = lockoutSecondsFor(lockoutCount)
            lockoutUntil = System.currentTimeMillis() + seconds * 1000L
        }
        settingsStore.recordFailureState(failures, lockoutCount, lockoutUntil)
        // On-device break-in log (timestamp + streak count). Never uploaded.
        repository.recordBreakInAttempt(failures)
        _state.update {
            it.copy(
                isBusy = false,
                entered = "",
                error = LockError.WRONG_PIN,
                shakeTrigger = it.shakeTrigger + 1
            )
        }
    }

    private fun computeBiometricAvailable(): Boolean {
        val manager = BiometricManager.from(appContext)
        return manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    companion object {
        const val FAILURES_BEFORE_LOCKOUT = 5
        const val BASE_LOCKOUT_SECONDS = 30
        const val MAX_LOCKOUT_SECONDS = 480

        /** 30 s, doubling per repeat lockout, capped at [MAX_LOCKOUT_SECONDS]. */
        fun lockoutSecondsFor(lockoutCount: Int): Int {
            var seconds = BASE_LOCKOUT_SECONDS
            repeat((lockoutCount - 1).coerceAtLeast(0)) {
                seconds = (seconds * 2).coerceAtMost(MAX_LOCKOUT_SECONDS)
            }
            return seconds
        }
    }
}
