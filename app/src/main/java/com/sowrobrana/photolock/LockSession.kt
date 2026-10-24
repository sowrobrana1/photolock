package com.sowrobrana.photolock

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.sowrobrana.photolock.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds the app-wide "vault is unlocked" session state and implements
 * auto-lock (PLAN.md §1.1).
 *
 * Model: the app cold-starts LOCKED. A successful PIN/biometric unlock
 * calls [unlock]. When the whole process goes to the background
 * (ProcessLifecycleOwner onStop), the timestamp is recorded; when it
 * returns to the foreground, if the elapsed time exceeds the user's
 * auto-lock setting, [lock] fires and the UI navigates back to the lock
 * screen with the back stack cleared.
 *
 * The "immediately" option still applies a short grace
 * ([IMMEDIATE_GRACE_MS]): launching the system Photo Picker or a MediaStore
 * consent dialog briefly backgrounds the app, and locking in the middle of
 * an import would be a bug, not a feature. 15 s comfortably covers those
 * system round-trips while still locking promptly.
 *
 * While backgrounded beyond the timeout there is nothing to protect on
 * screen: FLAG_SECURE (toggled from MainActivity off [isUnlocked]) keeps
 * vault content out of screenshots/recents, and the foreground check locks
 * before any content is interacted with again.
 */
class LockSession(settingsStore: SettingsStore) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    /** Effective auto-lock timeout, kept current from the settings flow. */
    @Volatile
    private var timeoutMs: Long = DEFAULT_TIMEOUT_MS

    @Volatile
    private var backgroundedAtMs: Long? = null

    init {
        scope.launch {
            settingsStore.autoLockSeconds.collect { seconds ->
                timeoutMs = if (seconds <= 0) {
                    IMMEDIATE_GRACE_MS
                } else {
                    seconds * 1000L
                }
            }
        }
    }

    /** Registers this session with ProcessLifecycleOwner (call once). */
    fun attachTo(lifecycle: Lifecycle) {
        lifecycle.addObserver(this)
    }

    fun unlock() {
        _isUnlocked.value = true
    }

    fun lock() {
        _isUnlocked.value = false
    }

    override fun onStop(owner: LifecycleOwner) {
        if (_isUnlocked.value) {
            backgroundedAtMs = System.currentTimeMillis()
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        val since = backgroundedAtMs ?: return
        backgroundedAtMs = null
        if (_isUnlocked.value &&
            System.currentTimeMillis() - since >= timeoutMs
        ) {
            lock()
        }
    }

    companion object {
        /** Grace applied to the "immediately" auto-lock option. */
        const val IMMEDIATE_GRACE_MS = 15_000L
        private const val DEFAULT_TIMEOUT_MS = 60_000L
    }
}
