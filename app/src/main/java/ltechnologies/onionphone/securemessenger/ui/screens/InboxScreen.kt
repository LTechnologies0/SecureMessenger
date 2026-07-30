package ltechnologies.onionphone.securemessenger.ui.screens

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
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Search
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InboxScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel,
    accountId: String? = null,
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

    val listContent: @Composable (Modifier) -> Unit = { contentModifier ->
        Column(modifier = contentModifier.fillMaxSize()) {
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
                    onNewChat = onNewChat,
                    onClearQuery = { query = "" },
                )
            } else if (!searchExpanded) {
                LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(filtered, key = { it.id }) { conv ->
                        ConversationRow(
                            conv = conv,
                            timeLabel = formatInboxTime(conv, timeFormat, relativeTime),
                            onClick = {
                                onConversationClick(conv.id, conv.title, conv.protocol)
                            },
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

    if (embedded) {
        Box(modifier = modifier.fillMaxSize()) {
            listContent(Modifier.fillMaxSize())
            FloatingActionButton(
                onClick = onNewChat,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Nouveau chat")
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

@Composable
private fun ConversationRow(
    conv: Conversation,
    timeLabel: String,
    onClick: () -> Unit,
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
            if (unread) {
                Badge { Text(conv.unreadCount.coerceAtMost(99).toString()) }
            }
        },
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "${conv.protocol} conversation ${conv.title}"
            },
    )
}

@Composable
private fun InboxEmptyState(
    query: String,
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
                "Lance un nouveau chat pour démarrer."
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
                Text("Nouveau chat")
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
