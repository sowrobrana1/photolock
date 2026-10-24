package com.sowrobrana.photolock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.sowrobrana.photolock.ui.navigation.PhotoLockNavHost
import com.sowrobrana.photolock.ui.theme.PhotoLockTheme

/**
 * Single-activity host for the Compose UI.
 *
 * NOTE(Phase 1): androidx.biometric's BiometricPrompt requires the host to be a
 * FragmentActivity. When biometric unlock is wired into the LockScreen, change
 * this activity's base class to FragmentActivity (Compose works the same).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhotoLockTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PhotoLockNavHost()
                }
            }
        }
    }
}
