package ltechnologies.onionphone.securemessenger.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.ui.MainViewModel
import ltechnologies.onionphone.securemessenger.ui.components.protocolIcon
import ltechnologies.onionphone.securemessenger.ui.components.protocolShortPrefix

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
    val killswitch by viewModel.killswitchActive.collectAsState()
    val timeFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
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
                            ListItem(
                                headlineContent = { Text(conv.title) },
                                supportingContent = {
                                    Text(conv.lastMessagePreview ?: protocolShortPrefix(conv.protocol))
                                },
                                modifier = Modifier.clickable {
                                    searchExpanded = false
                                    onConversationClick(conv.id, conv.title, conv.protocol)
                                },
                            )
                        }
                    }
                }
            }

            if (killswitch) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        leadingContent = {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        },
                        headlineContent = {
                            Text(
                                "Killswitch actif",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        },
                        supportingContent = {
                            Text(
                                "Tor requis mais proxy indisponible — connexions bloquées.",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        },
                    )
                }
            }

            if (filtered.isEmpty() && !searchExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = if (query.isBlank()) {
                            "Aucune conversation. Lance un nouveau chat."
                        } else {
                            "Aucun résultat pour « $query »."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (!searchExpanded) {
                LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(filtered, key = { it.id }) { conv ->
                        ListItem(
                            headlineContent = {
                                Text(conv.title, style = MaterialTheme.typography.titleMedium)
                            },
                            supportingContent = {
                                val preview = conv.lastMessagePreview ?: protocolShortPrefix(conv.protocol)
                                val ts = if (conv.lastMessageAt > 0) {
                                    " · ${timeFormat.format(Date(conv.lastMessageAt))}"
                                } else {
                                    ""
                                }
                                Text("$preview$ts")
                            },
                            leadingContent = {
                                Icon(
                                    protocolIcon(conv.protocol),
                                    contentDescription = conv.protocol.name,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            trailingContent = {
                                if (conv.unreadCount > 0) {
                                    Badge { Text(conv.unreadCount.toString()) }
                                }
                            },
                            modifier = Modifier
                                .clickable {
                                    onConversationClick(conv.id, conv.title, conv.protocol)
                                }
                                .semantics {
                                    contentDescription = "${conv.protocol} conversation ${conv.title}"
                                },
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
            topBar = { TopAppBar(title = { Text("Inbox") }) },
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
