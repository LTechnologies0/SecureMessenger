package ltechnologies.onionphone.securemessenger.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import ltechnologies.onionphone.securemessenger.core.security.AppLockManager
import ltechnologies.onionphone.securemessenger.service.MessengerForegroundService
import ltechnologies.onionphone.securemessenger.ui.applock.AppLockGate
import ltechnologies.onionphone.securemessenger.ui.navigation.SecureMessengerNavHost
import ltechnologies.onionphone.securemessenger.ui.theme.SecureMessengerTheme

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var appLockManager: AppLockManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(
            Intent(this, MessengerForegroundService::class.java),
        )
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        setContent {
            SecureMessengerTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                AppLockGate(snackbarHostState = snackbarHostState) {
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
