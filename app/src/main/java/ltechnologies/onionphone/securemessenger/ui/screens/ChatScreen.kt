package ltechnologies.onionphone.securemessenger.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ltechnologies.onionphone.securemessenger.core.model.Attachment
import ltechnologies.onionphone.securemessenger.core.model.AttachmentState
import ltechnologies.onionphone.securemessenger.core.model.DeliveryState
import ltechnologies.onionphone.securemessenger.core.model.HistoryLoadResult
import ltechnologies.onionphone.securemessenger.core.model.Message
import ltechnologies.onionphone.securemessenger.core.model.MessageDirection
import ltechnologies.onionphone.securemessenger.core.model.MessageKind
import ltechnologies.onionphone.securemessenger.core.model.OutgoingContent
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.core.security.MessageSanitizer
import ltechnologies.onionphone.securemessenger.ui.MainViewModel
import ltechnologies.onionphone.securemessenger.core.model.ConversationIds
import ltechnologies.onionphone.securemessenger.protocol.telegram.TelegramSticker
import ltechnologies.onionphone.securemessenger.ui.components.chatComposerPlaceholder
import ltechnologies.onionphone.securemessenger.ui.components.conversationKindHint
import ltechnologies.onionphone.securemessenger.ui.components.protocolAccentColor
import ltechnologies.onionphone.securemessenger.ui.components.protocolShortPrefix
import org.json.JSONObject

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalFoundationApi::class,
)
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    title: String,
    conversationId: String,
    protocol: ProtocolId,
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onConversationRemapped: (newConversationId: String) -> Unit = {},
) {
    // Re-read each composition so Matrix E2EE/media and Email JMAP flags stay honest per account.
    val accountId = remember(conversationId) {
        ltechnologies.onionphone.securemessenger.core.model.ConversationIds.accountId(conversationId)
    }
    val capabilities = viewModel.capabilitiesFor(protocol, accountId)
    val messagesFlow = remember(conversationId) { viewModel.messagesFor(conversationId) }
    val messages by messagesFlow.collectAsState()
    val typingUsers by remember(conversationId, protocol, capabilities.typingIndicators) {
        if (capabilities.typingIndicators) {
            viewModel.observeTyping(conversationId, protocol)
        } else {
            MutableStateFlow(emptyList())
        }
    }.collectAsState()
    // Key composer state by conversation — ChatScreen stays mounted across inbox switches.
    var draft by remember(conversationId) { mutableStateOf("") }
    var replyToEventId by remember(conversationId) { mutableStateOf<String?>(null) }
    var emailSubject by remember(conversationId) { mutableStateOf("") }
    var emailCc by remember(conversationId) { mutableStateOf("") }
    var emailBcc by remember(conversationId) { mutableStateOf("") }
    var e2eeDetailText by remember { mutableStateOf<String?>(null) }
    var e2eeDetailError by remember { mutableStateOf<String?>(null) }
    val emailTo = remember(conversationId) {
        ConversationIds.remoteId(conversationId).orEmpty()
    }
    var conversationE2ee by remember(conversationId, protocol) {
        mutableStateOf(capabilities.endToEndEncryption)
    }
    var stickerLoading by remember { mutableStateOf(false) }
    var stickerChoices by remember { mutableStateOf<List<TelegramSticker>>(emptyList()) }
    var actionMessage by remember { mutableStateOf<Message?>(null) }
    var editDraft by remember { mutableStateOf("") }
    val allConversations by viewModel.conversations.collectAsState()
    val signalLinkSync = protocol == ProtocolId.SIGNAL &&
        accountId?.let { viewModel.isSignalLinkAndSyncInProgress(it) } == true
    var sendError by remember(conversationId) { mutableStateOf<String?>(null) }
    var loadingHistory by remember(conversationId) { mutableStateOf(true) }
    var historyError by remember(conversationId) { mutableStateOf<String?>(null) }
    var dialog by remember(conversationId) { mutableStateOf(ComposerDialog.NONE) }
    var showAttachSheet by remember(conversationId) { mutableStateOf(false) }
    var pendingPick by remember(conversationId) { mutableStateOf(ComposerPickKind.IMAGE) }
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val accent = protocolAccentColor(protocol)

    val hasAttachOptions = capabilities.mediaSend || capabilities.gifs || capabilities.voiceNotes ||
        capabilities.stickers ||
        capabilities.locationShare || capabilities.polls || capabilities.contactShare ||
        capabilities.ephemeralMessages

    LaunchedEffect(conversationId, protocol, capabilities.endToEndEncryption) {
        conversationE2ee = when (protocol) {
            ProtocolId.XMPP, ProtocolId.SIGNAL ->
                viewModel.isConversationE2ee(conversationId, protocol)
            ProtocolId.MATRIX -> capabilities.endToEndEncryption
            else -> capabilities.endToEndEncryption
        }
    }

    LaunchedEffect(conversationId, protocol) {
        loadingHistory = true
        historyError = null
        if (!capabilities.messageHistory) {
            // IRC / Signal pre-import: local cache only — skip network "Chargement…"
            loadingHistory = false
            return@LaunchedEffect
        }
        when (val result = viewModel.loadMessageHistory(conversationId, protocol)) {
            is HistoryLoadResult.Failure -> historyError = result.reason
            // Empty inbox is normal (new DM, IRC channel, email draft thread) — not an error.
            is HistoryLoadResult.Success -> Unit
        }
        loadingHistory = false
    }

    LaunchedEffect(messages.size, conversationId, protocol) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
        // Always clear local unread (Email/IRC); read-receipt UI stays gated on capabilities.
        val lastIncoming = messages.lastOrNull { it.direction == MessageDirection.INCOMING }
        viewModel.markRead(conversationId, protocol, lastIncoming?.id)
    }

    LaunchedEffect(draft, conversationId, protocol) {
        if (!capabilities.typingIndicators) return@LaunchedEffect
        if (draft.isBlank()) {
            viewModel.setTyping(conversationId, protocol, false)
            return@LaunchedEffect
        }
        viewModel.setTyping(conversationId, protocol, true)
        delay(2_000)
        viewModel.setTyping(conversationId, protocol, false)
    }

    LaunchedEffect(sendError) {
        val err = sendError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(err)
        sendError = null
    }

    DisposableEffect(conversationId, protocol) {
        onDispose {
            if (capabilities.typingIndicators) {
                viewModel.setTyping(conversationId, protocol, false)
            }
            viewModel.closeConversation(conversationId, protocol)
        }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    suspend fun copyUriToCache(uri: android.net.Uri, prefix: String): Pair<File, String>? =
        withContext(Dispatchers.IO) {
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val fileName = uri.lastPathSegment ?: "attachment"
            val dest = File(context.cacheDir, "${prefix}_${UUID.randomUUID()}_$fileName")
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output ->
                        var copied = 0L
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            copied += n
                            if (copied > MAX_ATTACHMENT_BYTES) {
                                error("Attachment exceeds $MAX_ATTACHMENT_BYTES bytes")
                            }
                            output.write(buffer, 0, n)
                        }
                    }
                } ?: return@runCatching null
                dest to mime
            }.getOrNull()
        }

    fun sendAttachmentResult(
        ok: Boolean,
        failureLabel: String,
        reason: String? = null,
        remappedConversationId: String? = null,
    ) {
        if (ok) {
            draft = ""
            sendError = null
            remappedConversationId?.let(onConversationRemapped)
        } else {
            sendError = reason?.takeIf { it.isNotBlank() } ?: failureLabel
        }
    }

    val pickAttachment = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val kind = pendingPick
        scope.launch {
            val copied = copyUriToCache(uri, "out")
            if (copied == null) {
                sendError = "Lecture du fichier impossible"
                return@launch
            }
            val (dest, mime) = copied
            val attachment = Attachment(
                id = UUID.randomUUID().toString(),
                mimeType = mime,
                fileName = dest.name,
                localPath = dest.absolutePath,
                sizeBytes = dest.length(),
                state = AttachmentState.READY,
            )
            when (kind) {
                ComposerPickKind.VOICE -> {
                    viewModel.sendContent(
                        conversationId,
                        protocol,
                        OutgoingContent.VoiceNote(attachment = attachment),
                    ) { ok, reason, remapped -> sendAttachmentResult(ok, "Envoi vocal échoué", reason, remapped) }
                }
                ComposerPickKind.IMAGE, ComposerPickKind.GIF, ComposerPickKind.FILE -> {
                    val messageKind = when (kind) {
                        ComposerPickKind.IMAGE -> MessageKind.IMAGE
                        ComposerPickKind.GIF -> MessageKind.GIF
                        else -> MessageKind.FILE
                    }
                    val caption = draft.takeIf { it.isNotBlank() }?.let { MessageSanitizer.sanitize(it) }
                    viewModel.sendContent(
                        conversationId,
                        protocol,
                        OutgoingContent.Media(attachment = attachment, caption = caption, kind = messageKind),
                    ) { ok, reason, remapped -> sendAttachmentResult(ok, "Envoi média échoué", reason, remapped) }
                }
            }
        }
    }

    fun launchPick(kind: ComposerPickKind) {
        pendingPick = kind
        val mime = when (kind) {
            ComposerPickKind.IMAGE -> "image/*"
            ComposerPickKind.GIF -> "image/gif"
            ComposerPickKind.VOICE -> "audio/*"
            ComposerPickKind.FILE -> "*/*"
        }
        pickAttachment.launch(mime)
    }

    val typingLabel = when {
        typingUsers.isEmpty() -> null
        typingUsers.size == 1 -> "${typingUsers.first()} écrit…"
        else -> "${typingUsers.joinToString(", ")} écrivent…"
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, style = MaterialTheme.typography.titleLarge)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                protocolShortPrefix(protocol),
                                style = MaterialTheme.typography.labelMedium,
                                color = accent,
                                fontWeight = FontWeight.SemiBold,
                            )
                            conversationKindHint(protocol, emailTo)?.let { hint ->
                                Text(
                                    hint,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            when {
                                conversationE2ee -> {
                                    AssistChip(
                                        onClick = {
                                            when (protocol) {
                                                ProtocolId.SIGNAL -> {
                                                    e2eeDetailText = null
                                                    e2eeDetailError = null
                                                    dialog = ComposerDialog.SAFETY_NUMBER
                                                    viewModel.loadSignalSafetyNumber(
                                                        conversationId,
                                                        accountId,
                                                    ) { num, err ->
                                                        e2eeDetailText = num
                                                        e2eeDetailError = err
                                                    }
                                                }
                                                ProtocolId.XMPP -> {
                                                    e2eeDetailText = null
                                                    e2eeDetailError = null
                                                    dialog = ComposerDialog.SAFETY_NUMBER
                                                    viewModel.loadOmemoFingerprint(
                                                        conversationId,
                                                        accountId,
                                                    ) { num, err ->
                                                        e2eeDetailText = num
                                                        e2eeDetailError = err
                                                    }
                                                }
                                                else -> Unit
                                            }
                                        },
                                        enabled = protocol == ProtocolId.SIGNAL ||
                                            protocol == ProtocolId.XMPP,
                                        label = { Text("E2EE") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Lock,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                            )
                                        },
                                    )
                                }
                                protocol == ProtocolId.XMPP || protocol == ProtocolId.MATRIX ||
                                    protocol == ProtocolId.SIGNAL -> {
                                    AssistChip(
                                        onClick = {},
                                        enabled = false,
                                        label = {
                                            Text(
                                                when (protocol) {
                                                    ProtocolId.XMPP -> "OMEMO indisponible"
                                                    ProtocolId.MATRIX -> "E2EE indisponible"
                                                    else -> "Session chiffrée en attente"
                                                },
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.ErrorOutline,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                            )
                                        },
                                    )
                                }
                            }
                            if (signalLinkSync) {
                                AssistChip(
                                    onClick = {},
                                    enabled = false,
                                    label = { Text("Import appareil…") },
                                )
                            }
                            if (typingLabel != null) {
                                Text(
                                    typingLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
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
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (protocol == ProtocolId.EMAIL) {
                    OutlinedTextField(
                        value = emailTo,
                        onValueChange = { },
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("À") },
                        shape = MaterialTheme.shapes.large,
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = emailCc,
                        onValueChange = { emailCc = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Cc") },
                        placeholder = { Text("copie@example.com, autre@example.org") },
                        shape = MaterialTheme.shapes.large,
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = emailBcc,
                        onValueChange = { emailBcc = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Bcc") },
                        placeholder = { Text("copie-cachée@example.com") },
                        shape = MaterialTheme.shapes.large,
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = emailSubject,
                        onValueChange = { emailSubject = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Objet") },
                        shape = MaterialTheme.shapes.large,
                        singleLine = true,
                    )
                    if (!capabilities.mediaSend) {
                        Text(
                            "Pièces jointes indisponibles sur ce compte (ex. JMAP).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                    Text(
                        if (viewModel.emailImapSentFolderConfigured(accountId)) {
                            "Boîte récente (~100 msgs INBOX + ~50 Sent IMAP synchronisés / 50 JMAP)."
                        } else {
                            "Boîte récente (~100 msgs INBOX / 50 JMAP). Sent IMAP non détecté — envois locaux après envoi app."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    if (hasAttachOptions) {
                        IconButton(onClick = { showAttachSheet = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Joindre")
                        }
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(chatComposerPlaceholder(protocol)) },
                        shape = MaterialTheme.shapes.large,
                        maxLines = 4,
                    )
                    FilledIconButton(
                        onClick = {
                            if (draft.isNotBlank()) {
                                val replyTarget = replyToEventId
                                viewModel.sendContent(
                                    conversationId,
                                    protocol,
                                    OutgoingContent.Text(
                                        body = MessageSanitizer.sanitize(draft),
                                        subject = emailSubject.trim().takeIf { it.isNotEmpty() },
                                        cc = emailCc.trim().takeIf { it.isNotEmpty() },
                                        bcc = emailBcc.trim().takeIf { it.isNotEmpty() },
                                        replyToEventId = replyTarget,
                                    ),
                                ) { ok, reason, remapped ->
                                    if (ok) {
                                        draft = ""
                                        replyToEventId = null
                                        sendError = null
                                        remapped?.let(onConversationRemapped)
                                    } else {
                                        sendError = reason?.takeIf { it.isNotBlank() } ?: "Envoi échoué"
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
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "Chargement des messages…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                            canVotePoll = protocol == ProtocolId.TELEGRAM && capabilities.polls,
                            onVotePoll = { optionIndex ->
                                viewModel.votePoll(
                                    conversationId,
                                    message.id,
                                    intArrayOf(optionIndex),
                                ) { ok ->
                                    if (!ok) sendError = "Vote échoué"
                                }
                            },
                            onLongPress = {
                                actionMessage = message
                                dialog = ComposerDialog.MESSAGE_ACTIONS
                            },
                        )
                    }
                    if (typingLabel != null) {
                        item(key = "typing") {
                            Text(
                                text = typingLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAttachSheet) {
        ComposerAttachSheet(
            capabilities = capabilities,
            onDismiss = { showAttachSheet = false },
            onPickMedia = { kind -> launchPick(kind) },
            onOpenDialog = { dialog = it },
        )
    }

    when (dialog) {
        ComposerDialog.NONE -> Unit
        ComposerDialog.LOCATION -> LocationComposerDialog(
            onDismiss = { dialog = ComposerDialog.NONE },
            onSend = { lat, lon ->
                viewModel.sendContent(
                    conversationId,
                    protocol,
                    OutgoingContent.Location(latitude = lat, longitude = lon),
                ) { ok, reason, remapped ->
                    dialog = ComposerDialog.NONE
                    sendAttachmentResult(ok, "Envoi lieu échoué", reason, remapped)
                }
            },
        )
        ComposerDialog.POLL -> PollComposerDialog(
            onDismiss = { dialog = ComposerDialog.NONE },
            onSend = { question, options ->
                viewModel.sendContent(
                    conversationId,
                    protocol,
                    OutgoingContent.Poll(question = question, options = options),
                ) { ok, reason, remapped ->
                    dialog = ComposerDialog.NONE
                    sendAttachmentResult(ok, "Envoi sondage échoué", reason, remapped)
                }
            },
        )
        ComposerDialog.CONTACT -> ContactComposerDialog(
            onDismiss = { dialog = ComposerDialog.NONE },
            onSend = { first, last, phone ->
                viewModel.sendContent(
                    conversationId,
                    protocol,
                    OutgoingContent.ContactCard(firstName = first, lastName = last, phone = phone),
                ) { ok, reason, remapped ->
                    dialog = ComposerDialog.NONE
                    sendAttachmentResult(ok, "Envoi contact échoué", reason, remapped)
                }
            },
        )
        ComposerDialog.EPHEMERAL -> EphemeralComposerDialog(
            initialBody = draft,
            onDismiss = { dialog = ComposerDialog.NONE },
            onSend = { body, seconds ->
                viewModel.sendContent(
                    conversationId,
                    protocol,
                    OutgoingContent.Ephemeral(
                        body = MessageSanitizer.sanitize(body),
                        expireSeconds = seconds,
                    ),
                ) { ok, reason, remapped ->
                    dialog = ComposerDialog.NONE
                    if (ok) draft = ""
                    sendAttachmentResult(ok, "Envoi éphémère échoué", reason, remapped)
                }
            },
        )
        ComposerDialog.STICKER -> {
            LaunchedEffect(Unit) {
                stickerLoading = true
                viewModel.listTelegramStickers(accountId) { list ->
                    stickerChoices = list
                    stickerLoading = false
                }
            }
            StickerPickerSheet(
                stickers = stickerChoices,
                loading = stickerLoading,
                onDismiss = { dialog = ComposerDialog.NONE },
                onPick = { sticker ->
                    val path = sticker.localPath ?: return@StickerPickerSheet
                    viewModel.sendContent(
                        conversationId,
                        protocol,
                        OutgoingContent.Sticker(localPath = path, emoji = sticker.emoji),
                    ) { ok, reason, remapped ->
                        dialog = ComposerDialog.NONE
                        sendAttachmentResult(ok, "Envoi sticker échoué", reason, remapped)
                    }
                },
            )
        }
        ComposerDialog.MESSAGE_ACTIONS -> {
            val msg = actionMessage
            if (msg != null) {
                AlertDialog(
                    onDismissRequest = {
                        dialog = ComposerDialog.NONE
                        actionMessage = null
                    },
                    title = { Text("Message") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (capabilities.reactions) {
                                Text("Réactions rapides")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("👍", "❤️", "🔥", "👏").forEach { emoji ->
                                        TextButton(onClick = {
                                            viewModel.addReaction(
                                                conversationId,
                                                protocol,
                                                msg.id,
                                                emoji,
                                            ) { ok ->
                                                if (!ok) sendError = "Réaction échouée"
                                            }
                                            dialog = ComposerDialog.NONE
                                            actionMessage = null
                                        }) { Text(emoji) }
                                    }
                                }
                            }
                            if (capabilities.messageReply) {
                                TextButton(
                                    onClick = {
                                        replyToEventId = msg.id
                                        draft = buildString {
                                            if (msg.body.isNotBlank()) {
                                                append("> ")
                                                append(msg.body.lines().joinToString("\n> "))
                                                append("\n\n")
                                            }
                                        } + draft
                                        dialog = ComposerDialog.NONE
                                        actionMessage = null
                                    },
                                ) { Text("Répondre (citation)") }
                            }
                            if (capabilities.messageForward) {
                                TextButton(onClick = {
                                    dialog = ComposerDialog.FORWARD
                                }) { Text("Transférer…") }
                            }
                            if (capabilities.messageEdit &&
                                msg.direction == MessageDirection.OUTGOING &&
                                msg.kind == MessageKind.TEXT
                            ) {
                                TextButton(onClick = {
                                    editDraft = msg.body
                                    dialog = ComposerDialog.EDIT_MATRIX
                                }) { Text("Modifier…") }
                            }
                            if (capabilities.messageDelete &&
                                msg.direction == MessageDirection.OUTGOING
                            ) {
                                TextButton(onClick = {
                                    viewModel.deleteMessage(conversationId, protocol, msg.id) { ok ->
                                        if (!ok) sendError = "Suppression échouée"
                                    }
                                    dialog = ComposerDialog.NONE
                                    actionMessage = null
                                }) {
                                    Text(
                                        if (protocol == ProtocolId.MATRIX) {
                                            "Supprimer (redaction)"
                                        } else {
                                            "Supprimer"
                                        },
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = {
                            dialog = ComposerDialog.NONE
                            actionMessage = null
                        }) { Text("Fermer") }
                    },
                )
            }
        }
        ComposerDialog.EDIT_MATRIX -> {
            val msg = actionMessage
            AlertDialog(
                onDismissRequest = {
                    dialog = ComposerDialog.NONE
                    actionMessage = null
                },
                title = { Text("Modifier le message") },
                text = {
                    OutlinedTextField(
                        value = editDraft,
                        onValueChange = { editDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 6,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (msg != null && editDraft.isNotBlank()) {
                            viewModel.editMessage(
                                conversationId,
                                protocol,
                                msg.id,
                                editDraft.trim(),
                            ) { ok ->
                                if (!ok) sendError = "Modification échouée"
                            }
                        }
                        dialog = ComposerDialog.NONE
                        actionMessage = null
                    }) { Text("Enregistrer") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        dialog = ComposerDialog.NONE
                        actionMessage = null
                    }) { Text("Annuler") }
                },
            )
        }
        ComposerDialog.SAFETY_NUMBER -> {
            AlertDialog(
                onDismissRequest = { dialog = ComposerDialog.NONE },
                title = {
                    Text(
                        when (protocol) {
                            ProtocolId.XMPP -> "Empreintes OMEMO"
                            else -> "Numéro de sécurité Signal"
                        },
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            when (protocol) {
                                ProtocolId.XMPP ->
                                    "Comparez ces empreintes avec votre contact (TOFU). " +
                                        "Correction XEP-0308 / réactions XEP-0444 pas encore dans l’UI."
                                else ->
                                    "Comparez ces chiffres avec votre contact (hors groupe). " +
                                        "SVR2 et verrouillage d’inscription ne sont pas gérés ici."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        when {
                            e2eeDetailError != null -> Text(
                                e2eeDetailError!!,
                                color = MaterialTheme.colorScheme.error,
                            )
                            e2eeDetailText != null -> Text(
                                e2eeDetailText!!,
                                style = MaterialTheme.typography.bodyLarge,
                                fontFamily = FontFamily.Monospace,
                            )
                            else -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { dialog = ComposerDialog.NONE }) { Text("Fermer") }
                },
            )
        }
        ComposerDialog.FORWARD -> {
            val msg = actionMessage
            val targets = allConversations.filter {
                it.accountId == accountId && it.protocol == protocol && it.id != conversationId
            }
            AlertDialog(
                onDismissRequest = {
                    dialog = ComposerDialog.NONE
                    actionMessage = null
                },
                title = { Text("Transférer vers") },
                text = {
                    Column {
                        if (targets.isEmpty()) {
                            Text("Aucun autre chat sur ce compte.")
                        } else if (protocol != ProtocolId.TELEGRAM) {
                            Text("Transfert serveur disponible pour Telegram uniquement.")
                        } else {
                            targets.take(8).forEach { conv ->
                                TextButton(onClick = {
                                    if (msg != null) {
                                        viewModel.forwardTelegramMessages(
                                            conv.id,
                                            conversationId,
                                            listOf(msg.id),
                                        ) { ok ->
                                            if (!ok) sendError = "Transfert échoué"
                                        }
                                    }
                                    dialog = ComposerDialog.NONE
                                    actionMessage = null
                                }) { Text(conv.title) }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = {
                        dialog = ComposerDialog.NONE
                        actionMessage = null
                    }) { Text("Annuler") }
                },
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: Message,
    timeFormat: DateFormat,
    showReadReceipts: Boolean,
    canVotePoll: Boolean = false,
    onVotePoll: (optionIndex: Int) -> Unit = {},
    onLongPress: () -> Unit = {},
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
            modifier = Modifier
                .widthIn(max = 320.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongPress,
                ),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                KindChip(message.kind, message.expireSeconds)
                message.attachments.forEach { attachment ->
                    AttachmentContent(attachment)
                }
                StructuredPayload(
                    message = message,
                    canVotePoll = canVotePoll,
                    onVotePoll = onVotePoll,
                )
                if (message.body.isNotBlank() && message.kind != MessageKind.LOCATION &&
                    message.kind != MessageKind.POLL && message.kind != MessageKind.CONTACT
                ) {
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
                ReactionChips(message.payloadJson)
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
private fun ReactionChips(payloadJson: String?) {
    val reactions = remember(payloadJson) {
        runCatching {
            val arr = JSONObject(payloadJson ?: return@runCatching emptyList<String>())
                .optJSONArray("reactions") ?: return@runCatching emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }.getOrDefault(emptyList())
    }
    if (reactions.isEmpty()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 4.dp),
    ) {
        reactions.distinct().take(8).forEach { emoji ->
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(emoji, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}

@Composable
private fun KindChip(kind: MessageKind, expireSeconds: Int?) {
    val label = when (kind) {
        MessageKind.TEXT -> null
        MessageKind.IMAGE -> "Image"
        MessageKind.VIDEO -> "Vidéo"
        MessageKind.FILE -> "Fichier"
        MessageKind.GIF -> "GIF"
        MessageKind.STICKER -> "Sticker"
        MessageKind.VOICE -> "Vocal"
        MessageKind.LOCATION -> "Lieu"
        MessageKind.POLL -> "Sondage"
        MessageKind.CONTACT -> "Contact"
        MessageKind.SYSTEM -> "Système"
        MessageKind.CALL -> "Appel"
        MessageKind.STORY -> "Story"
        MessageKind.UNKNOWN -> null
    }
    if (label == null && expireSeconds == null) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(bottom = 4.dp),
    ) {
        label?.let {
            FilterChip(
                selected = false,
                onClick = {},
                enabled = false,
                label = { Text(it, style = MaterialTheme.typography.labelSmall) },
            )
        }
        expireSeconds?.let { sec ->
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text("${sec}s", style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

@Composable
private fun StructuredPayload(
    message: Message,
    canVotePoll: Boolean = false,
    onVotePoll: (optionIndex: Int) -> Unit = {},
) {
    val json = message.payloadJson ?: return
    val parsed = runCatching { JSONObject(json) }.getOrNull() ?: return
    val emailSubject = parsed.optString("subject").takeIf { it.isNotBlank() }
    if (emailSubject != null &&
        (message.protocol == ProtocolId.EMAIL || message.kind == MessageKind.TEXT)
    ) {
        Text(
            text = "Objet : $emailSubject",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
    when (message.kind) {
        MessageKind.LOCATION -> {
            val (lat, lon) = readLocationCoords(parsed) ?: return
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
            ) {
                Column(Modifier.padding(8.dp)) {
                    Text("Lieu partagé", style = MaterialTheme.typography.labelMedium)
                    Text(
                        "%.5f, %.5f".format(lat, lon),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        MessageKind.POLL -> {
            val poll = readPollFields(parsed, fallbackBody = message.body)
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
            ) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(poll.question, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    poll.options.forEach { option ->
                        val label = buildString {
                            append(option.text)
                            option.voterCount?.let { append(" · $it") }
                        }
                        AssistChip(
                            onClick = {
                                if (canVotePoll && !poll.isClosed) onVotePoll(option.index)
                            },
                            enabled = canVotePoll && !poll.isClosed,
                            label = {
                                Text(
                                    if (option.isChosen) "✓ $label" else label,
                                )
                            },
                        )
                    }
                    if (poll.isClosed) {
                        Text(
                            "Sondage clos",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        MessageKind.CONTACT -> {
            val (name, phone) = readContactFields(parsed, fallbackBody = message.body)
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
            ) {
                Column(Modifier.padding(8.dp)) {
                    Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    phone?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        else -> Unit
    }
}

/** Accepts flat latitude/longitude, Matrix lat/lon, or nested Signal location{}. */
private fun readLocationCoords(parsed: JSONObject): Pair<Double, Double>? {
    fun from(obj: JSONObject): Pair<Double, Double>? {
        val lat = when {
            obj.has("latitude") -> obj.optDouble("latitude", Double.NaN)
            obj.has("lat") -> obj.optDouble("lat", Double.NaN)
            else -> Double.NaN
        }
        val lon = when {
            obj.has("longitude") -> obj.optDouble("longitude", Double.NaN)
            obj.has("lon") -> obj.optDouble("lon", Double.NaN)
            else -> Double.NaN
        }
        return if (!lat.isNaN() && !lon.isNaN()) lat to lon else null
    }
    from(parsed)?.let { return it }
    val nested = parsed.optJSONObject("location") ?: return null
    return from(nested)
}

private data class PollOptionUi(
    val index: Int,
    val text: String,
    val isChosen: Boolean = false,
    val voterCount: Int? = null,
)

private data class PollUi(
    val question: String,
    val options: List<PollOptionUi>,
    val isClosed: Boolean = false,
)

/** Accepts flat question/options, nested poll{}, and Telegram option objects {text}. */
private fun readPollFields(parsed: JSONObject, fallbackBody: String): PollUi {
    val root = parsed.optJSONObject("poll") ?: parsed
    val question = root.optString("question").ifBlank { fallbackBody }
    val isClosed = root.optBoolean("isClosed", false)
    val optionsArr = root.optJSONArray("options")
    val options = buildList {
        if (optionsArr != null) {
            for (i in 0 until optionsArr.length()) {
                val asObj = optionsArr.optJSONObject(i)
                val text = when {
                    asObj != null -> asObj.optString("text").ifBlank { asObj.optString("label") }
                    else -> optionsArr.optString(i)
                }
                if (text.isBlank()) continue
                add(
                    PollOptionUi(
                        index = i,
                        text = text,
                        isChosen = asObj?.optBoolean("isChosen", false) == true,
                        voterCount = asObj?.optInt("voterCount", -1)?.takeIf { it >= 0 },
                    ),
                )
            }
        }
    }
    return PollUi(question = question, options = options, isClosed = isClosed)
}

/** Accepts firstName/lastName/phone, phoneNumber, contacts[], or XMPP vcard FN/TEL. */
private fun readContactFields(parsed: JSONObject, fallbackBody: String): Pair<String, String?> {
    val first = parsed.optString("firstName")
    val last = parsed.optString("lastName")
    var name = listOf(first, last).filter { it.isNotBlank() }.joinToString(" ")
    var phone = parsed.optString("phone").takeIf { it.isNotBlank() }
        ?: parsed.optString("phoneNumber").takeIf { it.isNotBlank() }

    if (name.isBlank() || phone == null) {
        val contacts = parsed.optJSONArray("contacts")
        val firstContact = contacts?.optJSONObject(0)
        if (firstContact != null) {
            if (name.isBlank()) {
                name = firstContact.optString("name").ifBlank {
                    listOf(
                        firstContact.optString("firstName"),
                        firstContact.optString("lastName"),
                    ).filter { it.isNotBlank() }.joinToString(" ")
                }
            }
            if (phone == null) {
                phone = firstContact.optString("phone").takeIf { it.isNotBlank() }
                    ?: firstContact.optString("phoneNumber").takeIf { it.isNotBlank() }
            }
        }
    }

    if (name.isBlank() || phone == null) {
        val vcard = parsed.optString("vcard").takeIf { it.isNotBlank() }
        if (vcard != null) {
            if (name.isBlank()) {
                name = Regex("""(?im)^FN:(.+)$""").find(vcard)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            }
            if (phone == null) {
                phone = Regex("""(?im)^TEL[^:]*:(.+)$""").find(vcard)?.groupValues?.getOrNull(1)?.trim()
            }
        }
    }

    return name.ifBlank { fallbackBody } to phone
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
                attachment.mimeType.startsWith("image/") &&
                    (attachment.localPath != null || !attachment.remoteRef.isNullOrBlank()) -> {
                    AsyncImage(
                        model = attachment.localPath?.let { File(it) } ?: attachment.remoteRef,
                        contentDescription = attachment.fileName,
                        modifier = Modifier
                            .padding(bottom = 4.dp)
                            .fillMaxWidth()
                            .height(180.dp),
                        contentScale = ContentScale.Crop,
                    )
                }
                attachment.mimeType.startsWith("audio/") -> {
                    Text(
                        text = "🎤 ${attachment.fileName ?: "Vocal"}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(bottom = 4.dp),
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

private const val MAX_ATTACHMENT_BYTES = 100L * 1024L * 1024L
