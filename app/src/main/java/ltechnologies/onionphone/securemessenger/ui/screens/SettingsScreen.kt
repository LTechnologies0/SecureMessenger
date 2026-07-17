package ltechnologies.onionphone.securemessenger.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ltechnologies.onionphone.securemessenger.core.model.FeatureFlags
import ltechnologies.onionphone.securemessenger.core.security.AppLockManager
import ltechnologies.onionphone.securemessenger.core.security.AppLockState

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onOpenProxy: () -> Unit = {},
    onClose: (() -> Unit)? = null,
    appLockManager: AppLockManager = hiltViewModel<SettingsLockViewModel>().appLockManager,
) {
    val lockState by appLockManager.state.collectAsState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                onClose?.let { close ->
                    IconButton(onClick = close) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Fermer")
                    }
                }
            },
        )
        Text("SecureMessenger 1.0.0-alpha")
        Text(
            when (lockState) {
                AppLockState.UNLOCKED -> "Verrouillage : actif (code / biométrie système)"
                AppLockState.LOCKED -> "Verrouillage : en attente d'authentification"
                AppLockState.DEVICE_INSECURE -> "Verrouillage : configurez un PIN dans Android"
            },
        )
        Text("Données chiffrées (SQLCipher + Keystore) — déverrouillage système requis.")
        Text("Enabled protocols: ${FeatureFlags.enabled.joinToString { it.name }}")
        Text("Toutes les inscriptions et tout le trafic passent exclusivement par Tor (fail-closed).")
        Text("Signal : inscription API via Tor ; SMS via numéro / service en ligne.")
        Button(
            onClick = onOpenProxy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        ) {
            Text("Proxy / Tor")
        }
    }
}
