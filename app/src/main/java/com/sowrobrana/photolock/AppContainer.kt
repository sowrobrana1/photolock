package com.sowrobrana.photolock

import android.content.Context
import com.sowrobrana.photolock.crypto.CryptoManager
import com.sowrobrana.photolock.data.SettingsStore
import com.sowrobrana.photolock.data.VaultRepository
import com.sowrobrana.photolock.data.local.PhotoLockDatabase
import com.sowrobrana.photolock.security.PinManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual dependency graph (no Hilt — the app is small and the graph is
 * flat). Built once by [PhotoLockApp] and handed to the UI layer.
 */
class AppContainer(context: Context) {

    /** Application context, exposed for ViewModel construction in the UI layer. */
    val appContext: Context = context.applicationContext

    val cryptoManager: CryptoManager = CryptoManager()

    val database: PhotoLockDatabase = PhotoLockDatabase.getInstance(appContext)

    val vaultRepository: VaultRepository =
        VaultRepository(appContext, cryptoManager, database)

    val pinManager: PinManager = PinManager(appContext)

    val settingsStore: SettingsStore = SettingsStore(appContext)

    val lockSession: LockSession = LockSession(settingsStore)

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Orphaned plaintext video temp files from a previous crashed
        // session must not linger — sweep them at every cold start.
        appScope.launch { vaultRepository.sweepTempFiles() }
    }
}
