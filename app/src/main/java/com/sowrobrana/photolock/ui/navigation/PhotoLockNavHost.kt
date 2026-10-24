package com.sowrobrana.photolock.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sowrobrana.photolock.AppContainer
import com.sowrobrana.photolock.ui.lock.LockScreen
import com.sowrobrana.photolock.ui.lock.LockViewModel
import com.sowrobrana.photolock.ui.settings.BreakInLogScreen
import com.sowrobrana.photolock.ui.settings.SettingsScreen
import com.sowrobrana.photolock.ui.settings.SettingsViewModel
import com.sowrobrana.photolock.ui.vault.VaultGridScreen
import com.sowrobrana.photolock.ui.vault.VaultViewModel
import com.sowrobrana.photolock.ui.viewer.ViewerScreen
import com.sowrobrana.photolock.ui.viewer.ViewerViewModel

/** Route names for the app. */
object PhotoLockDestinations {
    const val LOCK = "lock"
    const val VAULT = "vault"
    const val SETTINGS = "settings"
    const val VIEWER = "viewer/{itemId}"
    const val BREAK_IN_LOG = "breakin"

    fun viewer(itemId: String): String = "viewer/$itemId"
}

/**
 * App navigation.
 *
 * Flow: LOCK (start) → on successful unlock → VAULT (lock route popped, so
 * Back from the vault exits instead of returning to the PIN pad) → VIEWER /
 * SETTINGS / BREAK-IN LOG.
 *
 * Auto-lock: [AppContainer.lockSession] flips to locked when the app has
 * been backgrounded past the user's timeout; the effect below then
 * navigates back to LOCK and clears the ENTIRE back stack, so no unlocked
 * content remains reachable (PLAN.md §1.1).
 */
@Composable
fun PhotoLockNavHost(
    container: AppContainer,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    val unlocked by container.lockSession.isUnlocked.collectAsState()

    LaunchedEffect(unlocked) {
        if (!unlocked) {
            val route = navController.currentDestination?.route
            if (route != null && route != PhotoLockDestinations.LOCK) {
                navController.navigate(PhotoLockDestinations.LOCK) {
                    popUpTo(0) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = PhotoLockDestinations.LOCK,
        modifier = modifier
    ) {
        composable(PhotoLockDestinations.LOCK) {
            val lockViewModel: LockViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        LockViewModel(
                            context = container.appContext,
                            pinManager = container.pinManager,
                            settingsStore = container.settingsStore,
                            repository = container.vaultRepository
                        )
                    }
                }
            )
            LockScreen(
                viewModel = lockViewModel,
                onUnlocked = {
                    container.lockSession.unlock()
                    navController.navigate(PhotoLockDestinations.VAULT) {
                        popUpTo(PhotoLockDestinations.LOCK) { inclusive = true }
                    }
                }
            )
        }

        composable(PhotoLockDestinations.VAULT) {
            val vaultViewModel: VaultViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        VaultViewModel(repository = container.vaultRepository)
                    }
                }
            )
            VaultGridScreen(
                viewModel = vaultViewModel,
                repository = container.vaultRepository,
                onOpenSettings = {
                    navController.navigate(PhotoLockDestinations.SETTINGS)
                },
                onOpenItem = { itemId ->
                    navController.navigate(PhotoLockDestinations.viewer(itemId))
                },
                onLockNow = { container.lockSession.lock() }
            )
        }

        composable(
            route = PhotoLockDestinations.VIEWER,
            arguments = listOf(
                navArgument("itemId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val itemId = backStackEntry.arguments?.getString("itemId") ?: ""
            val viewerViewModel: ViewerViewModel = viewModel(
                key = "viewer_$itemId",
                factory = viewModelFactory {
                    initializer {
                        ViewerViewModel(
                            repository = container.vaultRepository,
                            itemId = itemId
                        )
                    }
                }
            )
            ViewerScreen(
                viewModel = viewerViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(PhotoLockDestinations.SETTINGS) {
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { settingsViewModel(container) }
                }
            )
            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = { navController.popBackStack() },
                onOpenBreakInLog = {
                    navController.navigate(PhotoLockDestinations.BREAK_IN_LOG)
                },
                onLockNow = { container.lockSession.lock() }
            )
        }

        composable(PhotoLockDestinations.BREAK_IN_LOG) {
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { settingsViewModel(container) }
                }
            )
            BreakInLogScreen(
                viewModel = settingsViewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

private fun settingsViewModel(container: AppContainer): SettingsViewModel =
    SettingsViewModel(
        context = container.appContext,
        pinManager = container.pinManager,
        settingsStore = container.settingsStore,
        repository = container.vaultRepository
    )
