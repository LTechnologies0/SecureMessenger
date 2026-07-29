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
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ltechnologies.onionphone.securemessenger.core.security.AppLockAuthResult
import ltechnologies.onionphone.securemessenger.core.security.AppLockAuthenticator
import ltechnologies.onionphone.securemessenger.core.security.AppLockManager
import ltechnologies.onionphone.securemessenger.core.security.AppLockState

@Composable
fun AppLockGate(
    appLockManager: AppLockManager,
    authenticator: AppLockAuthenticator,
    unlockedContent: @Composable () -> Unit,
) {
    val lockState by appLockManager.state.collectAsStateWithLifecycle()
    when (lockState) {
        AppLockState.DEVICE_INSECURE -> DeviceInsecureScreen(
            onContinue = { appLockManager.markUnlocked() },
        )
        AppLockState.LOCKED -> AppLockScreen(
            authenticator = authenticator,
            onSuccess = { appLockManager.markUnlocked() },
        )
        AppLockState.UNLOCKED -> unlockedContent()
    }
}

@Composable
private fun AppLockScreen(
    authenticator: AppLockAuthenticator,
    onSuccess: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var promptShown by remember { mutableStateOf(false) }

    fun launchPrompt() {
        val act = activity ?: run {
            errorMessage = "Hôte FragmentActivity requis pour le déverrouillage"
            return
        }
        promptShown = true
        authenticator.authenticate(act) { result ->
            when (result) {
                is AppLockAuthResult.Failure -> {
                    errorMessage = result.message
                    promptShown = false
                }
                is AppLockAuthResult.Cancelled -> promptShown = false
                is AppLockAuthResult.Success -> onSuccess()
            }
        }
    }

    LaunchedEffect(activity) {
        if (activity != null && !promptShown) {
            launchPrompt()
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
            text = "Déverrouillez avec le code PIN, le schéma ou la biométrie (y compris profil privé).",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        errorMessage?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        Spacer(modifier = Modifier.height(24.dp))
        // Always enabled — OnionVPN pattern; show error if host activity missing.
        Button(
            onClick = { launchPrompt() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Déverrouiller")
        }
    }
}

@Composable
private fun DeviceInsecureScreen(onContinue: () -> Unit) {
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
            text = "Configurez un code PIN, un schéma ou une biométrie dans les paramètres Android " +
                "(ou du profil privé). Sans verrouillage d'écran, les données chiffrées ne peuvent " +
                "pas être protégées.",
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
        // Same escape hatch as OnionVPN for profiles where Keyguard reports insecure.
        TextButton(onClick = onContinue) {
            Text("Continuer sans verrouillage app")
        }
    }
}
