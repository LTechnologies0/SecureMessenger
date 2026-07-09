package ltechnologies.onionphone.securemessenger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import ltechnologies.onionphone.securemessenger.BuildConfig
import ltechnologies.onionphone.securemessenger.core.model.AuthStepKind
import ltechnologies.onionphone.securemessenger.core.model.ConnectionResult
import ltechnologies.onionphone.securemessenger.core.model.ConnectionState
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import ltechnologies.onionphone.securemessenger.ui.MainViewModel

private enum class TelegramLoginStep {
    PHONE,
    CODE,
    PASSWORD,
    REGISTRATION,
    OTHER_DEVICE,
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TelegramLoginScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel,
    onClose: () -> Unit,
    onConnected: (accountId: String) -> Unit,
) {
    var step by rememberSaveable { mutableStateOf(TelegramLoginStep.PHONE) }
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var firstName by rememberSaveable { mutableStateOf("") }
    var lastName by rememberSaveable { mutableStateOf("") }
    var accountId by rememberSaveable { mutableStateOf<String?>(null) }
    var statusMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by rememberSaveable { mutableStateOf(false) }

    val accounts by viewModel.accounts.collectAsState()
    val apiConfigured = BuildConfig.TELEGRAM_API_ID != 0 && BuildConfig.TELEGRAM_API_HASH.isNotBlank()

    LaunchedEffect(accounts, accountId) {
        val id = accountId ?: return@LaunchedEffect
        val account = accounts.firstOrNull { it.id == id } ?: return@LaunchedEffect
        if (account.connectionState == ConnectionState.CONNECTED) {
            loading = false
            onConnected(id)
        }
    }

    LaunchedEffect(accountId, step) {
        if (accountId == null || step != TelegramLoginStep.PHONE) return@LaunchedEffect
        val protocol = viewModel.telegramProtocol() ?: return@LaunchedEffect
        protocol.observePendingAuthStep().collectLatest { authStep ->
            if (authStep == null) return@collectLatest
            loading = false
            when (authStep.kind) {
                AuthStepKind.TELEGRAM_SMS_CODE -> {
                    step = TelegramLoginStep.CODE
                    statusMessage = authStep.prompt
                }
                AuthStepKind.TELEGRAM_PASSWORD -> {
                    step = TelegramLoginStep.PASSWORD
                    statusMessage = authStep.prompt
                }
                AuthStepKind.TELEGRAM_REGISTRATION -> {
                    step = TelegramLoginStep.REGISTRATION
                    statusMessage = authStep.prompt
                }
                AuthStepKind.TELEGRAM_OTHER_DEVICE -> {
                    step = TelegramLoginStep.OTHER_DEVICE
                    statusMessage = authStep.prompt
                }
                else -> Unit
            }
        }
    }

    LaunchedEffect(loading, accountId, step) {
        if (!loading || accountId == null || step != TelegramLoginStep.PHONE) return@LaunchedEffect
        delay(60_000)
        if (loading && step == TelegramLoginStep.PHONE) {
            loading = false
            statusMessage = "Tor n'est pas actif. Ouvrez Orbot ou InviZible, puis réessayez " +
                "(Paramètres → Proxy)."
            accountId?.let { viewModel.cancelTelegramLogin(it) }
            accountId = null
        }
    }

    fun handleClose() {
        accountId?.let { viewModel.cancelTelegramLogin(it) }
        onClose()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TopAppBar(
            title = { Text("Telegram") },
            navigationIcon = {
                IconButton(onClick = { handleClose() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                }
            },
        )

        if (!apiConfigured) {
            Text(
                text = "Telegram n'est pas encore activé dans cette application.\n\n" +
                    "Ce n'est pas un réglage pour vous en tant qu'utilisateur : c'est une étape " +
                    "unique pour la personne qui compile l'APK (comme pour l'app Telegram officielle, " +
                    "où ces identifiants sont déjà intégrés dans le code source).\n\n" +
                    "Voir le fichier local.properties.example ou lancer : scripts/setup-telegram-api.sh",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }

        when (step) {
            TelegramLoginStep.PHONE -> {
                Text(
                    text = "Votre numéro",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = "Comme sur l'app Telegram officielle : entrez votre numéro au format international.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Numéro de téléphone") },
                    placeholder = { Text("+33612345678") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val normalized = phone.trim()
                        if (normalized.isBlank()) {
                            statusMessage = "Entrez votre numéro de téléphone"
                            return@Button
                        }
                        if (!normalized.startsWith("+")) {
                            statusMessage = "Utilisez le format international, ex. +33612345678"
                            return@Button
                        }
                        loading = true
                        statusMessage = null
                        viewModel.connectTelegram(normalized) { result, newAccountId ->
                            accountId = newAccountId
                            when (result) {
                                is ConnectionResult.Success -> {
                                    statusMessage = "Connexion à Telegram…"
                                }
                                is ConnectionResult.Failure -> {
                                    loading = false
                                    statusMessage = result.reason
                                    viewModel.cancelTelegramLogin(newAccountId)
                                }
                            }
                        }
                    },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp))
                    } else {
                        Text("Continuer")
                    }
                }
            }

            TelegramLoginStep.CODE -> {
                Text(
                    text = "Code de vérification",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = statusMessage ?: "Nous avons envoyé un code au $phone.\n" +
                        "• Par SMS sur ce téléphone\n" +
                        "• Ou dans l'app Telegram si vous êtes déjà connecté ailleurs",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Code") },
                    placeholder = { Text("12345") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        if (code.isBlank()) {
                            statusMessage = "Entrez le code reçu"
                            return@Button
                        }
                        loading = true
                        statusMessage = null
                        viewModel.continueAuth(ProtocolId.TELEGRAM, mapOf("code" to code.trim())) { result ->
                            when (result) {
                                is ConnectionResult.Failure -> {
                                    loading = false
                                    statusMessage = result.reason
                                }
                                is ConnectionResult.Success -> {
                                    viewModel.pendingAuth(ProtocolId.TELEGRAM) { authStep ->
                                        when (authStep?.kind) {
                                            AuthStepKind.TELEGRAM_PASSWORD -> {
                                                loading = false
                                                step = TelegramLoginStep.PASSWORD
                                                statusMessage = null
                                            }
                                            AuthStepKind.TELEGRAM_SMS_CODE -> {
                                                loading = false
                                                statusMessage = "Code incorrect, réessayez"
                                            }
                                            else -> {
                                                // AuthorizationStateReady will flip account to CONNECTED
                                                statusMessage = "Connexion en cours…"
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (loading) "Vérification…" else "Vérifier")
                }
                Button(
                    onClick = {
                        loading = true
                        viewModel.resendTelegramCode { result ->
                            loading = false
                            statusMessage = when (result) {
                                is ConnectionResult.Success -> "Nouveau code envoyé"
                                is ConnectionResult.Failure -> result.reason
                            }
                        }
                    },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Renvoyer le code")
                }
            }

            TelegramLoginStep.REGISTRATION -> {
                Text(
                    text = "Créer votre profil",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = statusMessage ?: "Entrez votre prénom et nom comme sur Telegram.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = firstName,
                    onValueChange = { firstName = it },
                    label = { Text("Prénom") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = lastName,
                    onValueChange = { lastName = it },
                    label = { Text("Nom") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        loading = true
                        viewModel.continueAuth(
                            ProtocolId.TELEGRAM,
                            mapOf("firstName" to firstName.trim(), "lastName" to lastName.trim()),
                        ) { result ->
                            loading = false
                            statusMessage = when (result) {
                                is ConnectionResult.Success -> "Connexion en cours…"
                                is ConnectionResult.Failure -> result.reason
                            }
                        }
                    },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Créer le compte")
                }
            }

            TelegramLoginStep.OTHER_DEVICE -> {
                Text(
                    text = "Confirmer sur un autre appareil",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = statusMessage
                        ?: "Ouvrez Telegram sur un appareil déjà connecté et confirmez la connexion.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            TelegramLoginStep.PASSWORD -> {
                Text(
                    text = "Vérification en deux étapes",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = "Votre compte Telegram a un mot de passe 2FA. Entrez-le pour continuer.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Mot de passe") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        if (password.isBlank()) {
                            statusMessage = "Entrez votre mot de passe"
                            return@Button
                        }
                        loading = true
                        viewModel.continueAuth(
                            ProtocolId.TELEGRAM,
                            mapOf("password" to password),
                        ) { result ->
                            loading = false
                            statusMessage = when (result) {
                                is ConnectionResult.Success -> "Connexion en cours…"
                                is ConnectionResult.Failure -> result.reason
                            }
                        }
                    },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Confirmer")
                }
            }
        }

        statusMessage?.let {
            Text(
                text = it,
                color = if (it.contains("incorrect") || it.contains("échoué") || it.contains("manquant")) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }

        if (loading && step != TelegramLoginStep.PHONE) {
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
        }
    }
}
