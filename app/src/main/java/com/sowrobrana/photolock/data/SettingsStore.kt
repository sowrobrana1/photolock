package com.sowrobrana.photolock.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "photolock_settings"
)

/**
 * Non-secret app settings, backed by DataStore Preferences.
 *
 * Holds: the biometric opt-in, the auto-lock option, and the PIN back-off
 * state (consecutive failures / lockout deadline) — the back-off MUST live
 * in durable storage so restarting the app can't reset it. The PIN verifier
 * itself is NOT here; it lives in PinManager's encrypted prefs.
 */
class SettingsStore(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    // ------------------------------------------------------------- toggles

    val biometricEnabled: Flow<Boolean> =
        dataStore.data.map { it[KEY_BIOMETRIC] ?: false }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_BIOMETRIC] = enabled }
    }

    // ----------------------------------------------------------- auto-lock

    /** Seconds the user picked: 0 = immediately, 60, or 300. */
    val autoLockSeconds: Flow<Int> =
        dataStore.data.map { it[KEY_AUTO_LOCK_SECONDS] ?: DEFAULT_AUTO_LOCK_SECONDS }

    suspend fun setAutoLockSeconds(seconds: Int) {
        dataStore.edit { it[KEY_AUTO_LOCK_SECONDS] = seconds }
    }

    // --------------------------------------------------- PIN back-off state

    /** Consecutive failed PIN attempts since the last success. */
    val consecutivePinFailures: Flow<Int> =
        dataStore.data.map { it[KEY_FAILURES] ?: 0 }

    /** How many lockouts have been served in the current failure streak. */
    val lockoutCount: Flow<Int> =
        dataStore.data.map { it[KEY_LOCKOUT_COUNT] ?: 0 }

    /** Epoch ms until which PIN entry is blocked (0 = not locked out). */
    val lockoutUntilEpochMs: Flow<Long> =
        dataStore.data.map { it[KEY_LOCKOUT_UNTIL] ?: 0L }

    suspend fun recordFailureState(
        consecutiveFailures: Int,
        lockoutCount: Int,
        lockoutUntilEpochMs: Long
    ) {
        dataStore.edit {
            it[KEY_FAILURES] = consecutiveFailures
            it[KEY_LOCKOUT_COUNT] = lockoutCount
            it[KEY_LOCKOUT_UNTIL] = lockoutUntilEpochMs
        }
    }

    /** Called on any successful unlock (PIN or biometric). */
    suspend fun resetFailureState() {
        dataStore.edit {
            it[KEY_FAILURES] = 0
            it[KEY_LOCKOUT_COUNT] = 0
            it[KEY_LOCKOUT_UNTIL] = 0L
        }
    }

    companion object {
        const val AUTO_LOCK_IMMEDIATELY = 0
        const val AUTO_LOCK_1_MIN = 60
        const val AUTO_LOCK_5_MIN = 300
        const val DEFAULT_AUTO_LOCK_SECONDS = AUTO_LOCK_1_MIN

        private val KEY_BIOMETRIC = booleanPreferencesKey("biometric_enabled")
        private val KEY_AUTO_LOCK_SECONDS = intPreferencesKey("auto_lock_seconds")
        private val KEY_FAILURES = intPreferencesKey("pin_consecutive_failures")
        private val KEY_LOCKOUT_COUNT = intPreferencesKey("pin_lockout_count")
        private val KEY_LOCKOUT_UNTIL = longPreferencesKey("pin_lockout_until_ms")
    }
}
