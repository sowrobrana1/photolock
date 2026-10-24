package com.sowrobrana.photolock

import android.app.Application

/**
 * Application entry point.
 *
 * TODO(Phase 1): initialise the app graph here — CryptoManager, VaultRepository,
 * settings store — and expose them to the UI layer (manual DI or Hilt).
 */
class PhotoLockApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Nothing to initialise yet in the scaffold.
    }
}
