package ltechnologies.onionphone.securemessenger.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ltechnologies.onionphone.securemessenger.core.model.FeatureFlags
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.core.security.AppLockManager
import ltechnologies.onionphone.securemessenger.core.security.AppLockState
import ltechnologies.onionphone.securemessenger.ui.MainViewModel
import ltechnologies.onionphone.securemessenger.ui.components.protocolShortPrefix

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel? = null,
    onOpenProxy: () -> Unit = {},
    onClose: (() -> Unit)? = null,
    onDisconnectAccount: ((String) -> Unit)? = null,
    appLockManager: AppLockManager = hiltViewModel<SettingsLockViewModel>().appLockManager,
) {
    val lockState by appLockManager.state.collectAsState()
    val accounts = viewModel?.accounts?.collectAsState()?.value.orEmpty()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Paramètres") },
                navigationIcon = {
                    onClose?.let { close ->
                        IconButton(onClick = close) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Fermer")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Sécurité",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ListItem(
                leadingContent = { Icon(Icons.Default.Lock, contentDescription = null) },
                headlineContent = { Text("Verrouillage application") },
                supportingContent = {
                    Text(
                        when (lockState) {
                            AppLockState.UNLOCKED -> "Actif — code / biométrie système"
                            AppLockState.LOCKED -> "En attente d'authentification"
                            AppLockState.DEVICE_INSECURE -> "Configurez un PIN dans Android"
                        },
                    )
                },
            )
            ListItem(
                leadingContent = { Icon(Icons.Default.Security, contentDescription = null) },
                headlineContent = { Text("Chiffrement au repos") },
                supportingContent = {
                    Text("SQLCipher + Keystore — déverrouillage système requis")
                },
            )
            ListItem(
                leadingContent = { Icon(Icons.Default.VpnKey, contentDescription = null) },
                headlineContent = { Text("Proxy / Tor") },
                supportingContent = {
                    Text("Tout le trafic passe exclusivement par Tor (fail-closed)")
                },
                trailingContent = {
                    TextButton(onClick = onOpenProxy) { Text("Ouvrir") }
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = "Comptes",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (accounts.isEmpty()) {
                ListItem(
                    headlineContent = { Text("Aucun compte connecté") },
                    supportingContent = { Text("Ajoutez un compte depuis le rail") },
                )
            } else {
                accounts.forEach { account ->
                    ListItem(
                        headlineContent = { Text(account.displayName) },
                        supportingContent = {
                            Text("${protocolShortPrefix(account.protocol)} · ${account.connectionState.name}")
                        },
                        trailingContent = {
                            if (onDisconnectAccount != null) {
                                IconButton(onClick = { onDisconnectAccount(account.id) }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Logout,
                                        contentDescription = "Déconnecter",
                                    )
                                }
                            }
                        },
                    )
                }
            }
            if (viewModel != null) {
                ListItem(
                    leadingContent = { Icon(Icons.Default.Refresh, contentDescription = null) },
                    headlineContent = { Text("Reconnecter les sessions") },
                    supportingContent = { Text("Restaure les comptes persistés via Tor") },
                    trailingContent = {
                        TextButton(onClick = { viewModel.restoreSessions() }) {
                            Text("Relancer")
                        }
                    },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = "Protocoles",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            FeatureFlags.enabled.forEach { protocol ->
                ProtocolCapabilityRow(protocol = protocol, viewModel = viewModel)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            ListItem(
                headlineContent = { Text("SecureMessenger") },
                supportingContent = { Text("1.0.0-alpha · Tor-only multi-protocole") },
            )
        }
    }
}

@Composable
private fun ProtocolCapabilityRow(
    protocol: ProtocolId,
    viewModel: MainViewModel?,
) {
    val caps = viewModel?.capabilitiesFor(protocol)
    val canRegister = viewModel?.canRegister(protocol) == true
    val features = buildList {
        if (caps?.directMessages == true) add("DM")
        if (caps?.groupChats == true) add("groupes")
        if (caps?.mediaSend == true) add("médias")
        if (caps?.endToEndEncryption == true) add("E2EE")
        if (caps?.typingIndicators == true) add("frappe")
        if (caps?.readReceipts == true) add("lus")
        if (caps?.requiresPhoneAuth == true) add("téléphone")
        if (canRegister) add("inscription")
    }
    ListItem(
        headlineContent = { Text(protocolShortPrefix(protocol)) },
        supportingContent = {
            Text(
                if (features.isEmpty()) {
                    "Capacités indisponibles (déverrouillez / reconnectez)"
                } else {
                    features.joinToString(" · ")
                },
            )
        },
    )
}
