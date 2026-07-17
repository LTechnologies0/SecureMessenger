package ltechnologies.onionphone.securemessenger.ui.applock

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ltechnologies.onionphone.securemessenger.core.security.AppLockAuthResult
import ltechnologies.onionphone.securemessenger.core.security.AppLockState

@Composable
fun AppLockGate(
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
    viewModel: AppLockViewModel = hiltViewModel(),
    unlockedContent: @Composable () -> Unit,
) {
    val lockState by viewModel.lockState.collectAsStateWithLifecycle()
    when (lockState) {
        AppLockState.DEVICE_INSECURE -> DeviceInsecureScreen()
        AppLockState.LOCKED -> AppLockScreen(viewModel = viewModel, snackbarHostState = snackbarHostState)
        AppLockState.UNLOCKED -> unlockedContent()
    }
}

@Composable
fun AppLockScreen(
    viewModel: AppLockViewModel = hiltViewModel(),
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
) {
    val activity = LocalContext.current as? FragmentActivity
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var promptShown by remember { mutableStateOf(false) }

    LaunchedEffect(errorMessage) {
        errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            errorMessage = null
        }
    }

    LaunchedEffect(activity, promptShown) {
        if (activity != null && !promptShown) {
            promptShown = true
            viewModel.authenticate(activity) { result ->
                when (result) {
                    is AppLockAuthResult.Failure -> errorMessage = result.message
                    is AppLockAuthResult.Cancelled -> promptShown = false
                    is AppLockAuthResult.Success -> Unit
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "SecureMessenger est verrouillé",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Déverrouillez avec le code PIN, le schéma ou la biométrie de votre appareil.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = {
                if (activity != null) {
                    viewModel.authenticate(activity) { result ->
                        when (result) {
                            is AppLockAuthResult.Failure -> errorMessage = result.message
                            is AppLockAuthResult.Cancelled -> Unit
                            is AppLockAuthResult.Success -> Unit
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = activity != null,
        ) {
            Text("Déverrouiller")
        }
    }
}

@Composable
fun DeviceInsecureScreen() {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Verrouillage système requis",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Configurez un code PIN, un schéma ou une biométrie dans les paramètres Android. " +
                "Sans verrouillage d'écran, les données chiffrées ne peuvent pas être protégées.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Ouvrir les paramètres de sécurité")
        }
    }
}
