package com.sowrobrana.photolock

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Application entry point: builds the [AppContainer] graph once and wires
 * the [LockSession] to the process lifecycle for auto-lock.
 */
class PhotoLockApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.lockSession.attachTo(ProcessLifecycleOwner.get().lifecycle)
    }
}
