package ltechnologies.onionphone.securemessenger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.ui.MainViewModel

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NewChatScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel,
    accountId: String? = null,
    defaultProtocol: ProtocolId = ProtocolId.XMPP,
    onBack: () -> Unit,
    onStarted: (conversationId: String, title: String, protocol: ProtocolId) -> Unit,
) {
    val accounts by viewModel.accounts.collectAsState()
    val boundAccount = accountId?.let { id -> accounts.firstOrNull { it.id == id } }
    var protocol by remember(boundAccount) {
        mutableStateOf(boundAccount?.protocol ?: defaultProtocol)
    }
    var remoteId by remember { mutableStateOf("") }
    var firstMessage by remember { mutableStateOf("") }
    var joinAsMuc by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Nouveau chat") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                }
            },
        )
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (accounts.none { it.protocol == protocol }) {
                Text("Connecte un compte ${protocol.name} d'abord.")
            }
            Text("Protocole")
            if (boundAccount != null) {
                Text("${boundAccount.displayName} · ${boundAccount.protocol.name}")
            } else {
                viewModel.enabledProtocols.forEach { p ->
                    FilterChip(
                        selected = protocol == p,
                        onClick = { protocol = p },
                        label = { Text(p.name) },
                    )
                }
            }
            if (protocol == ProtocolId.XMPP) {
                FilterChip(
                    selected = joinAsMuc,
                    onClick = { joinAsMuc = !joinAsMuc },
                    label = { Text("Rejoindre une salle MUC") },
                )
            }
            OutlinedTextField(
                value = remoteId,
                onValueChange = { remoteId = it },
                label = {
                    Text(
                        when {
                            protocol == ProtocolId.XMPP && joinAsMuc ->
                                "JID salle (room@conference.domain)"
                            protocol == ProtocolId.XMPP -> "JID (user@domain)"
                            protocol == ProtocolId.MATRIX -> "Room ID (!abc:server) ou @user:server"
                            protocol == ProtocolId.TELEGRAM -> "Chat ID ou @username"
                            protocol == ProtocolId.SIGNAL -> "Numéro E.164 (+33…) ou ACI"
                            else -> "Remote ID"
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = firstMessage,
                onValueChange = { firstMessage = it },
                label = { Text("Premier message (optionnel)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    if (remoteId.isBlank()) {
                        status = "Remote ID requis"
                        return@Button
                    }
                    viewModel.startConversation(
                        protocol,
                        remoteId.trim(),
                        firstMessage.takeIf { it.isNotBlank() },
                        accountId = boundAccount?.id,
                    ) { convId ->
                        if (convId != null) {
                            onStarted(convId, remoteId.trim(), protocol)
                        } else {
                            status = "Échec démarrage conversation"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (protocol == ProtocolId.XMPP && joinAsMuc) "Rejoindre" else "Démarrer")
            }
            status?.let { Text(it) }
        }
    }
}
