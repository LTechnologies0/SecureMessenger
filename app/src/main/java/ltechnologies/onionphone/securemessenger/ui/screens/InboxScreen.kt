package ltechnologies.onionphone.securemessenger.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import ltechnologies.onionphone.securemessenger.core.model.Conversation
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.ui.MainViewModel
import ltechnologies.onionphone.securemessenger.ui.components.ProtocolAccentChip
import ltechnologies.onionphone.securemessenger.ui.components.ProtocolAvatar
import ltechnologies.onionphone.securemessenger.ui.components.inboxEmptySubtitle
import ltechnologies.onionphone.securemessenger.ui.components.newChatFabLabel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun InboxScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel,
    accountId: String? = null,
    inboxProtocol: ProtocolId? = null,
    onConversationClick: (conversationId: String, title: String, protocol: ProtocolId) -> Unit,
    onNewChat: () -> Unit,
    embedded: Boolean = false,
) {
    val allConversations by viewModel.conversations.collectAsState()
    val conversations = if (accountId != null) {
        allConversations.filter { it.accountId == accountId }
    } else {
        allConversations
    }
    val timeFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val relativeTime = remember {
        DateFormat.getTimeInstance(DateFormat.SHORT)
    }
    var query by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var showTelegramArchive by rememberSaveable { mutableStateOf(false) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var listMenuConv by remember { mutableStateOf<Conversation?>(null) }

    val filtered = remember(conversations, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            conversations
        } else {
            conversations.filter { conv ->
                conv.title.contains(q, ignoreCase = true) ||
                    (conv.lastMessagePreview?.contains(q, ignoreCase = true) == true) ||
                    conv.remoteId.contains(q, ignoreCase = true)
            }
        }
    }

    val displayList = remember(filtered, inboxProtocol, showTelegramArchive, showArchived) {
        filtered
            .filter { conv ->
                val showArchive = if (inboxProtocol == ProtocolId.TELEGRAM) {
                    showTelegramArchive
                } else {
                    showArchived
                }
                showArchive || !conv.isArchived
            }
            .sortedWith(
                compareByDescending<Conversation> { it.isPinned }
                    .thenByDescending { it.lastMessageAt },
            )
    }

    val listContent: @Composable (Modifier) -> Unit = { contentModifier ->
        Column(modifier = contentModifier.fillMaxSize()) {
            if (inboxProtocol == ProtocolId.EMAIL) {
                val sentNote = if (viewModel.emailImapSentFolderConfigured(accountId)) {
                    " INBOX + dossier Sent (~50 msgs) synchronisés quand détecté."
                } else {
                    " Pas de dossier Sent IMAP détecté pour l’instant."
                }
                Text(
                    "Messages récents (IMAP/JMAP) — pas une archive complète.$sentNote",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            if (inboxProtocol == ProtocolId.TELEGRAM) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (showTelegramArchive) "Archives Telegram" else "Chats Telegram",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { showTelegramArchive = !showTelegramArchive }) {
                        Text(if (showTelegramArchive) "Retour aux chats" else "Voir archives")
                    }
                }
            } else if (inboxProtocol != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (showArchived) "Archives locales" else "Boîte",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { showArchived = !showArchived }) {
                        Text(if (showArchived) "Retour" else "Voir archives")
                    }
                }
            }
            if (inboxProtocol == ProtocolId.IRC) {
                Text(
                    "Canaux rejoints et DMs — pas d’historique serveur avant connexion.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            SearchBar(
                inputField = {
                    SearchBarDefaults.InputField(
                        query = query,
                        onQueryChange = { query = it },
                        onSearch = { searchExpanded = false },
                        expanded = searchExpanded,
                        onExpandedChange = { searchExpanded = it },
                        placeholder = { Text("Rechercher une conversation") },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null)
                        },
                    )
                },
                expanded = searchExpanded,
                onExpandedChange = { searchExpanded = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                if (query.isNotBlank() && filtered.isEmpty()) {
                    Text(
                        "Aucun résultat",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn {
                        items(filtered, key = { it.id }) { conv ->
                            ConversationRow(
                                conv = conv,
                                timeLabel = formatInboxTime(conv, timeFormat, relativeTime),
                                onClick = {
                                    searchExpanded = false
                                    onConversationClick(conv.id, conv.title, conv.protocol)
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }

            if (filtered.isEmpty() && !searchExpanded) {
                InboxEmptyState(
                    query = query,
                    protocol = inboxProtocol,
                    onNewChat = onNewChat,
                    onClearQuery = { query = "" },
                )
            } else if (!searchExpanded) {
                LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(displayList, key = { it.id }) { conv ->
                        ConversationRow(
                            conv = conv,
                            timeLabel = formatInboxTime(conv, timeFormat, relativeTime),
                            onClick = {
                                onConversationClick(conv.id, conv.title, conv.protocol)
                            },
                            onLongClick = { listMenuConv = conv },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 72.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }
    }

    listMenuConv?.let { conv ->
        AlertDialog(
            onDismissRequest = { listMenuConv = null },
            title = { Text(conv.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        viewModel.setConversationListFlags(
                            conv.id,
                            conv.protocol,
                            pinned = !conv.isPinned,
                        ) { listMenuConv = null }
                    }) {
                        Icon(Icons.Default.PushPin, contentDescription = null)
                        Text(if (conv.isPinned) "Désépingler" else "Épingler")
                    }
                    TextButton(onClick = {
                        val mute = if (conv.isMuted) 0 else 365 * 24 * 3600
                        viewModel.setConversationListFlags(
                            conv.id,
                            conv.protocol,
                            mutedSeconds = mute,
                        ) { listMenuConv = null }
                    }) {
                        Icon(Icons.Default.NotificationsOff, contentDescription = null)
                        Text(if (conv.isMuted) "Activer notifications" else "Couper notifications (1 an)")
                    }
                    TextButton(onClick = {
                        viewModel.setConversationListFlags(
                            conv.id,
                            conv.protocol,
                            archived = !conv.isArchived,
                        ) { listMenuConv = null }
                    }) {
                        Icon(Icons.Default.Archive, contentDescription = null)
                        Text(if (conv.isArchived) "Désarchiver" else "Archiver")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { listMenuConv = null }) { Text("Fermer") }
            },
        )
    }

    if (embedded) {
        Box(modifier = modifier.fillMaxSize()) {
            listContent(Modifier.fillMaxSize())
            FloatingActionButton(
                onClick = onNewChat,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = newChatFabLabel(inboxProtocol))
            }
        }
    } else {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = { TopAppBar(title = { Text("Boîte de réception") }) },
            floatingActionButton = {
                FloatingActionButton(onClick = onNewChat) {
                    Icon(Icons.Default.Add, contentDescription = "Nouveau chat")
                }
            },
        ) { padding ->
            listContent(Modifier.padding(padding))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conv: Conversation,
    timeLabel: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val unread = conv.unreadCount > 0
    ListItem(
        headlineContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    conv.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (timeLabel.isNotEmpty()) {
                    Text(
                        timeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (unread) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = conv.lastMessagePreview ?: "Pas encore de message",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (unread) FontWeight.Medium else FontWeight.Normal,
                )
                ProtocolAccentChip(protocol = conv.protocol)
            }
        },
        leadingContent = {
            BadgedBox(
                badge = {
                    if (unread) {
                        Badge { Text(conv.unreadCount.coerceAtMost(99).toString()) }
                    }
                },
            ) {
                ProtocolAvatar(protocol = conv.protocol, size = 48.dp)
            }
        },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (conv.isPinned) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = "Épinglé",
                        modifier = Modifier.padding(end = 2.dp),
                    )
                }
                if (conv.isMuted) {
                    Icon(Icons.Default.NotificationsOff, contentDescription = "Silencieux")
                }
            }
        },
        modifier = Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics {
                contentDescription = "${conv.protocol} conversation ${conv.title}"
            },
    )
}

@Composable
private fun InboxEmptyState(
    query: String,
    protocol: ProtocolId?,
    onNewChat: () -> Unit,
    onClearQuery: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.ChatBubbleOutline,
            contentDescription = null,
            modifier = Modifier.padding(bottom = 16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (query.isBlank()) {
                "Aucune conversation"
            } else {
                "Aucun résultat pour « $query »"
            },
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = if (query.isBlank()) {
                inboxEmptySubtitle(protocol)
            } else {
                "Essaie un autre terme de recherche."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        if (query.isBlank()) {
            Button(onClick = onNewChat) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(newChatFabLabel(protocol))
            }
        } else {
            TextButton(onClick = onClearQuery) { Text("Effacer la recherche") }
        }
    }
}

private fun formatInboxTime(
    conv: Conversation,
    dateTime: DateFormat,
    timeOnly: DateFormat,
): String {
    if (conv.lastMessageAt <= 0) return ""
    val now = System.currentTimeMillis()
    val dayMs = 24 * 60 * 60 * 1000L
    return if (now - conv.lastMessageAt < dayMs) {
        timeOnly.format(Date(conv.lastMessageAt))
    } else {
        dateTime.format(Date(conv.lastMessageAt))
    }
}
