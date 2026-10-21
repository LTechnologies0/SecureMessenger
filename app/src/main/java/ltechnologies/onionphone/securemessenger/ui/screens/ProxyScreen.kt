package ltechnologies.onionphone.securemessenger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ltechnologies.onionphone.securemessenger.core.model.TorProvider
import ltechnologies.onionphone.securemessenger.core.proxy.OnionVpnConstants
import ltechnologies.onionphone.securemessenger.core.proxy.ProxyConfigNormalizer
import ltechnologies.onionphone.securemessenger.ui.MainViewModel

private const val DEFAULT_CUSTOM_SOCKS_PORT = 9050

private fun TorProvider.label(): String = when (this) {
    TorProvider.ONIONVPN -> "OnionVPN"
    TorProvider.CUSTOM -> "Custom"
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProxyScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel,
    onClose: (() -> Unit)? = null,
) {
    val proxyStatus by viewModel.proxyStatus.collectAsState()
    val savedConfig = proxyStatus.config
    var host by remember(savedConfig.host, savedConfig.torProvider) {
        mutableStateOf(savedConfig.host)
    }
    var port by remember(savedConfig.port, savedConfig.torProvider) {
        mutableStateOf(savedConfig.port.toString())
    }
    var username by remember(savedConfig.username, savedConfig.torProvider) {
        mutableStateOf(savedConfig.username.orEmpty())
    }
    var password by remember(savedConfig.torProvider) {
        mutableStateOf("")
    }
    var passwordEdited by remember(savedConfig.torProvider) {
        mutableStateOf(false)
    }
    var torProvider by remember(savedConfig.torProvider) {
        mutableStateOf(savedConfig.torProvider)
    }
    var testResult by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TopAppBar(
            title = { Text("Proxy & Tor") },
            navigationIcon = {
                onClose?.let { close ->
                    IconButton(onClick = close) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                }
            },
        )

        Text("Routage Tor (obligatoire)")
        Text(
            "Tout le trafic passe par le pont SOCKS OnionVPN (PAC) ou un SOCKS custom. " +
                "Aucun chemin clearnet. Si OnionVPN est arrêté, les sessions sont coupées.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Fournisseur Tor : ${torProvider.label()}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TorProvider.entries.forEach { provider ->
                FilterChip(
                    selected = torProvider == provider,
                    onClick = { torProvider = provider },
                    label = { Text(provider.label()) },
                )
            }
        }

        when (torProvider) {
            TorProvider.ONIONVPN -> {
                Text("OnionVPN installé : ${proxyStatus.onionVpnInstalled}")
                Text("PAC : ${proxyStatus.pacUrl}")
                Text("SOCKS (DNSCrypt→Tor) : ${savedConfig.host}:${savedConfig.port}")
                Text("Pont opérationnel : ${proxyStatus.onionVpnRunning}")
                proxyStatus.lastCheckLatencyMs?.let {
                    Text("Dernier test SOCKS+DNS : ${it}ms")
                }
                Text(
                    "Le client lit ${OnionVpnConstants.PAC_URL} pour découvrir le SOCKS " +
                        "(pas d’auth côté app — le pont gère IsolateSOCKSAuth).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TorProvider.CUSTOM -> {
                Text("Proxy SOCKS5 personnalisé (daemon Tor, etc.)")
            }
        }

        Text("Proxy opérationnel : ${proxyStatus.proxyHealthy}")
        proxyStatus.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (torProvider == TorProvider.CUSTOM) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Hôte SOCKS5") },
                placeholder = { Text("127.0.0.1") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                label = { Text("Port SOCKS5") },
                placeholder = { Text(DEFAULT_CUSTOM_SOCKS_PORT.toString()) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Utilisateur SOCKS (optionnel)") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    passwordEdited = true
                },
                label = { Text("Mot de passe SOCKS (laisser vide pour effacer)") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text("Point de terminaison : ${savedConfig.host}:${savedConfig.port}")
        }

        Button(
            onClick = {
                val config = ProxyConfigNormalizer.configForSave(
                    torProvider = torProvider,
                    customHost = host,
                    customPort = port.toIntOrNull() ?: DEFAULT_CUSTOM_SOCKS_PORT,
                    resolvedStatus = savedConfig,
                    username = username,
                    password = if (passwordEdited) password else savedConfig.password,
                    torRequired = true,
                )
                viewModel.updateProxy(config)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Enregistrer")
        }

        Button(
            onClick = { viewModel.requestTorStart() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when (torProvider) {
                    TorProvider.ONIONVPN ->
                        if (proxyStatus.onionVpnInstalled) "Ouvrir OnionVPN" else "Installer OnionVPN"
                    TorProvider.CUSTOM -> "Rafraîchir le test proxy"
                },
            )
        }

        if (torProvider == TorProvider.ONIONVPN && !proxyStatus.onionVpnInstalled) {
            Button(
                onClick = { viewModel.openOnionVpnReleases() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Télécharger OnionVPN")
            }
        }

        Button(
            onClick = {
                viewModel.testProxy { ok ->
                    testResult = if (ok) {
                        val ms = proxyStatus.lastCheckLatencyMs
                        if (ms != null) "Proxy OK (${ms}ms, DNS distant via Tor)" else "Proxy OK"
                    } else {
                        proxyStatus.lastError ?: "Proxy SOCKS injoignable"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Tester SOCKS (+ DNS distant)")
        }

        testResult?.let { Text(it) }
    }
}
