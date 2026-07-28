package ltechnologies.onionphone.securemessenger.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import ltechnologies.onionphone.securemessenger.core.model.Attachment
import ltechnologies.onionphone.securemessenger.core.model.AttachmentState
import ltechnologies.onionphone.securemessenger.core.model.DeliveryState
import ltechnologies.onionphone.securemessenger.core.model.HistoryLoadResult
import ltechnologies.onionphone.securemessenger.core.model.Message
import ltechnologies.onionphone.securemessenger.core.model.MessageDirection
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.ui.MainViewModel
import ltechnologies.onionphone.securemessenger.ui.components.protocolShortPrefix

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    title: String,
    conversationId: String,
    protocol: ProtocolId,
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val capabilities = remember(protocol) { viewModel.capabilitiesFor(protocol) }
    val messagesFlow = remember(conversationId) { viewModel.messagesFor(conversationId) }
    val messages by messagesFlow.collectAsState()
    var draft by remember { mutableStateOf("") }
    var sendError by remember { mutableStateOf<String?>(null) }
    var loadingHistory by remember(conversationId) { mutableStateOf(true) }
    var historyError by remember(conversationId) { mutableStateOf<String?>(null) }
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val listState = rememberLazyListState()

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

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    DisposableEffect(conversationId, protocol) {
        onDispose {
            viewModel.closeConversation(conversationId, protocol)
        }
    }

    val context = LocalContext.current
    val pickAttachment = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (!capabilities.mediaSend) {
            sendError = "Médias non supportés pour ce protocole"
            return@rememberLauncherForActivityResult
        }
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val fileName = uri.lastPathSegment ?: "attachment"
        val dest = File(context.cacheDir, "out_${UUID.randomUUID()}_$fileName")
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }.onSuccess {
            val attachment = Attachment(
                id = UUID.randomUUID().toString(),
                mimeType = mime,
                fileName = fileName,
                localPath = dest.absolutePath,
                sizeBytes = dest.length(),
                state = AttachmentState.READY,
            )
            viewModel.sendMedia(conversationId, protocol, attachment, draft.takeIf { it.isNotBlank() }) { ok ->
                if (ok) {
                    draft = ""
                    sendError = null
                } else {
                    sendError = "Envoi média échoué"
                }
            }
        }.onFailure {
            sendError = it.message ?: "Lecture du fichier impossible"
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                protocolShortPrefix(protocol),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (capabilities.endToEndEncryption) {
                                AssistChip(
                                    onClick = {},
                                    enabled = false,
                                    label = { Text("E2EE") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Lock,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                )
                            }
                            if (capabilities.readReceipts) {
                                Text(
                                    "accusés de lecture",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                sendError?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    if (capabilities.mediaSend) {
                        IconButton(onClick = { pickAttachment.launch("image/*") }) {
                            Icon(Icons.Default.Image, contentDescription = "Joindre une image")
                        }
                        IconButton(onClick = { pickAttachment.launch("*/*") }) {
                            Icon(Icons.Default.AttachFile, contentDescription = "Joindre un fichier")
                        }
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message") },
                        shape = MaterialTheme.shapes.large,
                        maxLines = 4,
                    )
                    FilledIconButton(
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
                        enabled = draft.isNotBlank(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Envoyer")
                    }
                }
            }
        },
    ) { padding ->
        when {
            loadingHistory && messages.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Chargement des messages…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (messages.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                text = historyError ?: "Aucun message dans cette conversation.",
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
                        MessageBubble(
                            message = message,
                            timeFormat = timeFormat,
                            showReadReceipts = capabilities.readReceipts,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: Message,
    timeFormat: DateFormat,
    showReadReceipts: Boolean,
) {
    val outgoing = message.direction == MessageDirection.OUTGOING
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
    ) {
        val sender = message.senderDisplayName
        if (!outgoing && sender != null) {
            Text(
                text = sender,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Surface(
            color = if (outgoing) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (outgoing) 16.dp else 4.dp,
                bottomEnd = if (outgoing) 4.dp else 16.dp,
            ),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                message.attachments.forEach { attachment ->
                    AttachmentContent(attachment)
                }
                if (message.body.isNotBlank()) {
                    Text(
                        text = message.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (outgoing) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = timeFormat.format(Date(message.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (outgoing) {
                        DeliveryIcon(
                            state = message.deliveryState,
                            distinguishRead = showReadReceipts,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentContent(attachment: Attachment) {
    when (attachment.state) {
        AttachmentState.PENDING, AttachmentState.DOWNLOADING -> {
            Text(
                text = "Média en cours… (${attachment.fileName ?: attachment.mimeType})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        AttachmentState.FAILED -> {
            Text(
                text = "Média indisponible",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        AttachmentState.READY -> {
            when {
                attachment.mimeType.startsWith("image/") && attachment.localPath != null -> {
                    AsyncImage(
                        model = File(attachment.localPath!!),
                        contentDescription = attachment.fileName,
                        modifier = Modifier
                            .padding(bottom = 4.dp)
                            .fillMaxWidth()
                            .height(180.dp),
                        contentScale = ContentScale.Crop,
                    )
                }
                else -> {
                    Text(
                        text = attachment.fileName ?: attachment.mimeType,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DeliveryIcon(state: DeliveryState, distinguishRead: Boolean) {
    val (icon, tint) = when (state) {
        DeliveryState.PENDING -> Icons.Default.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
        DeliveryState.SENT -> Icons.Default.Done to MaterialTheme.colorScheme.onSurfaceVariant
        DeliveryState.DELIVERED -> Icons.Default.DoneAll to MaterialTheme.colorScheme.onSurfaceVariant
        DeliveryState.READ -> Icons.Default.DoneAll to if (distinguishRead) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        DeliveryState.FAILED -> Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
    }
    Icon(
        imageVector = icon,
        contentDescription = state.name,
        modifier = Modifier.size(14.dp),
        tint = tint,
    )
}
