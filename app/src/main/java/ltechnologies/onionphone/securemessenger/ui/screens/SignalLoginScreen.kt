package ltechnologies.onionphone.securemessenger.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import ltechnologies.onionphone.securemessenger.core.model.AuthStepKind
import ltechnologies.onionphone.securemessenger.core.model.ConnectionResult
import ltechnologies.onionphone.securemessenger.core.model.ConnectionState
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.protocol.signal.SignalServiceEnvironment
import ltechnologies.onionphone.securemessenger.ui.MainViewModel

private enum class SignalLoginStep {
    PHONE,
    CAPTCHA,
    CODE,
    PIN,
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SignalLoginScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel,
    onClose: () -> Unit,
    onConnected: (accountId: String) -> Unit,
) {
    var step by rememberSaveable { mutableStateOf(SignalLoginStep.PHONE) }
    var phone by rememberSaveable { mutableStateOf("") }
    var captcha by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }
    var accountId by rememberSaveable { mutableStateOf<String?>(null) }
    var statusMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by rememberSaveable { mutableStateOf(false) }

    val accounts by viewModel.accounts.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(accounts, accountId) {
        val id = accountId ?: return@LaunchedEffect
        val account = accounts.firstOrNull { it.id == id } ?: return@LaunchedEffect
        if (account.connectionState == ConnectionState.CONNECTED) {
            loading = false
            onConnected(id)
        }
    }

    LaunchedEffect(accountId, step) {
        if (accountId == null || step != SignalLoginStep.PHONE) return@LaunchedEffect
        val protocol = viewModel.signalProtocol() ?: return@LaunchedEffect
        protocol.observePendingAuthStep().collectLatest { authStep ->
            if (authStep == null) return@collectLatest
            loading = false
            when (authStep.kind) {
                AuthStepKind.SIGNAL_CAPTCHA -> {
                    step = SignalLoginStep.CAPTCHA
                    statusMessage = authStep.prompt
                }
                AuthStepKind.SIGNAL_SMS_CODE -> {
                    step = SignalLoginStep.CODE
                    statusMessage = authStep.prompt
                }
                AuthStepKind.SIGNAL_PIN -> {
                    step = SignalLoginStep.PIN
                    statusMessage = authStep.prompt
                }
                else -> Unit
            }
        }
    }

    LaunchedEffect(loading, accountId, step) {
        if (!loading || accountId == null || step != SignalLoginStep.PHONE) return@LaunchedEffect
        delay(90_000)
        if (loading && step == SignalLoginStep.PHONE) {
            loading = false
            statusMessage = "Tor requis : démarrez Orbot ou InviZible, puis réessayez."
            accountId?.let { viewModel.cancelSignalLogin(it) }
            accountId = null
        }
    }

    fun handleClose() {
        accountId?.let { viewModel.cancelSignalLogin(it) }
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
            title = { Text("Signal") },
            navigationIcon = {
                IconButton(onClick = { handleClose() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                }
            },
        )

        Text(
            text = "Inscription via Tor",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Les appels API passent par Tor. Le code SMS peut être reçu via un service " +
                "SMS en ligne sur le numéro que vous indiquez — l'app n'intercepte pas le SMS localement.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (step) {
            SignalLoginStep.PHONE -> {
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Numéro E.164") },
                    placeholder = { Text("+33612345678") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val normalized = phone.trim()
                        if (!normalized.startsWith("+")) {
                            statusMessage = "Format international requis, ex. +33612345678"
                            return@Button
                        }
                        loading = true
                        statusMessage = null
                        viewModel.connectSignal(normalized) { result, newAccountId ->
                            accountId = newAccountId
                            when (result) {
                                is ConnectionResult.Success -> {
                                    statusMessage = "Connexion Signal via Tor…"
                                }
                                is ConnectionResult.Failure -> {
                                    loading = false
                                    statusMessage = result.reason
                                    viewModel.cancelSignalLogin(newAccountId)
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

            SignalLoginStep.CAPTCHA -> {
                Text(
                    text = "Captcha Signal",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = statusMessage ?: "Ouvrez le captcha, résolvez-le, puis collez le token signalcaptcha://…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(SignalServiceEnvironment.CAPTCHA_URL)),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Ouvrir le captcha (navigateur / Tor)")
                }
                OutlinedTextField(
                    value = captcha,
                    onValueChange = { captcha = it },
                    label = { Text("Token captcha") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        if (captcha.isBlank()) {
                            statusMessage = "Collez le token captcha"
                            return@Button
                        }
                        loading = true
                        viewModel.continueAuth(
                            ProtocolId.SIGNAL,
                            mapOf("captcha" to captcha.trim()),
                        ) { result ->
                            loading = false
                            statusMessage = when (result) {
                                is ConnectionResult.Success -> "Captcha accepté, demande SMS…"
                                is ConnectionResult.Failure -> result.reason
                            }
                        }
                    },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Valider le captcha")
                }
            }

            SignalLoginStep.CODE -> {
                Text(
                    text = "Code SMS",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = statusMessage ?: "Entrez le code reçu sur $phone (SMS en ligne accepté).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        if (code.isBlank()) {
                            statusMessage = "Entrez le code SMS"
                            return@Button
                        }
                        loading = true
                        viewModel.continueAuth(
                            ProtocolId.SIGNAL,
                            mapOf("code" to code.trim()),
                        ) { result ->
                            loading = false
                            statusMessage = when (result) {
                                is ConnectionResult.Success -> "Vérification en cours…"
                                is ConnectionResult.Failure -> result.reason
                            }
                        }
                    },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Vérifier")
                }
                Button(
                    onClick = {
                        loading = true
                        viewModel.resendSignalCode { result ->
                            loading = false
                            statusMessage = when (result) {
                                is ConnectionResult.Success -> "Nouveau code demandé"
                                is ConnectionResult.Failure -> result.reason
                            }
                        }
                    },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Renvoyer le SMS")
                }
            }

            SignalLoginStep.PIN -> {
                Text(
                    text = "PIN (optionnel)",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = "Registration Lock : laissez vide pour continuer sans PIN.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        loading = true
                        viewModel.continueAuth(
                            ProtocolId.SIGNAL,
                            mapOf("pin" to pin.trim()),
                        ) { result ->
                            loading = false
                            statusMessage = when (result) {
                                is ConnectionResult.Success -> "Finalisation…"
                                is ConnectionResult.Failure -> result.reason
                            }
                        }
                    },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (pin.isBlank()) "Continuer sans PIN" else "Enregistrer avec PIN")
                }
            }
        }

        statusMessage?.let {
            Text(
                text = it,
                color = if (it.contains("incorrect") || it.contains("échoué") || it.contains("failed", true)) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }

        if (loading && step != SignalLoginStep.PHONE) {
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
