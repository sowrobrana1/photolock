package com.sowrobrana.photolock.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sowrobrana.photolock.ui.lock.LockScreen
import com.sowrobrana.photolock.ui.settings.SettingsScreen
import com.sowrobrana.photolock.ui.vault.VaultGridScreen

/** Route names for the app. */
object PhotoLockDestinations {
    const val LOCK = "lock"
    const val VAULT = "vault"
    const val SETTINGS = "settings"

    // Phase 1 additions:
    // const val VIEWER = "viewer/{itemId}"   — in-app photo/video viewer
    // const val FOLDER = "folder/{folderId}" — folder contents (reuses grid)
}

/**
 * App navigation skeleton.
 *
 * Flow: LOCK (start) → on successful unlock → VAULT (lock route is popped, so
 * Back from the vault exits instead of returning to the PIN pad) → SETTINGS.
 *
 * TODO(Phase 1): re-lock when the app leaves the foreground — a lifecycle
 * observer should navigate back to LOCK (clearing the back stack) after a
 * configurable timeout, and the "unlocked" state should live in a session
 * holder, not in the navigation graph.
 */
@Composable
fun PhotoLockNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = PhotoLockDestinations.LOCK,
        modifier = modifier
    ) {
        composable(PhotoLockDestinations.LOCK) {
            LockScreen(
                onUnlocked = {
                    navController.navigate(PhotoLockDestinations.VAULT) {
                        popUpTo(PhotoLockDestinations.LOCK) { inclusive = true }
                    }
                }
            )
        }

        composable(PhotoLockDestinations.VAULT) {
            VaultGridScreen(
                onOpenSettings = {
                    navController.navigate(PhotoLockDestinations.SETTINGS)
                }
            )
        }

        composable(PhotoLockDestinations.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
