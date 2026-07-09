package ltechnologies.onionphone.securemessenger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.UUID
import ltechnologies.onionphone.securemessenger.core.model.AccountCredentials
import ltechnologies.onionphone.securemessenger.core.model.ConnectionResult
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.core.model.RegistrationRequest
import ltechnologies.onionphone.securemessenger.core.model.RegistrationResult
import ltechnologies.onionphone.securemessenger.ui.MainViewModel

private enum class FormMode { LOGIN, REGISTER }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AccountsScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel,
    initialProtocol: ProtocolId = ProtocolId.XMPP,
    onClose: (() -> Unit)? = null,
) {
    var selectedProtocol by remember { mutableStateOf(initialProtocol) }
    var mode by remember { mutableStateOf(FormMode.LOGIN) }
    var displayName by remember { mutableStateOf("") }
    var field1 by remember { mutableStateOf("") }
    var field2 by remember { mutableStateOf("") }
    var field3 by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val accounts by viewModel.accounts.collectAsState()

    // Registration-only state, kept separate from the login form above.
    var regServer by remember { mutableStateOf("") }
    var regUsername by remember { mutableStateOf("") }
    var regPassword by remember { mutableStateOf("") }
    var regConfirmPassword by remember { mutableStateOf("") }
    var pendingSessionId by remember { mutableStateOf<String?>(null) }
    var pendingFields by remember { mutableStateOf<List<ltechnologies.onionphone.securemessenger.core.model.RegistrationField>>(emptyList()) }
    var pendingInstructions by remember { mutableStateOf<String?>(null) }
    val pendingFieldValues = remember { mutableStateMapOf<String, String>() }
    var webViewState by remember { mutableStateOf<RegistrationResult.NeedsWebView?>(null) }

    fun resetRegistrationFlow() {
        pendingSessionId = null
        pendingFields = emptyList()
        pendingInstructions = null
        pendingFieldValues.clear()
        webViewState = null
    }

    fun handleRegistrationResult(result: RegistrationResult) {
        when (result) {
            is RegistrationResult.Success -> {
                resetRegistrationFlow()
                statusMessage = "Compte créé et connecté"
                mode = FormMode.LOGIN
            }
            is RegistrationResult.NeedsFields -> {
                pendingSessionId = result.sessionId
                pendingFields = result.fields
                pendingInstructions = result.instructions
                pendingFieldValues.clear()
                statusMessage = result.instructions
            }
            is RegistrationResult.NeedsWebView -> {
                pendingSessionId = result.sessionId
                webViewState = result
            }
            is RegistrationResult.Failure -> {
                resetRegistrationFlow()
                statusMessage = result.reason
            }
        }
    }

    webViewState?.let { pending ->
        val (socksHost, socksPort) = viewModel.resolvedSocksEndpoint()
        RegistrationWebViewDialog(
            url = pending.url,
            instructions = pending.instructions,
            socksHost = socksHost,
            socksPort = socksPort,
            onContinue = {
                val sessionId = pending.sessionId
                webViewState = null
                viewModel.continueRegistration(selectedProtocol, sessionId, emptyMap()) { result ->
                    handleRegistrationResult(result)
                }
            },
            onDismiss = {
                webViewState = null
                resetRegistrationFlow()
            },
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TopAppBar(
            title = { Text("Accounts") },
            navigationIcon = {
                onClose?.let { close ->
                    IconButton(onClick = close) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Fermer")
                    }
                }
            },
        )

        Text("Protocole : ${selectedProtocol.name}")

        if (selectedProtocol != ProtocolId.TELEGRAM) {
            TextButton(
                onClick = {
                    mode = if (mode == FormMode.LOGIN) FormMode.REGISTER else FormMode.LOGIN
                    statusMessage = null
                    resetRegistrationFlow()
                },
            ) {
                Text(
                    if (mode == FormMode.LOGIN) {
                        "Pas de compte ? En créer un"
                    } else {
                        "Déjà un compte ? Se connecter"
                    },
                )
            }
        }

        if (mode == FormMode.LOGIN) {
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Display name") },
                modifier = Modifier.fillMaxWidth(),
            )

            when (selectedProtocol) {
                ProtocolId.XMPP -> {
                    OutlinedTextField(field1, { field1 = it }, label = { Text("JID (user@domain)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(field2, { field2 = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(field3, { field3 = it }, label = { Text("Server (optional)") }, modifier = Modifier.fillMaxWidth())
                }
                ProtocolId.MATRIX -> {
                    OutlinedTextField(field1, { field1 = it }, label = { Text("Homeserver (ex. matrix.org)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(field2, { field2 = it }, label = { Text("User ID (@user:server)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(field3, { field3 = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth())
                }
                ProtocolId.TELEGRAM -> {
                    Text("Utilisez l'écran Telegram dédié depuis « Ajouter un compte ».")
                }
                else -> Text("Protocol not enabled in this build.")
            }

            Button(
                onClick = {
                    if (selectedProtocol == ProtocolId.TELEGRAM) return@Button
                    val secrets = when (selectedProtocol) {
                        ProtocolId.XMPP -> mapOf(
                            "jid" to field1.trim(),
                            "password" to field2,
                            "server" to field3,
                        )
                        ProtocolId.MATRIX -> mapOf(
                            "homeserver" to field1.trim(),
                            "userId" to field2.trim(),
                            "password" to field3,
                        )
                        ProtocolId.TELEGRAM -> emptyMap()
                        else -> emptyMap()
                    }
                    val creds = AccountCredentials(
                        protocol = selectedProtocol,
                        accountId = UUID.randomUUID().toString(),
                        displayName = displayName.ifBlank { selectedProtocol.name },
                        secrets = secrets,
                    )
                    viewModel.connectAccount(creds) { result ->
                        statusMessage = when (result) {
                            is ConnectionResult.Success -> "Connecté"
                            is ConnectionResult.Failure -> result.reason
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedProtocol != ProtocolId.TELEGRAM,
            ) {
                Text("Connect")
            }
        } else {
            // Registration mode — same protocols, different flow (create a brand new account).
            when (selectedProtocol) {
                ProtocolId.XMPP -> {
                    OutlinedTextField(regServer, { regServer = it }, label = { Text("Domaine (ex. jabber.org)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(regUsername, { regUsername = it }, label = { Text("Nom d'utilisateur souhaité") }, modifier = Modifier.fillMaxWidth())
                }
                ProtocolId.MATRIX -> {
                    OutlinedTextField(regServer, { regServer = it }, label = { Text("Homeserver (ex. matrix.org)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(regUsername, { regUsername = it }, label = { Text("Nom d'utilisateur souhaité") }, modifier = Modifier.fillMaxWidth())
                }
                else -> Text("La création de compte n'est pas disponible pour ce protocole.")
            }

            if (selectedProtocol == ProtocolId.XMPP || selectedProtocol == ProtocolId.MATRIX) {
                OutlinedTextField(regPassword, { regPassword = it }, label = { Text("Mot de passe") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(regConfirmPassword, { regConfirmPassword = it }, label = { Text("Confirmer le mot de passe") }, modifier = Modifier.fillMaxWidth())

                if (pendingSessionId != null && pendingFields.isNotEmpty()) {
                    pendingInstructions?.let { Text(it) }
                    pendingFields.forEach { field ->
                        OutlinedTextField(
                            value = pendingFieldValues[field.key].orEmpty(),
                            onValueChange = { pendingFieldValues[field.key] = it },
                            label = { Text(field.label) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Button(
                        onClick = {
                            val sessionId = pendingSessionId ?: return@Button
                            viewModel.continueRegistration(selectedProtocol, sessionId, pendingFieldValues.toMap()) { result ->
                                handleRegistrationResult(result)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Continuer")
                    }
                } else {
                    Button(
                        onClick = {
                            if (regPassword != regConfirmPassword) {
                                statusMessage = "Les mots de passe ne correspondent pas"
                                return@Button
                            }
                            if (regServer.isBlank() || regUsername.isBlank() || regPassword.isBlank()) {
                                statusMessage = "Merci de remplir tous les champs"
                                return@Button
                            }
                            val request = RegistrationRequest(
                                protocol = selectedProtocol,
                                server = regServer.trim(),
                                username = regUsername.trim(),
                                password = regPassword,
                            )
                            viewModel.registerAccount(request) { result ->
                                handleRegistrationResult(result)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Créer le compte")
                    }
                }
            }
        }

        statusMessage?.let { Text(it) }

        Text("Connected accounts: ${accounts.size}")
        accounts.forEach { account ->
            RowAccount(
                label = "${account.protocol.name}: ${account.displayName} (${account.connectionState})",
                onDisconnect = {
                    viewModel.disconnectAccount(account.id) {
                        statusMessage = "Compte supprimé"
                    }
                },
            )
        }
    }
}

@Composable
private fun RowAccount(label: String, onDisconnect: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label)
        OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
            Text("Disconnect")
        }
    }
}
