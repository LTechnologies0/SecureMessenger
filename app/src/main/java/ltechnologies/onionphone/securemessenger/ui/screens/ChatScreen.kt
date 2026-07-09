package ltechnologies.onionphone.securemessenger.ui.screens

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import ltechnologies.onionphone.securemessenger.core.model.DeliveryState
import ltechnologies.onionphone.securemessenger.core.model.HistoryLoadResult
import ltechnologies.onionphone.securemessenger.core.model.MessageDirection
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.ui.MainViewModel

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    title: String,
    conversationId: String,
    protocol: ProtocolId,
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val messagesFlow = remember(conversationId) { viewModel.messagesFor(conversationId) }
    val messages by messagesFlow.collectAsState()
    var draft by remember { mutableStateOf("") }
    var sendError by remember { mutableStateOf<String?>(null) }
    var loadingHistory by remember(conversationId) { mutableStateOf(true) }
    var historyError by remember(conversationId) { mutableStateOf<String?>(null) }
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

    LaunchedEffect(conversationId, protocol) {
        loadingHistory = true
        historyError = null
        when (val result = viewModel.loadMessageHistory(conversationId, protocol)) {
            is HistoryLoadResult.Failure -> historyError = result.reason
            is HistoryLoadResult.Success -> {
                if (result.messageCount == 0) {
                    historyError = "Aucun message trouvé dans cette conversation."
                }
            }
        }
        loadingHistory = false
    }

    DisposableEffect(conversationId, protocol) {
        onDispose {
            viewModel.closeConversation(conversationId, protocol)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(title)
                    Text(protocol.name, style = MaterialTheme.typography.labelSmall)
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
        if (loadingHistory && messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Chargement des messages…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (messages.isEmpty() && !loadingHistory) {
                item(key = "empty") {
                    Text(
                        text = historyError
                            ?: "Aucun message dans cette conversation.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (historyError != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(messages, key = { it.id }) { message ->
                val outgoing = message.direction == MessageDirection.OUTGOING
                val colors = if (outgoing) {
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    )
                } else {
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = if (outgoing) {
                        androidx.compose.ui.Alignment.End
                    } else {
                        androidx.compose.ui.Alignment.Start
                    },
                ) {
                    val sender = message.senderDisplayName
                    if (!outgoing && sender != null) {
                        Text(
                            text = sender,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                    Card(colors = colors) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(text = message.body)
                            Text(
                                text = buildString {
                                    append(timeFormat.format(Date(message.timestamp)))
                                    if (outgoing) {
                                        append(" · ")
                                        append(
                                            when (message.deliveryState) {
                                                DeliveryState.PENDING -> "…"
                                                DeliveryState.SENT -> "✓"
                                                DeliveryState.DELIVERED -> "✓✓"
                                                DeliveryState.READ -> "✓✓"
                                                DeliveryState.FAILED -> "!"
                                            },
                                        )
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        }
        sendError?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
            )
            Button(
                onClick = {
                    if (draft.isNotBlank()) {
                        viewModel.sendMessage(conversationId, protocol, draft) { ok ->
                            if (ok) {
                                draft = ""
                                sendError = null
                            } else {
                                sendError = "Envoi échoué"
                            }
                        }
                    }
                },
            ) {
                Text("Send")
            }
        }
    }
}
