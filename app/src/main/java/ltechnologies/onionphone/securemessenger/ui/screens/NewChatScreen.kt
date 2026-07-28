package ltechnologies.onionphone.securemessenger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import ltechnologies.onionphone.securemessenger.ui.components.protocolShortPrefix

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class,
)
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
    var asGroup by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val capabilities = remember(protocol) { viewModel.capabilitiesFor(protocol) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Nouveau chat") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (accounts.none { it.protocol == protocol }) {
                Text(
                    "Connecte un compte ${protocolShortPrefix(protocol)} d'abord.",
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Text("Protocole", style = MaterialTheme.typography.titleSmall)
            if (boundAccount != null) {
                Text(
                    "${boundAccount.displayName} · ${protocolShortPrefix(boundAccount.protocol)}",
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    viewModel.enabledProtocols.forEach { p ->
                        FilterChip(
                            selected = protocol == p,
                            onClick = {
                                protocol = p
                                asGroup = false
                            },
                            label = { Text(protocolShortPrefix(p)) },
                        )
                    }
                }
            }

            if (capabilities.groupChats) {
                FilterChip(
                    selected = asGroup,
                    onClick = { asGroup = !asGroup },
                    label = {
                        Text(
                            when (protocol) {
                                ProtocolId.XMPP -> "Rejoindre une salle MUC"
                                ProtocolId.MATRIX -> "Rejoindre une room"
                                ProtocolId.TELEGRAM -> "Groupe / canal"
                                ProtocolId.SIGNAL -> "Groupe Signal (gv2:…)"
                            },
                        )
                    },
                )
            }

            OutlinedTextField(
                value = remoteId,
                onValueChange = { remoteId = it },
                label = {
                    Text(
                        when {
                            protocol == ProtocolId.XMPP && asGroup ->
                                "JID salle (room@conference.domain)"
                            protocol == ProtocolId.XMPP -> "JID (user@domain)"
                            protocol == ProtocolId.MATRIX && asGroup ->
                                "Room ID (!abc:server)"
                            protocol == ProtocolId.MATRIX -> "Room ID ou @user:server"
                            protocol == ProtocolId.TELEGRAM && asGroup ->
                                "Chat ID groupe / @channel"
                            protocol == ProtocolId.TELEGRAM -> "Chat ID ou @username"
                            protocol == ProtocolId.SIGNAL && asGroup ->
                                "Identifiant gv2:… (groupe existant)"
                            protocol == ProtocolId.SIGNAL -> "Numéro E.164 (+33…) ou ACI"
                            else -> "Remote ID"
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    val hints = buildList {
                        if (capabilities.directMessages) add("DM")
                        if (capabilities.groupChats) add("groupes")
                        if (capabilities.mediaSend) add("médias")
                        if (capabilities.endToEndEncryption) add("E2EE")
                    }
                    if (hints.isNotEmpty()) {
                        Text("Capacités : ${hints.joinToString(" · ")}")
                    }
                },
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
                        status = "Identifiant requis"
                        return@Button
                    }
                    if (asGroup && !capabilities.groupChats) {
                        status = "Groupes non supportés pour ce protocole"
                        return@Button
                    }
                    viewModel.startConversation(
                        protocol = protocol,
                        remoteId = remoteId.trim(),
                        message = firstMessage.takeIf { it.isNotBlank() },
                        accountId = boundAccount?.id,
                        asGroup = asGroup,
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
                Text(if (asGroup) "Rejoindre / ouvrir le groupe" else "Démarrer")
            }
            status?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
