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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
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
import ltechnologies.onionphone.securemessenger.ui.components.connectionStateLabel
import ltechnologies.onionphone.securemessenger.ui.components.protocolAccentColor
import ltechnologies.onionphone.securemessenger.ui.components.protocolIcon
import ltechnologies.onionphone.securemessenger.ui.components.protocolShortPrefix

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
    ADD_SIGNAL,
    ADD_PROTOCOL,
    SETTINGS,
    PROXY,
    NEW_CHAT,
    CONTACTS,
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MessengerShellScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel,
) {
    val accounts by viewModel.accounts.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
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
    val unreadForSelected = conversations
        .filter { it.accountId == selectedAccountId }
        .sumOf { it.unreadCount }

    when (overlay) {
        ShellOverlay.ADD_ACCOUNT_PICKER -> {
            Scaffold { padding ->
                AddAccountScreen(
                    modifier = Modifier.padding(padding),
                    onClose = { overlay = ShellOverlay.NONE },
                    onPickTelegram = { overlay = ShellOverlay.ADD_TELEGRAM },
                    onPickSignal = { overlay = ShellOverlay.ADD_SIGNAL },
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
        ShellOverlay.ADD_SIGNAL -> {
            Scaffold { padding ->
                SignalLoginScreen(
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
                    viewModel = viewModel,
                    onOpenProxy = { overlay = ShellOverlay.PROXY },
                    onClose = { overlay = ShellOverlay.NONE },
                    onDisconnectAccount = { accountId ->
                        viewModel.disconnectAccount(accountId)
                    },
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
        ShellOverlay.CONTACTS -> {
            val account = selectedAccount
            if (account != null) {
                Scaffold { padding ->
                    ContactsScreen(
                        modifier = Modifier.padding(padding),
                        viewModel = viewModel,
                        accountId = account.id,
                        protocol = account.protocol,
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
        }
        ShellOverlay.NONE -> Unit
    }

    Row(modifier = modifier.fillMaxSize()) {
        AccountNavigationRail(
            accounts = accounts,
            selectedAccountId = selectedAccountId,
            protocolIndices = protocolIndices,
            contactsSelected = false,
            settingsSelected = false,
            unreadCount = unreadForSelected,
            onSelectAccount = {
                selectedAccountId = it
                openConversationId = null
            },
            onAddAccount = { overlay = ShellOverlay.ADD_ACCOUNT_PICKER },
            onOpenContacts = { overlay = ShellOverlay.CONTACTS },
            onOpenSettings = { overlay = ShellOverlay.SETTINGS },
            contactsEnabled = selectedAccount?.let {
                viewModel.capabilitiesFor(it.protocol).contacts
            } == true,
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
            onOpenContacts = { overlay = ShellOverlay.CONTACTS },
            onAddAccount = { overlay = ShellOverlay.ADD_ACCOUNT_PICKER },
        )
    }
}

@Composable
private fun AccountNavigationRail(
    accounts: List<Account>,
    selectedAccountId: String?,
    protocolIndices: Map<String, Int>,
    contactsSelected: Boolean,
    settingsSelected: Boolean,
    unreadCount: Int,
    onSelectAccount: (String) -> Unit,
    onAddAccount: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenSettings: () -> Unit,
    contactsEnabled: Boolean,
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
                    BadgedBox(
                        badge = {
                            if (selected && unreadCount > 0) {
                                Badge { Text(unreadCount.coerceAtMost(99).toString()) }
                            }
                        },
                    ) {
                        AccountRailAvatar(
                            account = account,
                            accent = accent,
                            selected = selected,
                        )
                    }
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
            label = { Text("Ajouter", style = MaterialTheme.typography.labelSmall) },
        )
        if (contactsEnabled) {
            NavigationRailItem(
                selected = contactsSelected,
                onClick = onOpenContacts,
                icon = {
                    Icon(Icons.Default.Contacts, contentDescription = "Contacts")
                },
                label = { Text("Contacts", style = MaterialTheme.typography.labelSmall) },
            )
        }
        NavigationRailItem(
            selected = settingsSelected,
            onClick = onOpenSettings,
            icon = {
                Icon(Icons.Default.Settings, contentDescription = "Paramètres")
            },
            label = { Text("Paramètres", style = MaterialTheme.typography.labelSmall) },
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
            Icon(
                imageVector = protocolIcon(account.protocol),
                contentDescription = protocolShortPrefix(account.protocol),
                tint = accent,
                modifier = Modifier.size(24.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(connectionIndicatorColor(account.connectionState))
                .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape),
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
    onOpenContacts: () -> Unit,
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

    val caps = viewModel.capabilitiesFor(selectedAccount.protocol)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val accent = protocolAccentColor(selectedAccount.protocol)
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
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = buildString {
                                    append(selectedAccountLabel ?: protocolShortPrefix(selectedAccount.protocol))
                                    append(" · ")
                                    append(connectionStateLabel(selectedAccount.connectionState))
                                    if (caps.endToEndEncryption) append(" · E2EE")
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = accent,
                            )
                        }
                    }
                },
                actions = {
                    if (caps.contacts) {
                        IconButton(onClick = onOpenContacts) {
                            Icon(Icons.Default.Contacts, contentDescription = "Contacts")
                        }
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
        FilledTonalButton(onClick = onAddAccount) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text("Ajouter un compte")
        }
    }
}
