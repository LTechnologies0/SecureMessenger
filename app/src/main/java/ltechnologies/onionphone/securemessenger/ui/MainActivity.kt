package ltechnologies.onionphone.securemessenger.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import dagger.hilt.android.AndroidEntryPoint
import ltechnologies.onionphone.securemessenger.service.MessengerForegroundService
import ltechnologies.onionphone.securemessenger.ui.navigation.SecureMessengerNavHost
import ltechnologies.onionphone.securemessenger.ui.theme.SecureMessengerTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(
            Intent(this, MessengerForegroundService::class.java),
        )
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            SecureMessengerTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                SecureMessengerNavHost(snackbarHostState = snackbarHostState)
            }
        }
    }
}
