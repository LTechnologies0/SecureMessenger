package ltechnologies.onionphone.securemessenger.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import ltechnologies.onionphone.securemessenger.ui.util.qrImageBitmap

private enum class SignalLoginStep {
    CHOICE,
    PHONE,
    CAPTCHA,
    CODE,
    PIN,
    LINK_QR,
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SignalLoginScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel,
    onClose: () -> Unit,
    onConnected: (accountId: String) -> Unit,
) {
    var step by rememberSaveable { mutableStateOf(SignalLoginStep.CHOICE) }
    var phone by rememberSaveable { mutableStateOf("") }
    var captcha by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }
    var accountId by rememberSaveable { mutableStateOf<String?>(null) }
    var statusMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by rememberSaveable { mutableStateOf(false) }
    var linkUrl by remember { mutableStateOf<String?>(null) }

    val accounts by viewModel.accounts.collectAsState()
    val context = LocalContext.current
    val deviceLinkUrl by viewModel.observeSignalDeviceLinkUrl().collectAsState()

    LaunchedEffect(deviceLinkUrl) {
        if (!deviceLinkUrl.isNullOrBlank()) {
            linkUrl = deviceLinkUrl
            loading = false
        }
    }

    LaunchedEffect(accounts, accountId) {
        val id = accountId ?: return@LaunchedEffect
        val account = accounts.firstOrNull { it.id == id } ?: return@LaunchedEffect
        if (account.connectionState == ConnectionState.CONNECTED) {
            loading = false
            onConnected(id)
        }
    }

    // Also detect connect after device link (account id may change on complete).
    LaunchedEffect(accounts, step) {
        if (step != SignalLoginStep.LINK_QR) return@LaunchedEffect
        val connected = accounts.firstOrNull {
            it.protocol == ProtocolId.SIGNAL && it.connectionState == ConnectionState.CONNECTED
        } ?: return@LaunchedEffect
        loading = false
        onConnected(connected.id)
    }

    LaunchedEffect(accountId) {
        val id = accountId ?: return@LaunchedEffect
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
                AuthStepKind.SIGNAL_DEVICE_LINK -> {
                    step = SignalLoginStep.LINK_QR
                    statusMessage = authStep.prompt
                    if (!authStep.url.isNullOrBlank()) {
                        linkUrl = authStep.url
                    }
                    // Errors keep SIGNAL_DEVICE_LINK with a failure prompt and no success path.
                    if (authStep.prompt.contains("refusé", ignoreCase = true) ||
                        authStep.prompt.contains("échou", ignoreCase = true) ||
                        authStep.prompt.contains("invalide", ignoreCase = true) ||
                        authStep.prompt.contains("Erreur", ignoreCase = true) ||
                        authStep.prompt.contains("interrompu", ignoreCase = true)
                    ) {
                        loading = false
                    }
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
            statusMessage = "Délai dépassé. Vérifiez le numéro et la connexion, puis réessayez."
            accountId?.let { viewModel.cancelSignalLogin(it) }
            accountId = null
        }
    }

    LaunchedEffect(step, linkUrl) {
        if (step != SignalLoginStep.LINK_QR || linkUrl == null) return@LaunchedEffect
        delay(90_000)
        if (step == SignalLoginStep.LINK_QR) {
            statusMessage = "QR expiré — régénérez un nouveau code."
            loading = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.cancelSignalDeviceLink()
        }
    }

    fun handleClose() {
        accountId?.let { viewModel.cancelSignalLogin(it) }
        viewModel.cancelSignalDeviceLink()
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

        when (step) {
            SignalLoginStep.CHOICE -> {
                Text(
                    text = "Connexion Signal",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = "Créez un nouveau compte (SMS) ou liez un compte existant en scannant " +
                        "le QR depuis votre Signal principal (Paramètres → Appareils liés).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { step = SignalLoginStep.PHONE },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Nouveau numéro (SMS)")
                }
                OutlinedButton(
                    onClick = {
                        step = SignalLoginStep.LINK_QR
                        loading = true
                        statusMessage = "Génération du QR…"
                        linkUrl = null
                        viewModel.startSignalDeviceLink { result ->
                            when (result) {
                                is ConnectionResult.Success -> {
                                    statusMessage = "Scannez le QR depuis Signal"
                                }
                                is ConnectionResult.Failure -> {
                                    loading = false
                                    statusMessage = result.reason
                                    step = SignalLoginStep.CHOICE
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Lier un compte existant (QR)")
                }
            }

            SignalLoginStep.LINK_QR -> {
                Text(
                    text = "Lier cet appareil",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = "Sur votre téléphone Signal principal : Paramètres → Appareils liés → " +
                        "Lier un nouvel appareil, puis scannez ce QR. Après le scan, " +
                        "l'association se termine ici (ne fermez pas cet écran).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val url = linkUrl
                if (url != null && loading) {
                    Text(
                        text = "En attente du scan / finalisation…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(8.dp)
                            .align(Alignment.CenterHorizontally),
                    )
                }
                if (url != null) {
                    val qr = remember(url) { qrImageBitmap(url) }
                    Image(
                        bitmap = qr,
                        contentDescription = "QR de liaison Signal",
                        modifier = Modifier
                            .size(280.dp)
                            .align(Alignment.CenterHorizontally),
                    )
                    Text(
                        text = "Valide ~90 s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                } else if (loading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                Button(
                    onClick = {
                        loading = true
                        statusMessage = "Nouveau QR…"
                        linkUrl = null
                        viewModel.startSignalDeviceLink { result ->
                            when (result) {
                                is ConnectionResult.Success ->
                                    statusMessage = "Scannez le nouveau QR"
                                is ConnectionResult.Failure -> {
                                    loading = false
                                    statusMessage = result.reason
                                }
                            }
                        }
                    },
                    enabled = true,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Régénérer le QR")
                }
                TextButton(
                    onClick = {
                        viewModel.cancelSignalDeviceLink()
                        step = SignalLoginStep.CHOICE
                        loading = false
                        linkUrl = null
                        statusMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Retour")
                }
            }

            SignalLoginStep.PHONE -> {
                Text(
                    text = "Inscription Signal",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = "Le code SMS peut être reçu via un service SMS en ligne — l'app " +
                        "n'intercepte pas le SMS localement. Si Tor est activé, le trafic " +
                        "passe par OnionVPN.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                                    statusMessage = "Connexion Signal…"
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
                TextButton(
                    onClick = {
                        accountId?.let { viewModel.cancelSignalLogin(it) }
                        accountId = null
                        step = SignalLoginStep.CHOICE
                        loading = false
                    },
                ) {
                    Text("Retour")
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
                    Text("Ouvrir le captcha")
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

        if (loading && step != SignalLoginStep.PHONE && step != SignalLoginStep.LINK_QR && step != SignalLoginStep.CHOICE) {
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
