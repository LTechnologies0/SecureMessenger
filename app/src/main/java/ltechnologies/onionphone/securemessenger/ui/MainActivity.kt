package ltechnologies.onionphone.securemessenger.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import ltechnologies.onionphone.securemessenger.core.security.AppLockAuthenticator
import ltechnologies.onionphone.securemessenger.core.security.AppLockManager
import ltechnologies.onionphone.securemessenger.core.security.AppLockState
import ltechnologies.onionphone.securemessenger.protocol.signal.SignalForegroundService
import ltechnologies.onionphone.securemessenger.service.MessengerForegroundService
import ltechnologies.onionphone.securemessenger.ui.applock.AppLockGate
import ltechnologies.onionphone.securemessenger.ui.navigation.SecureMessengerNavHost
import ltechnologies.onionphone.securemessenger.ui.theme.SecureMessengerTheme

/**
 * Must be a [FragmentActivity]: [androidx.biometric.BiometricPrompt] requires it.
 * [androidx.activity.ComponentActivity] alone leaves LocalContext unable to cast → grayed Unlock.
 * Same host pattern as OnionVPN (works in Android private profiles).
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var appLockManager: AppLockManager
    @Inject lateinit var appLockAuthenticator: AppLockAuthenticator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        setContent {
            SecureMessengerTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                val lockState by appLockManager.state.collectAsStateWithLifecycle()
                LaunchedEffect(lockState) {
                    when (lockState) {
                        AppLockState.UNLOCKED -> {
                            startForegroundService(
                                Intent(this@MainActivity, MessengerForegroundService::class.java),
                            )
                        }
                        AppLockState.LOCKED, AppLockState.DEVICE_INSECURE -> {
                            stopService(Intent(this@MainActivity, MessengerForegroundService::class.java))
                            SignalForegroundService.stop(this@MainActivity)
                        }
                    }
                }
                AppLockGate(
                    authenticator = appLockAuthenticator,
                    appLockManager = appLockManager,
                ) {
                    SecureMessengerNavHost(snackbarHostState = snackbarHostState)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        appLockManager.lock()
    }
}
