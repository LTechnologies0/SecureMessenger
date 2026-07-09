package ltechnologies.onionphone.securemessenger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ltechnologies.onionphone.securemessenger.core.model.Account
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.ui.MainViewModel
import ltechnologies.onionphone.securemessenger.ui.components.accountRailLabel
import ltechnologies.onionphone.securemessenger.ui.components.connectionIndicatorColor
import ltechnologies.onionphone.securemessenger.ui.components.protocolAccentColor

private fun rememberAccountProtocolIndices(accounts: List<Account>): Map<String, Int> {
    val counts = mutableMapOf<ProtocolId, Int>()
    return accounts.associate { account ->
        val index = counts.getOrDefault(account.protocol, 0)
        counts[account.protocol] = index + 1
        account.id to index
    }
}

private enum class ShellOverlay {
    NONE,
    ADD_ACCOUNT_PICKER,
    ADD_TELEGRAM,
    ADD_PROTOCOL,
    SETTINGS,
    PROXY,
    NEW_CHAT,
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MessengerShellScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel,
) {
    val accounts by viewModel.accounts.collectAsState()
    var selectedAccountId by rememberSaveable { mutableStateOf<String?>(null) }
    var openConversationId by rememberSaveable { mutableStateOf<String?>(null) }
    var openConversationTitle by rememberSaveable { mutableStateOf("") }
    var openConversationProtocol by rememberSaveable { mutableStateOf(ProtocolId.XMPP.name) }
    var overlay by rememberSaveable { mutableStateOf(ShellOverlay.NONE) }
    var addProtocol by rememberSaveable { mutableStateOf(ProtocolId.XMPP.name) }

    LaunchedEffect(accounts) {
        if (selectedAccountId == null && accounts.isNotEmpty()) {
            selectedAccountId = accounts.first().id
        }
        if (selectedAccountId != null && accounts.none { it.id == selectedAccountId }) {
            selectedAccountId = accounts.firstOrNull()?.id
            openConversationId = null
        }
    }

    val selectedAccount = accounts.firstOrNull { it.id == selectedAccountId }
    val protocolIndices = rememberAccountProtocolIndices(accounts)

    when (overlay) {
        ShellOverlay.ADD_ACCOUNT_PICKER -> {
            Scaffold { padding ->
                AddAccountScreen(
                    modifier = Modifier.padding(padding),
                    onClose = { overlay = ShellOverlay.NONE },
                    onPickTelegram = { overlay = ShellOverlay.ADD_TELEGRAM },
                    onPickProtocol = { protocol ->
                        addProtocol = protocol.name
                        overlay = ShellOverlay.ADD_PROTOCOL
                    },
                )
            }
            return
        }
        ShellOverlay.ADD_TELEGRAM -> {
            Scaffold { padding ->
                TelegramLoginScreen(
                    modifier = Modifier.padding(padding),
                    viewModel = viewModel,
                    onClose = { overlay = ShellOverlay.ADD_ACCOUNT_PICKER },
                    onConnected = { accountId ->
                        selectedAccountId = accountId
                        overlay = ShellOverlay.NONE
                    },
                )
            }
            return
        }
        ShellOverlay.ADD_PROTOCOL -> {
            Scaffold { padding ->
                AccountsScreen(
                    modifier = Modifier.padding(padding),
                    viewModel = viewModel,
                    initialProtocol = ProtocolId.valueOf(addProtocol),
                    onClose = { overlay = ShellOverlay.ADD_ACCOUNT_PICKER },
                )
            }
            return
        }
        ShellOverlay.SETTINGS -> {
            Scaffold { padding ->
                SettingsScreen(
                    modifier = Modifier.padding(padding),
                    onOpenProxy = { overlay = ShellOverlay.PROXY },
                    onClose = { overlay = ShellOverlay.NONE },
                )
            }
            return
        }
        ShellOverlay.PROXY -> {
            Scaffold { padding ->
                ProxyScreen(
                    modifier = Modifier.padding(padding),
                    viewModel = viewModel,
                    onClose = { overlay = ShellOverlay.SETTINGS },
                )
            }
            return
        }
        ShellOverlay.NEW_CHAT -> {
            Scaffold { padding ->
                NewChatScreen(
                    modifier = Modifier.padding(padding),
                    viewModel = viewModel,
                    accountId = selectedAccountId,
                    onBack = { overlay = ShellOverlay.NONE },
                    onStarted = { convId, title, protocol ->
                        overlay = ShellOverlay.NONE
                        openConversationId = convId
                        openConversationTitle = title
                        openConversationProtocol = protocol.name
                    },
                )
            }
            return
        }
        ShellOverlay.NONE -> Unit
    }

    Row(modifier = modifier.fillMaxSize()) {
        AccountNavigationRail(
            accounts = accounts,
            selectedAccountId = selectedAccountId,
            protocolIndices = protocolIndices,
            onSelectAccount = {
                selectedAccountId = it
                openConversationId = null
            },
            onAddAccount = { overlay = ShellOverlay.ADD_ACCOUNT_PICKER },
            onOpenSettings = { overlay = ShellOverlay.SETTINGS },
        )

        VerticalDivider()

        MainContentPane(
            modifier = Modifier.weight(1f),
            viewModel = viewModel,
            selectedAccount = selectedAccount,
            selectedAccountLabel = selectedAccount?.let { acc ->
                accountRailLabel(acc, protocolIndices[acc.id] ?: 0)
            },
            openConversationId = openConversationId,
            openConversationTitle = openConversationTitle,
            openConversationProtocol = openConversationProtocol,
            onConversationClick = { convId, title, protocol ->
                openConversationId = convId
                openConversationTitle = title
                openConversationProtocol = protocol.name
            },
            onBackFromChat = { openConversationId = null },
            onNewChat = { overlay = ShellOverlay.NEW_CHAT },
            onAddAccount = { overlay = ShellOverlay.ADD_ACCOUNT_PICKER },
        )
    }
}

