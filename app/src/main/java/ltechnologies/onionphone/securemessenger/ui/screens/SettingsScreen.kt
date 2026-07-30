package ltechnologies.onionphone.securemessenger.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.io.File
import ltechnologies.onionphone.securemessenger.core.model.Account
import ltechnologies.onionphone.securemessenger.core.model.AccountProfile
import ltechnologies.onionphone.securemessenger.core.model.BackupExportResult
import ltechnologies.onionphone.securemessenger.core.model.FeatureFlags
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.core.security.AppLockManager
import ltechnologies.onionphone.securemessenger.core.security.AppLockState
import ltechnologies.onionphone.securemessenger.ui.MainViewModel
import ltechnologies.onionphone.securemessenger.ui.components.CapabilityChipRow
import ltechnologies.onionphone.securemessenger.ui.components.ProtocolAvatar
import ltechnologies.onionphone.securemessenger.ui.components.capabilityLabels
import ltechnologies.onionphone.securemessenger.ui.components.connectionStateLabel
import ltechnologies.onionphone.securemessenger.ui.components.protocolDisplayName
import ltechnologies.onionphone.securemessenger.ui.components.protocolShortPrefix

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel? = null,
    onOpenProxy: () -> Unit = {},
    onClose: (() -> Unit)? = null,
    onDisconnectAccount: ((String) -> Unit)? = null,
    appLockManager: AppLockManager = hiltViewModel<SettingsLockViewModel>().appLockManager,
) {
    val lockState by appLockManager.state.collectAsState()
    val accounts = viewModel?.accounts?.collectAsState()?.value.orEmpty()
    val proxyStatus = viewModel?.proxyStatus?.collectAsState()?.value
    val context = LocalContext.current
    var profileAccount by remember { mutableStateOf<Account?>(null) }
    var backupStatus by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(backupStatus) {
        val msg = backupStatus ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Paramètres") },
                navigationIcon = {
                    onClose?.let { close ->
                        IconButton(onClick = close) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Fermer")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSectionHeader("Sécurité")
            ListItem(
                leadingContent = { Icon(Icons.Default.Lock, contentDescription = null) },
                headlineContent = { Text("Verrouillage application") },
                supportingContent = {
                    Text(
                        when (lockState) {
                            AppLockState.UNLOCKED -> "Actif — code / biométrie système"
                            AppLockState.LOCKED -> "En attente d'authentification"
                            AppLockState.DEVICE_INSECURE -> "Configurez un PIN dans Android"
                        },
                    )
                },
            )
            ListItem(
                leadingContent = { Icon(Icons.Default.Security, contentDescription = null) },
                headlineContent = { Text("Chiffrement au repos") },
                supportingContent = {
                    Text("SQLCipher + Keystore — déverrouillage système requis")
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingsSectionHeader("Proxy / Tor")
            ListItem(
                leadingContent = { Icon(Icons.Default.VpnKey, contentDescription = null) },
                headlineContent = { Text("Routage réseau") },
                supportingContent = {
                    Text(
                        if (proxyStatus?.config?.torRequired == true) {
                            "Tor via OnionVPN PAC (tous protocoles)"
                        } else {
                            "Clearnet (Tor désactivé)"
                        },
                    )
                },
                trailingContent = {
                    FilledTonalButton(onClick = onOpenProxy) { Text("Configurer") }
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingsSectionHeader("Compte")
            if (accounts.isEmpty()) {
                ListItem(
                    headlineContent = { Text("Aucun compte connecté") },
                    supportingContent = { Text("Ajoutez un compte depuis le rail") },
                )
            } else {
                accounts.forEach { account ->
                    val caps = viewModel?.capabilitiesFor(account.protocol)
                    ListItem(
                        leadingContent = { ProtocolAvatar(protocol = account.protocol) },
                        headlineContent = { Text(account.displayName) },
                        supportingContent = {
                            Text(
                                "${protocolDisplayName(account.protocol)} · " +
                                    connectionStateLabel(account.connectionState),
                            )
                        },
                        trailingContent = {
                            if (caps?.profileEdit == true) {
                                IconButton(onClick = { profileAccount = account }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Profil")
                                }
                            }
                        },
                    )
                }
            }

            if (viewModel != null) {
                ListItem(
                    leadingContent = { Icon(Icons.Default.Refresh, contentDescription = null) },
                    headlineContent = { Text("Reconnecter les sessions") },
                    supportingContent = { Text("Restaure les comptes persistés via Tor") },
                    trailingContent = {
                        OutlinedButton(onClick = { viewModel.restoreSessions() }) {
                            Text("Relancer")
                        }
                    },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingsSectionHeader("Capacités par protocole")
            FeatureFlags.enabled.forEach { protocol ->
                ProtocolCapabilityRow(protocol = protocol, viewModel = viewModel)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingsSectionHeader("Sauvegarde")
            if (accounts.isEmpty() || viewModel == null) {
                ListItem(
                    headlineContent = { Text("Export local") },
                    supportingContent = { Text("Connectez un compte pour exporter") },
                )
            } else {
                accounts.forEach { account ->
                    val caps = viewModel.capabilitiesFor(account.protocol)
                    if (caps.backupExport) {
                        ListItem(
                            leadingContent = {
                                Icon(Icons.Default.Download, contentDescription = null)
                            },
                            headlineContent = {
                                Text("Exporter · ${protocolShortPrefix(account.protocol)}")
                            },
                            supportingContent = {
                                Text(account.displayName)
                            },
                            trailingContent = {
                                TextButton(
                                    onClick = {
                                        val dest = File(
                                            context.filesDir,
                                            "backup_${account.id}_${System.currentTimeMillis()}.json",
                                        )
                                        viewModel.exportBackup(
                                            accountId = account.id,
                                            destinationPath = dest.absolutePath,
                                            protocol = account.protocol,
                                        ) { result ->
                                            backupStatus = when (result) {
                                                is BackupExportResult.Success ->
                                                    "Export : ${result.conversationCount} conv. / " +
                                                        "${result.messageCount} msg → ${result.uriOrPath}"
                                                is BackupExportResult.Failure -> result.reason
                                            }
                                        }
                                    },
                                ) { Text("Exporter") }
                            },
                        )
                    }
                }
            }

            if (onDisconnectAccount != null && accounts.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SettingsSectionHeader("Déconnexion")
                accounts.forEach { account ->
                    ListItem(
                        leadingContent = {
                            Icon(
                                Icons.AutoMirrored.Filled.Logout,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        headlineContent = {
                            Text(
                                "Déconnecter ${account.displayName}",
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        supportingContent = {
                            Text(protocolDisplayName(account.protocol))
                        },
                        trailingContent = {
                            TextButton(onClick = { onDisconnectAccount(account.id) }) {
                                Text("OK", color = MaterialTheme.colorScheme.error)
                            }
                        },
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            ListItem(
                headlineContent = { Text("SecureMessenger") },
                supportingContent = { Text("1.0.0-alpha · Tor-only multi-protocole") },
            )
        }
    }

    profileAccount?.let { account ->
        if (viewModel != null) {
            ProfileEditDialog(
                account = account,
                viewModel = viewModel,
                onDismiss = { profileAccount = null },
            )
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ProfileEditDialog(
    account: Account,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
) {
    var displayName by remember { mutableStateOf(account.displayName) }
    var bio by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null || account.protocol != ProtocolId.TELEGRAM) return@rememberLauncherForActivityResult
        val dest = File(context.cacheDir, "profile_${account.id}_${System.currentTimeMillis()}.jpg")
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.absolutePath
        }.getOrNull()
        if (copied == null) {
            status = "Impossible de lire la photo"
            return@rememberLauncherForActivityResult
        }
        viewModel.setTelegramProfilePhoto(account.id, copied) { result ->
            result.fold(
                onSuccess = { status = "Photo de profil mise à jour" },
                onFailure = { status = it.message ?: "Échec photo de profil" },
            )
        }
    }

    LaunchedEffect(account.id) {
        viewModel.loadAccountProfile(account.id, account.protocol) { profile: AccountProfile? ->
            if (profile != null) {
                displayName = profile.displayName
                bio = profile.bio.orEmpty()
            }
            loaded = true
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Profil · ${protocolShortPrefix(account.protocol)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!loaded) {
                    Text("Chargement…", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Nom affiché") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = bio,
                    onValueChange = { bio = it },
                    label = { Text("Bio") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                )
                if (account.protocol == ProtocolId.TELEGRAM) {
                    TextButton(onClick = { photoPicker.launch("image/*") }) {
                        Text("Changer la photo de profil")
                    }
                }
                status?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    viewModel.updateAccountProfile(
                        accountId = account.id,
                        protocol = account.protocol,
                        displayName = displayName.trim(),
                        bio = bio.trim().ifBlank { null },
                    ) { result ->
                        result.fold(
                            onSuccess = { onDismiss() },
                            onFailure = { status = it.message ?: "Mise à jour échouée" },
                        )
                    }
                },
                enabled = displayName.isNotBlank(),
            ) {
                Text("Enregistrer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}

@Composable
private fun ProtocolCapabilityRow(
    protocol: ProtocolId,
    viewModel: MainViewModel?,
) {
    val caps = viewModel?.capabilitiesFor(protocol)
    val canRegister = viewModel?.canRegister(protocol) == true
    val features = capabilityLabels(caps, canRegister)
    ListItem(
        leadingContent = { ProtocolAvatar(protocol = protocol, size = 36.dp) },
        headlineContent = { Text(protocolDisplayName(protocol)) },
        supportingContent = {
            CapabilityChipRow(
                labels = features,
                modifier = Modifier.padding(top = 4.dp),
            )
        },
    )
}
