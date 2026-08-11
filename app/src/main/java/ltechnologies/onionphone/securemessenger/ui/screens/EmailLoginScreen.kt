package ltechnologies.onionphone.securemessenger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.launch
import ltechnologies.onionphone.securemessenger.core.model.AccountCredentials
import ltechnologies.onionphone.securemessenger.core.model.ConnectionResult
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.protocol.email.EmailCredentialKeys
import ltechnologies.onionphone.securemessenger.protocol.email.EmailStoreKind
import ltechnologies.onionphone.securemessenger.protocol.email.MailSecurity
import ltechnologies.onionphone.securemessenger.ui.MainViewModel

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EmailLoginScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel,
    onClose: () -> Unit,
    onConnected: (accountId: String) -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var storeKind by remember { mutableStateOf(EmailStoreKind.IMAP) }

    var imapHost by remember { mutableStateOf("") }
    var imapPort by remember { mutableStateOf("993") }
    var imapSecurity by remember { mutableStateOf(MailSecurity.SSL) }
    var pop3Host by remember { mutableStateOf("") }
    var pop3Port by remember { mutableStateOf("995") }
    var pop3Security by remember { mutableStateOf(MailSecurity.SSL) }
    var smtpHost by remember { mutableStateOf("") }
    var smtpPort by remember { mutableStateOf("465") }
    var smtpSecurity by remember { mutableStateOf(MailSecurity.SSL) }
    var jmapSessionUrl by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("INBOX") }

    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TopAppBar(
            title = { Text("Email") },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                }
            },
        )

        Text(
            text = "IMAP / POP3 / JMAP + SMTP (Tor SOCKS si activé)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Adresse email") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Mot de passe") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text("Nom affiché (optionnel)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Text("Réception", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EmailStoreKind.entries.forEach { kind ->
                FilterChip(
                    selected = storeKind == kind,
                    onClick = { storeKind = kind },
                    label = { Text(kind.name) },
                )
            }
        }

        OutlinedButton(
            onClick = {
                if (email.isBlank()) {
                    status = "Saisissez une adresse email"
                    return@OutlinedButton
                }
                busy = true
                status = "Détection…"
                scope.launch {
                    try {
                        val result = viewModel.detectEmailSettings(email.trim())
                        if (result == null) {
                            status = "Autoconfig introuvable — saisissez les hôtes manuellement"
                            return@launch
                        }
                        result.imapHost?.let { imapHost = it }
                        result.imapPort?.let { imapPort = it.toString() }
                        result.imapSecurity?.let { imapSecurity = it }
                        result.pop3Host?.let { pop3Host = it }
                        result.pop3Port?.let { pop3Port = it.toString() }
                        result.pop3Security?.let { pop3Security = it }
                        result.smtpHost?.let { smtpHost = it }
                        result.smtpPort?.let { smtpPort = it.toString() }
                        result.smtpSecurity?.let { smtpSecurity = it }
                        result.jmapSessionUrl?.let { jmapSessionUrl = it }
                        if (result.imapHost != null) storeKind = EmailStoreKind.IMAP
                        else if (result.pop3Host != null) storeKind = EmailStoreKind.POP3
                        status = "Paramètres détectés (${result.source})"
                    } catch (e: Exception) {
                        status = e.message ?: "Détection échouée"
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Détecter les paramètres")
        }

        when (storeKind) {
            EmailStoreKind.IMAP -> {
                OutlinedTextField(imapHost, { imapHost = it }, label = { Text("Hôte IMAP") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(imapPort, { imapPort = it }, label = { Text("Port IMAP") }, modifier = Modifier.fillMaxWidth())
                SecurityChips("Sécurité IMAP", imapSecurity) { imapSecurity = it }
                OutlinedTextField(folder, { folder = it }, label = { Text("Dossier") }, modifier = Modifier.fillMaxWidth())
            }
            EmailStoreKind.POP3 -> {
                OutlinedTextField(pop3Host, { pop3Host = it }, label = { Text("Hôte POP3") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pop3Port, { pop3Port = it }, label = { Text("Port POP3") }, modifier = Modifier.fillMaxWidth())
                SecurityChips("Sécurité POP3", pop3Security) { pop3Security = it }
            }
            EmailStoreKind.JMAP -> {
                OutlinedTextField(
                    jmapSessionUrl,
                    { jmapSessionUrl = it },
                    label = { Text("URL session JMAP") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (storeKind != EmailStoreKind.JMAP) {
            OutlinedTextField(smtpHost, { smtpHost = it }, label = { Text("Hôte SMTP") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(smtpPort, { smtpPort = it }, label = { Text("Port SMTP") }, modifier = Modifier.fillMaxWidth())
            SecurityChips("Sécurité SMTP", smtpSecurity) { smtpSecurity = it }
        }

        status?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Button(
            onClick = {
                if (email.isBlank() || password.isBlank()) {
                    status = "Email et mot de passe requis"
                    return@Button
                }
                busy = true
                status = "Connexion…"
                val accountId = UUID.randomUUID().toString()
                val secrets = buildMap {
                    put(EmailCredentialKeys.EMAIL, email.trim())
                    put(EmailCredentialKeys.PASSWORD, password)
                    put(EmailCredentialKeys.STORE_KIND, storeKind.name)
                    put(EmailCredentialKeys.FOLDER, folder.ifBlank { "INBOX" })
                    when (storeKind) {
                        EmailStoreKind.IMAP -> {
                            put(EmailCredentialKeys.IMAP_HOST, imapHost.trim())
                            put(EmailCredentialKeys.IMAP_PORT, imapPort.trim())
                            put(EmailCredentialKeys.IMAP_SECURITY, imapSecurity.name)
                        }
                        EmailStoreKind.POP3 -> {
                            put(EmailCredentialKeys.POP3_HOST, pop3Host.trim())
                            put(EmailCredentialKeys.POP3_PORT, pop3Port.trim())
                            put(EmailCredentialKeys.POP3_SECURITY, pop3Security.name)
                            put(EmailCredentialKeys.POP3_LEAVE_ON_SERVER, "true")
                        }
                        EmailStoreKind.JMAP -> {
                            put(EmailCredentialKeys.JMAP_SESSION_URL, jmapSessionUrl.trim())
                        }
                    }
                    if (storeKind != EmailStoreKind.JMAP) {
                        put(EmailCredentialKeys.SMTP_HOST, smtpHost.trim())
                        put(EmailCredentialKeys.SMTP_PORT, smtpPort.trim())
                        put(EmailCredentialKeys.SMTP_SECURITY, smtpSecurity.name)
                    }
                }
                val creds = AccountCredentials(
                    protocol = ProtocolId.EMAIL,
                    accountId = accountId,
                    displayName = displayName.ifBlank { email.trim() },
                    secrets = secrets,
                )
                viewModel.connectAccount(creds) { result ->
                    busy = false
                    when (result) {
                        is ConnectionResult.Success -> onConnected(accountId)
                        is ConnectionResult.Failure -> status = result.reason
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            Text(if (busy) "Connexion…" else "Connecter")
        }
    }
}

@Composable
private fun SecurityChips(
    label: String,
    value: MailSecurity,
    onChange: (MailSecurity) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MailSecurity.entries.forEach { security ->
            FilterChip(
                selected = value == security,
                onClick = { onChange(security) },
                label = { Text(security.name) },
            )
        }
    }
}
