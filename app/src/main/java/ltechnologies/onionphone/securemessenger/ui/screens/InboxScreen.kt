package ltechnologies.onionphone.securemessenger.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.ui.MainViewModel

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    val timeFormat = androidx.compose.runtime.remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    val listContent: @Composable (Modifier) -> Unit = { contentModifier ->
        Column(modifier = contentModifier.fillMaxSize()) {
            if (killswitch) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Text(
                        text = "Killswitch active: Tor required but proxy unavailable. Connections blocked.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (conversations.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "Aucune conversation. Lance un nouveau chat.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                    itemsIndexed(conversations) { _, conv ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { onConversationClick(conv.id, conv.title, conv.protocol) }
                                .semantics { contentDescription = "${conv.protocol} conversation ${conv.title}" },
                            shape = MaterialTheme.shapes.largeIncreased,
                        ) {
                            ListItem(
                                headlineContent = {
                                    Text(conv.title, style = MaterialTheme.typography.titleMedium)
                                },
                                supportingContent = {
                                    val preview = conv.lastMessagePreview ?: conv.protocol.name
                                    val ts = if (conv.lastMessageAt > 0) {
                                        " · ${timeFormat.format(Date(conv.lastMessageAt))}"
                                    } else {
                                        ""
                                    }
                                    Text("$preview$ts")
                                },
                                trailingContent = {
                                    if (conv.unreadCount > 0) {
                                        Badge { Text(conv.unreadCount.toString()) }
                                    }
                                },
                            )
                        }
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
                    .align(androidx.compose.ui.Alignment.BottomEnd)
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
