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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.ui.MainViewModel
import ltechnologies.onionphone.securemessenger.ui.components.CapabilityChipRow
import ltechnologies.onionphone.securemessenger.ui.components.ProtocolAccentChip
import ltechnologies.onionphone.securemessenger.ui.components.capabilityLabels
import ltechnologies.onionphone.securemessenger.ui.components.protocolDisplayName
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
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(status) {
        val msg = status ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
    }

    val remoteHint = when {
        protocol == ProtocolId.XMPP && asGroup ->
            "JID salle (room@conference.domain)"
        protocol == ProtocolId.XMPP -> "JID (user@domain)"
        protocol == ProtocolId.MATRIX && asGroup ->
            "Room ID (!abc:server)"
        protocol == ProtocolId.MATRIX -> "Room ID ou @user:server"
        protocol == ProtocolId.TELEGRAM && asGroup ->
            "Chat ID / @canal — ou Titre|userId1,userId2 pour créer"
        protocol == ProtocolId.TELEGRAM ->
            "Chat ID, @username ou téléphone (+33…)"
        protocol == ProtocolId.SIGNAL && asGroup ->
            "gv2:… ou Titre|+33…,+44… / Titre|aci,aci"
        protocol == ProtocolId.SIGNAL -> "Numéro E.164 (+33…) ou ACI"
        protocol == ProtocolId.EMAIL -> "Adresse email (user@domain)"
        else -> "Identifiant distant"
    }

    val supportingHint = when (protocol) {
        ProtocolId.XMPP -> if (asGroup) {
            "Exemple : salon@conference.jabber.fr"
        } else {
            "Exemple : ami@jabber.fr"
        }
        ProtocolId.MATRIX -> if (asGroup) {
            "Exemple : !roomId:matrix.org"
        } else {
            "Exemple : @ami:matrix.org"
        }
        ProtocolId.TELEGRAM -> if (asGroup) {
            "Ouvrir un groupe existant, ou créer via Titre|userId1,userId2"
        } else {
            "Recherche téléphone disponible ci-dessous"
        }
        ProtocolId.SIGNAL -> if (asGroup) {
            "gv2:masterKey pour ouvrir, ou Titre|+e164 / ACI pour créer"
        } else {
            "Format international obligatoire (+…)"
        }
        ProtocolId.EMAIL -> "Exemple : ami@example.com"
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (accounts.none { it.protocol == protocol }) {
                Text(
                    "Connecte un compte ${protocolShortPrefix(protocol)} d'abord.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Text("Protocole", style = MaterialTheme.typography.titleSmall)
            if (boundAccount != null) {
                Text(
                    "${boundAccount.displayName} · ${protocolDisplayName(boundAccount.protocol)}",
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    viewModel.enabledProtocols.forEach { p ->
                        ProtocolAccentChip(
                            protocol = p,
                            selected = protocol == p,
                            onClick = {
                                protocol = p
                                asGroup = false
                            },
                        )
                    }
                }
            }

            CapabilityChipRow(labels = capabilityLabels(capabilities))

            if (capabilities.groupChats) {
                Text("Type", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !asGroup,
                        onClick = { asGroup = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) {
                        Text("Direct")
                    }
                    SegmentedButton(
                        selected = asGroup,
                        onClick = { asGroup = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) {
                        Text(
                            when (protocol) {
                                ProtocolId.XMPP -> "Salle MUC"
                                ProtocolId.MATRIX -> "Room"
                                ProtocolId.TELEGRAM -> "Groupe"
                                ProtocolId.SIGNAL -> "Groupe"
                                ProtocolId.EMAIL -> "Groupe"
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = remoteId,
                onValueChange = { remoteId = it },
                label = { Text(remoteHint) },
                supportingText = { Text(supportingHint) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            if (protocol == ProtocolId.TELEGRAM && !asGroup) {
                OutlinedButton(
                    onClick = {
                        val phone = remoteId.trim()
                        if (phone.isBlank()) {
                            status = "Numéro requis"
                            return@OutlinedButton
                        }
                        status = "Recherche…"
                        viewModel.searchTelegramUserByPhone(
                            phoneNumber = phone,
                            accountId = boundAccount?.id,
                        ) { contact ->
                            if (contact != null) {
                                remoteId = contact.remoteId
                                status = "Trouvé : ${contact.displayName}"
                            } else {
                                status = "Aucun utilisateur Telegram pour ce numéro"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Chercher par numéro de téléphone")
                }
            }

            OutlinedTextField(
                value = firstMessage,
                onValueChange = { firstMessage = it },
                label = { Text("Premier message (optionnel)") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
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
        }
    }
}