@Composable
private fun AccountNavigationRail(
    accounts: List<Account>,
    selectedAccountId: String?,
    protocolIndices: Map<String, Int>,
    onSelectAccount: (String) -> Unit,
    onAddAccount: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    NavigationRail(modifier = Modifier.fillMaxHeight()) {
        Spacer(Modifier.height(12.dp))
        accounts.forEach { account ->
            val label = accountRailLabel(account, protocolIndices[account.id] ?: 0)
            val accent = protocolAccentColor(account.protocol)
            val selected = account.id == selectedAccountId
            NavigationRailItem(
                selected = selected,
                onClick = { onSelectAccount(account.id) },
                icon = {
                    AccountRailAvatar(
                        account = account,
                        accent = accent,
                        selected = selected,
                    )
                },
                label = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                },
            )
        }
        Spacer(Modifier.weight(1f))
        NavigationRailItem(
            selected = false,
            onClick = onAddAccount,
            icon = {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Add, contentDescription = "Ajouter compte")
                    }
                }
            },
            label = { Text("+", style = MaterialTheme.typography.labelSmall) },
        )
        NavigationRailItem(
            selected = false,
            onClick = onOpenSettings,
            icon = {
                Icon(Icons.Default.Settings, contentDescription = "Paramètres")
            },
            label = { Text("Settings", style = MaterialTheme.typography.labelSmall) },
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AccountRailAvatar(
    account: Account,
    accent: Color,
    selected: Boolean,
) {
    val initial = account.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val borderWidth = if (selected) 3.dp else 1.5.dp
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else accent
    Box(contentAlignment = Alignment.BottomEnd) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(accent.copy(alpha = 0.22f))
                .border(borderWidth, borderColor, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
        }
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(connectionIndicatorColor(account.connectionState))
                .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MainContentPane(
    modifier: Modifier,
    viewModel: MainViewModel,
    selectedAccount: Account?,
    selectedAccountLabel: String?,
    openConversationId: String?,
    openConversationTitle: String,
    openConversationProtocol: String,
    onConversationClick: (String, String, ProtocolId) -> Unit,
    onBackFromChat: () -> Unit,
    onNewChat: () -> Unit,
    onAddAccount: () -> Unit,
) {
    openConversationId?.let { convId ->
        ChatScreen(
            modifier = modifier,
            title = openConversationTitle,
            conversationId = convId,
            protocol = ProtocolId.valueOf(openConversationProtocol),
            viewModel = viewModel,
            onBack = onBackFromChat,
        )
        return
    }

    if (selectedAccount == null) {
        EmptyShellPlaceholder(
            modifier = modifier,
            onAddAccount = onAddAccount,
        )
        return
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Column {
                        Text(
                            text = selectedAccount.displayName,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = selectedAccountLabel ?: selectedAccount.displayName,
                            style = MaterialTheme.typography.labelLarge,
                            color = protocolAccentColor(selectedAccount.protocol),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        InboxScreen(
            modifier = Modifier.padding(padding),
            viewModel = viewModel,
            accountId = selectedAccount.id,
            onConversationClick = onConversationClick,
            onNewChat = onNewChat,
            embedded = true,
        )
    }
}

@Composable
private fun EmptyShellPlaceholder(
    modifier: Modifier,
    onAddAccount: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Sélectionne un profil ou ajoute un compte",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Rail gauche = comptes multi-protocoles",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Surface(
            onClick = onAddAccount,
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Text(
                text = "Ajouter un compte",
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
