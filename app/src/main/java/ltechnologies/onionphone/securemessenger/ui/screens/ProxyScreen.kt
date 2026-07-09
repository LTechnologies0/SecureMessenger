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
import androidx.compose.material3.Switch
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
import ltechnologies.onionphone.securemessenger.core.proxy.ProxyConfigNormalizer
import ltechnologies.onionphone.securemessenger.ui.MainViewModel

private const val DEFAULT_SOCKS_PORT = 9050

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

        Text("Fournisseur Tor : ${torProvider.name}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TorProvider.entries.forEach { provider ->
                FilterChip(
                    selected = torProvider == provider,
                    onClick = { torProvider = provider },
                    label = { Text(provider.name) },
                )
            }
        }

        when (torProvider) {
            TorProvider.ORBOT -> {
                Text("Orbot installé : ${proxyStatus.orbotInstalled}")
                proxyStatus.orbotStatus?.let { Text("Statut Orbot : $it") }
                Text("SOCKS Orbot : ${savedConfig.host}:${savedConfig.port}")
                Text("Tor actif (Orbot) : ${proxyStatus.orbotTorOn}")
                Text("SOCKS opérationnel : ${proxyStatus.orbotRunning}")
            }
            TorProvider.INVIZIBLE -> {
                Text("InviZible installé : ${proxyStatus.invizibleInstalled}")
                Text("SOCKS InviZible : ${savedConfig.host}:${savedConfig.port}")
                Text("SOCKS opérationnel : ${proxyStatus.invizibleRunning}")
                proxyStatus.lastCheckLatencyMs?.let {
                    Text("Dernier test SOCKS+DNS : ${it}ms")
                }
            }
            TorProvider.CUSTOM -> {
                Text("Proxy SOCKS5 personnalisé (Orbot, Tor daemon, etc.)")
            }
        }

        Text("Proxy opérationnel : ${proxyStatus.proxyHealthy}")
        proxyStatus.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Tor obligatoire")
                Text(
                    "Tout le trafic (Telegram, Matrix, XMPP) passe par SOCKS5. Aucun contournement clearnet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = true, onCheckedChange = {}, enabled = false)
        }

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
                placeholder = { Text(DEFAULT_SOCKS_PORT.toString()) },
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
                onValueChange = { password = it },
                label = { Text("Mot de passe SOCKS (optionnel)") },
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
                    customPort = port.toIntOrNull() ?: DEFAULT_SOCKS_PORT,
                    resolvedStatus = savedConfig,
                    username = username,
                    password = password.ifBlank { savedConfig.password },
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
            enabled = when (torProvider) {
                TorProvider.ORBOT -> proxyStatus.orbotInstalled
                TorProvider.INVIZIBLE -> proxyStatus.invizibleInstalled
                TorProvider.CUSTOM -> true
            },
        ) {
            Text(
                when (torProvider) {
                    TorProvider.ORBOT -> "Interroger Orbot"
                    TorProvider.INVIZIBLE -> "Ouvrir InviZible"
                    TorProvider.CUSTOM -> "Rafraîchir le test proxy"
                },
            )
        }

        if (torProvider == TorProvider.INVIZIBLE && !proxyStatus.invizibleInstalled) {
            Button(
                onClick = { viewModel.openInvizibleStore() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Installer InviZible")
            }
        }

        Button(
            onClick = {
                viewModel.testProxy { ok ->
                    testResult = if (ok) {
                        val ms = proxyStatus.lastCheckLatencyMs
                        if (ms != null) "Proxy OK (${ms}ms, DNS distant via Tor)" else "Proxy OK"
                    } else {
                        proxyStatus.lastError ?: "Proxy injoignable ou killswitch actif"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Tester SOCKS (+ DNS distant)")
        }

        testResult?.let { Text(it) }

        Text(
            text = when (torProvider) {
                TorProvider.ORBOT ->
                    "Orbot : port SOCKS détecté automatiquement. Activez Tor dans Orbot."
                TorProvider.INVIZIBLE ->
                    "InviZible : activez Tor et SOCKS dans l'app (port par défaut 9050)."
                TorProvider.CUSTOM ->
                    "CUSTOM : utilisez 127.0.0.1 (pas « localhost ») et le port SOCKS affiché " +
                        "dans Orbot. Le test vérifie TCP vers le proxy puis une connexion DNS via Tor."
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
