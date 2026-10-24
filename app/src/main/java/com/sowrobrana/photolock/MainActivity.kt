package com.sowrobrana.photolock

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.sowrobrana.photolock.ui.navigation.PhotoLockNavHost
import com.sowrobrana.photolock.ui.theme.PhotoLockTheme

/**
 * Single-activity host for the Compose UI.
 *
 * Extends [FragmentActivity] (not ComponentActivity) because
 * androidx.biometric's BiometricPrompt requires a FragmentActivity host.
 * Compose works identically on top of it.
 *
 * FLAG_SECURE is applied while the vault is unlocked so vault content
 * never leaks into screenshots or the recents thumbnail (PLAN.md §1.3);
 * it is removed again on the lock screen.
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as PhotoLockApp).container
        setContent {
            val unlocked by container.lockSession.isUnlocked.collectAsState()
            LaunchedEffect(unlocked) {
                if (unlocked) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
            PhotoLockTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PhotoLockNavHost(container = container)
                }
            }
        }
    }
}
