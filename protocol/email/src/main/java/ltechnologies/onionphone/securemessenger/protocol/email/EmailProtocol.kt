package ltechnologies.onionphone.securemessenger.protocol.email

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ltechnologies.onionphone.securemessenger.core.model.Account
import ltechnologies.onionphone.securemessenger.core.model.AccountCredentials
import ltechnologies.onionphone.securemessenger.core.model.Attachment
import ltechnologies.onionphone.securemessenger.core.model.BackupExportResult
import ltechnologies.onionphone.securemessenger.core.model.ConnectionResult
import ltechnologies.onionphone.securemessenger.core.model.ConnectionState
import ltechnologies.onionphone.securemessenger.core.model.Contact
import ltechnologies.onionphone.securemessenger.core.model.Conversation
import ltechnologies.onionphone.securemessenger.core.model.ConversationIds
import ltechnologies.onionphone.securemessenger.core.model.DeliveryState
import ltechnologies.onionphone.securemessenger.core.model.HistoryLoadResult
import ltechnologies.onionphone.securemessenger.core.model.Message
import ltechnologies.onionphone.securemessenger.core.model.MessageDirection
import ltechnologies.onionphone.securemessenger.core.model.MessageKind
import ltechnologies.onionphone.securemessenger.core.model.OutgoingContent
import ltechnologies.onionphone.securemessenger.core.model.ProtocolCapabilities
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.model.SanitizedText
import ltechnologies.onionphone.securemessenger.core.model.SendResult
import ltechnologies.onionphone.securemessenger.core.network.NetworkGuard
import ltechnologies.onionphone.securemessenger.core.network.ProxiedHttpClientFactory
import ltechnologies.onionphone.securemessenger.core.security.EncryptedCredentialStore
import ltechnologies.onionphone.securemessenger.data.MessengerRepository
import ltechnologies.onionphone.securemessenger.protocol.api.MessengerProtocol
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

@Singleton
class EmailProtocol @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkGuard: NetworkGuard,
    private val repository: MessengerRepository,
    private val credentialStore: EncryptedCredentialStore,
    private val httpFactory: ProxiedHttpClientFactory,
    private val mailAutoconfig: MailAutoconfig,
) : MessengerProtocol {

    override val id: ProtocolId = ProtocolId.EMAIL

    private val baseCapabilities = ProtocolCapabilities(
        directMessages = true,
        groupChats = false,
        // Attachments work for IMAP/SMTP MIME; JMAP send rejects them (gated live below).
        mediaSend = true,
        mediaReceive = true,
        typingIndicators = false,
        readReceipts = false,
        endToEndEncryption = false,
        requiresPhoneAuth = false,
        // Derived from recent mailbox peers — not a server address book.
        contacts = true,
        profileEdit = false,
        voiceNotes = false,
        stickers = false,
        gifs = false,
        locationShare = false,
        polls = false,
        contactShare = false,
        ephemeralMessages = false,
        // Recent window only (IMAP ~100 / POP3~50 / JMAP~50), not full archive backfill.
        messageHistory = true,
        backupExport = true,
    )

    /**
     * Protocol-wide: advertise media if at least one non-JMAP session can send attachments
     * (or no sessions yet). Per-account truth lives in [capabilitiesFor].
     */
    override val capabilities: ProtocolCapabilities
        get() {
            val anyMedia = sessions.isEmpty() ||
                sessions.values.any { it.config.storeKind != EmailStoreKind.JMAP }
            return baseCapabilities.copy(mediaSend = anyMedia)
        }

    override fun capabilitiesFor(accountId: String?): ProtocolCapabilities {
        if (accountId == null) return capabilities
        val session = sessions[accountId] ?: return baseCapabilities.copy(mediaSend = false)
        return baseCapabilities.copy(mediaSend = session.config.storeKind != EmailStoreKind.JMAP)
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, EmailSession>()
    private val imapEngines = ConcurrentHashMap<String, ImapSyncEngine>()
    private val pop3Engines = ConcurrentHashMap<String, Pop3SyncEngine>()
    private val smtpSender = SmtpSender()

    override val canRegister: Boolean = false

    override fun isAccountConnected(accountId: String): Boolean =
        sessions[accountId]?.isConnected() == true

    suspend fun detectSettings(email: String): MailAutoconfigResult? =
        try {
            mailAutoconfig.detect(email)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "Email autoconfig failed for %s", email)
            null
        }

    override suspend fun connect(account: AccountCredentials, proxy: ProxyConfig): ConnectionResult =
        withContext(Dispatchers.IO) {
            try {
                networkGuard.assertNetworkAllowed()
                _connectionState.value = ConnectionState.CONNECTING
                val config = EmailAccountConfig.fromSecrets(account.secrets)
                    ?: return@withContext ConnectionResult.Failure("Missing email/password")

                when (config.storeKind) {
                    EmailStoreKind.IMAP -> {
                        if (config.imapHost.isNullOrBlank() || config.smtpHost.isNullOrBlank()) {
                            return@withContext ConnectionResult.Failure(
                                "IMAP/SMTP hosts required (use Detect settings)",
                            )
                        }
                    }
                    EmailStoreKind.POP3 -> {
                        if (config.pop3Host.isNullOrBlank() || config.smtpHost.isNullOrBlank()) {
                            return@withContext ConnectionResult.Failure(
                                "POP3/SMTP hosts required (use Detect settings)",
                            )
                        }
                    }
                    EmailStoreKind.JMAP -> {
                        if (config.jmapSessionUrl.isNullOrBlank()) {
                            return@withContext ConnectionResult.Failure("JMAP session URL required")
                        }
                    }
                }

                sessions.remove(account.accountId)?.also { old ->
                    imapEngines.remove(account.accountId)?.stop()
                    pop3Engines.remove(account.accountId)?.stop()
                    old.close()
                }

                val session = when (config.storeKind) {
                    EmailStoreKind.IMAP, EmailStoreKind.POP3 ->
                        EmailSession.openStoreAndTransport(
                            account.accountId,
                            config,
                            proxy,
                            httpFactory,
                        )
                    EmailStoreKind.JMAP -> {
                        val client = JmapClient(
                            http = httpFactory.okhttpClient(),
                            sessionUrl = config.jmapSessionUrl!!,
                            email = config.email,
                            password = config.password,
                        )
                        client.connect()
                        EmailSession(
                            accountId = account.accountId,
                            config = config,
                            proxy = proxy,
                            jmapClient = client,
                        )
                    }
                }
                sessions[account.accountId] = session

                repository.upsertAccount(
                    Account(
                        id = account.accountId,
                        protocol = ProtocolId.EMAIL,
                        displayName = account.displayName.ifBlank { config.email },
                        connectionState = ConnectionState.CONNECTED,
                    ),
                )

                when (config.storeKind) {
                    EmailStoreKind.IMAP -> {
                        val engine = ImapSyncEngine(context, repository, credentialStore)
                        imapEngines[account.accountId] = engine
                        engine.start(scope, session)
                    }
                    EmailStoreKind.POP3 -> {
                        val engine = Pop3SyncEngine(context, repository)
                        pop3Engines[account.accountId] = engine
                        engine.start(scope, session)
                    }
                    EmailStoreKind.JMAP -> {
                        scope.launch { syncJmap(session) }
                    }
                }

                refreshConnectionState()
                ConnectionResult.Success
            } catch (e: Exception) {
                Timber.w(e, "Email connect failed")
                refreshConnectionState(preferErrorIfEmpty = true)
                ConnectionResult.Failure(e.message ?: "Email connect failed")
            }
        }

    override fun observeConversations(): Flow<List<Conversation>> =
        repository.observeConversations().map { list ->
            list.filter { it.protocol == ProtocolId.EMAIL }
        }

    override fun observeMessages(conversationId: String): Flow<List<Message>> =
        repository.observeMessages(conversationId)

    override suspend fun startConversation(
        remoteId: String,
        initialMessage: SanitizedText?,
        accountId: String?,
        asGroup: Boolean,
    ): SendResult = withContext(Dispatchers.IO) {
        if (asGroup) {
            return@withContext SendResult.Failure("Email has no groups")
        }
        val session = when {
            accountId != null -> sessions[accountId]
            sessions.size == 1 -> sessions.values.single()
            sessions.isEmpty() -> null
            else -> return@withContext SendResult.Failure(
                "Plusieurs comptes Email — précise accountId",
            )
        } ?: return@withContext SendResult.Failure("Not connected")
        val peer = runCatching { EmailAddress.requireValid(EmailAddress.extract(remoteId)) }
            .getOrElse { return@withContext SendResult.Failure(it.message ?: "Invalid email") }
        val conversationId = EmailThreading.mailboxConversationId(session.accountId, peer)
        repository.upsertConversation(
            Conversation(
                id = conversationId,
                protocol = ProtocolId.EMAIL,
                accountId = session.accountId,
                remoteId = peer,
                title = peer,
                lastMessagePreview = initialMessage?.value,
                lastMessageAt = System.currentTimeMillis(),
            ),
        )
        if (initialMessage != null && initialMessage.value.isNotBlank()) {
            when (val result = sendMessage(conversationId, initialMessage, session.accountId)) {
                is SendResult.Failure -> return@withContext result
                is SendResult.Success -> Unit
            }
        }
        SendResult.Success(conversationId)
    }

    override suspend fun sendMessage(
        conversationId: String,
        body: SanitizedText,
        accountId: String?,
    ): SendResult = withContext(Dispatchers.IO) {
        val (subject, plain) = EmailSubject.parse(body.value)
        sendInternal(conversationId, plain, emptyList(), accountId, subjectOverride = subject)
    }

    override suspend fun sendMedia(
        conversationId: String,
        attachment: Attachment,
        caption: SanitizedText?,
        accountId: String?,
    ): SendResult = withContext(Dispatchers.IO) {
        val raw = caption?.value.orEmpty().ifBlank { attachment.fileName ?: "attachment" }
        val (subject, plain) = EmailSubject.parse(raw)
        sendInternal(
            conversationId,
            plain.ifBlank { attachment.fileName ?: "attachment" },
            listOf(attachment),
            accountId,
            subjectOverride = subject,
        )
    }

    override suspend fun sendContent(
        conversationId: String,
        content: OutgoingContent,
        accountId: String?,
    ): SendResult = withContext(Dispatchers.IO) {
        when (content) {
            is OutgoingContent.Text -> {
                val (parsedSubject, plain) = EmailSubject.parse(content.body.value)
                sendInternal(
                    conversationId,
                    plain,
                    emptyList(),
                    accountId,
                    subjectOverride = content.subject?.trim()?.takeIf { it.isNotEmpty() } ?: parsedSubject,
                )
            }
            is OutgoingContent.Media -> sendMedia(
                conversationId,
                content.attachment,
                content.caption,
                accountId,
            )
            else -> SendResult.Failure("Content type not supported for EMAIL")
        }
    }

    private suspend fun sendInternal(
        conversationId: String,
        body: String,
        attachments: List<Attachment>,
        accountId: String?,
        subjectOverride: String? = null,
    ): SendResult {
        val conversation = repository.getConversation(conversationId)
            ?: return SendResult.Failure("Conversation introuvable")
        val resolvedAccountId = accountId
            ?: conversation.accountId.takeIf { it.isNotBlank() }
            ?: ConversationIds.emailAccountId(conversationId)
        val session = resolveSession(resolvedAccountId)
            ?: return SendResult.Failure(
                when {
                    resolvedAccountId == null && sessions.size > 1 ->
                        "Plusieurs comptes Email — précise accountId"
                    else -> "Non connecté"
                },
            )
        if (conversation.accountId.isNotBlank() && session.accountId != conversation.accountId) {
            return SendResult.Failure("Compte Email incorrect pour cette conversation")
        }

        val (to, subject, inReplyTo, references) = resolveReplyContext(
            conversation,
            body,
            subjectOverride = subjectOverride,
        )
        if (to.isEmpty()) {
            return SendResult.Failure("Destinataire introuvable pour cette conversation")
        }
        val result = when (session.config.storeKind) {
            EmailStoreKind.JMAP -> {
                if (attachments.isNotEmpty()) {
                    return SendResult.Failure(
                        "Pièces jointes JMAP non supportées — utilisez IMAP/SMTP",
                    )
                }
                session.jmapClient?.submit(to, subject, body, inReplyTo)
                    ?: SendResult.Failure("Client JMAP manquant")
            }
            EmailStoreKind.IMAP, EmailStoreKind.POP3 -> {
                smtpSender.send(
                    session = session,
                    to = to,
                    subject = subject,
                    body = body,
                    inReplyTo = inReplyTo,
                    references = references,
                    attachments = attachments,
                )
            }
        }

        if (result is SendResult.Success) {
            val messageId = result.messageId
            val root = inReplyTo ?: messageId
            val threadConversationId = if (conversationId.contains(":mailbox:")) {
                EmailThreading.conversationId(session.accountId, root)
            } else {
                conversationId
            }
            val now = System.currentTimeMillis()
            repository.upsertMessage(
                Message(
                    id = "${session.accountId}:msg:$messageId",
                    conversationId = threadConversationId,
                    protocol = ProtocolId.EMAIL,
                    body = body,
                    timestamp = now,
                    direction = MessageDirection.OUTGOING,
                    deliveryState = DeliveryState.SENT,
                    senderDisplayName = session.config.email,
                    attachments = attachments,
                    kind = if (attachments.isEmpty()) MessageKind.TEXT else MessageKind.FILE,
                    payloadJson = JSONObject()
                        .put("messageId", messageId)
                        .put("subject", subject)
                        .put("rootMessageId", root)
                        .put("to", to.firstOrNull().orEmpty())
                        .toString(),
                ),
            )
            val peerEmail = to.firstOrNull()?.takeIf { EmailAddress.isValid(it) }
                ?: conversation.remoteId.takeIf { it.contains('@') }
            repository.upsertConversation(
                conversation.copy(
                    id = threadConversationId,
                    // Keep peer email when remapping mailbox→thread so replies still resolve.
                    remoteId = peerEmail ?: root,
                    title = subject,
                    lastMessagePreview = body.take(160),
                    lastMessageAt = now,
                ),
            )
            if (threadConversationId != conversationId) {
                // Drop orphan mailbox shell so inbox / open chat don't stay empty.
                repository.deleteConversation(conversationId)
            }
            return SendResult.Success(
                messageId = messageId,
                conversationId = threadConversationId.takeIf { it != conversationId },
            )
        }
        return result
    }

    private suspend fun resolveReplyContext(
        conversation: Conversation,
        body: String,
        subjectOverride: String? = null,
    ): ReplyContext {
        val latestId = repository.latestMessageId(conversation.id)
        val latest = latestId?.let { repository.getMessage(it) }
            ?: repository.listMessagesPage(conversation.id, limit = 1, offset = 0).lastOrNull()
        val payload = latest?.payloadJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        val subjectFromPayload = payload?.optString("subject")?.takeIf { it.isNotBlank() }
        val messageId = payload?.optString("messageId")?.takeIf { it.isNotBlank() }
        val root = payload?.optString("rootMessageId")?.takeIf { it.isNotBlank() }
            ?: conversation.remoteId.takeIf { !it.contains('@') }

        val toFromPayload = payload?.optString("to")?.takeIf { EmailAddress.isValid(it) }
        val toFromRemote = conversation.remoteId.takeIf { EmailAddress.isValid(it) }
        val toFromIncoming = if (latest?.direction == MessageDirection.INCOMING) {
            latest.senderDisplayName
                ?.let { EmailAddress.extract(it) }
                ?.takeIf { EmailAddress.isValid(it) }
        } else {
            null
        }
        val peer = toFromPayload ?: toFromRemote ?: toFromIncoming
        val to = if (peer != null) {
            listOf(EmailAddress.requireValid(peer))
        } else {
            emptyList()
        }
        if (to.isEmpty()) {
            return ReplyContext(
                emptyList(),
                subjectOverride?.takeIf { it.isNotBlank() } ?: "Message",
                null,
                null,
            )
        }

        val subject = when {
            !subjectOverride.isNullOrBlank() -> subjectOverride.trim()
            subjectFromPayload != null && !subjectFromPayload.startsWith("Re:", ignoreCase = true) ->
                "Re: $subjectFromPayload"
            subjectFromPayload != null -> subjectFromPayload
            conversation.title.isNotBlank() && conversation.title.contains('@') ->
                body.take(40).ifBlank { "Message" }
            conversation.title.isNotBlank() -> {
                if (conversation.title.startsWith("Re:", ignoreCase = true)) conversation.title
                else "Re: ${conversation.title}"
            }
            else -> body.take(40).ifBlank { "Message" }
        }

        val references = when {
            root != null && messageId != null && root != messageId -> "<$root> <$messageId>"
            messageId != null -> "<$messageId>"
            root != null -> "<$root>"
            else -> null
        }
        return ReplyContext(to, subject, messageId, references)
    }

    private data class ReplyContext(
        val to: List<String>,
        val subject: String,
        val inReplyTo: String?,
        val references: String?,
    )

    override suspend fun loadMessageHistory(conversationId: String): HistoryLoadResult {
        val accountId = ConversationIds.emailAccountId(conversationId)
            ?: conversationId.substringBefore(':')
        val session = sessions[accountId] ?: return HistoryLoadResult.Success(
            messageCount = repository.countMessages(conversationId),
            loadedFromCache = true,
            syncedFromNetwork = false,
        )
        return try {
            when (session.config.storeKind) {
                EmailStoreKind.IMAP -> imapEngines[accountId]?.syncFolder(session)
                EmailStoreKind.POP3 -> pop3Engines[accountId]?.sync(session)
                EmailStoreKind.JMAP -> syncJmap(session)
            }
            HistoryLoadResult.Success(
                messageCount = repository.countMessages(conversationId),
                loadedFromCache = true,
                syncedFromNetwork = true,
            )
        } catch (e: Exception) {
            Timber.w(e, "Email history sync failed")
            HistoryLoadResult.Failure(e.message ?: "History sync failed")
        }
    }

    override suspend fun markRead(conversationId: String, messageId: String?) {
        withContext(Dispatchers.IO) {
            val conversation = repository.getConversation(conversationId) ?: return@withContext
            val accountId = conversation.accountId
            val session = sessions[accountId]
            if (session?.config?.storeKind == EmailStoreKind.IMAP) {
                runCatching {
                    val messages = repository.listMessagesPage(conversationId, limit = 200, offset = 0)
                    val targets = if (messageId != null) {
                        messages.filter { it.id == messageId }
                    } else {
                        messages.filter { it.direction == MessageDirection.INCOMING }
                    }
                    val ids = targets.mapNotNull { msg ->
                        msg.payloadJson
                            ?.let { runCatching { JSONObject(it) }.getOrNull() }
                            ?.optString("messageId")
                            ?.takeIf { it.isNotBlank() }
                    }
                    if (ids.isNotEmpty()) {
                        imapEngines[accountId]?.markSeen(session, ids)
                    }
                }.onFailure { Timber.w(it, "IMAP markRead failed for $conversationId") }
            }
            if (conversation.unreadCount > 0) {
                repository.upsertConversation(conversation.copy(unreadCount = 0))
            }
        }
    }

    override fun observeContacts(accountId: String): Flow<List<Contact>> =
        repository.observeContacts(accountId)

    override suspend fun refreshContacts(accountId: String): Result<Int> = withContext(Dispatchers.IO) {
        val conversations = repository.listConversationsForAccount(accountId)
        val contacts = linkedMapOf<String, Contact>()
        for (conversation in conversations) {
            val messages = repository.listMessagesPage(conversation.id, limit = 50, offset = 0)
            for (message in messages) {
                val addr = message.senderDisplayName?.let { EmailAddress.extract(it) } ?: continue
                if (!EmailAddress.isValid(addr)) continue
                contacts[addr] = Contact(
                    id = "$accountId:contact:$addr",
                    protocol = ProtocolId.EMAIL,
                    accountId = accountId,
                    remoteId = addr,
                    displayName = addr,
                    handle = addr,
                )
            }
            if (conversation.remoteId.contains('@') && EmailAddress.isValid(conversation.remoteId)) {
                val addr = EmailAddress.normalize(conversation.remoteId)
                contacts.putIfAbsent(
                    addr,
                    Contact(
                        id = "$accountId:contact:$addr",
                        protocol = ProtocolId.EMAIL,
                        accountId = accountId,
                        remoteId = addr,
                        displayName = addr,
                        handle = addr,
                    ),
                )
            }
        }
        repository.replaceContacts(accountId, contacts.values.toList())
        Result.success(contacts.size)
    }

    override suspend fun exportBackup(accountId: String, destinationPath: String): BackupExportResult =
        withContext(Dispatchers.IO) {
            try {
                val conversations = repository.listConversationsForAccount(accountId)
                val root = JSONObject()
                root.put("protocol", ProtocolId.EMAIL.name)
                root.put("accountId", accountId)
                root.put("exportedAt", Instant.now().toString())
                val convArray = JSONArray()
                var messageCount = 0
                for (conversation in conversations) {
                    val cObj = JSONObject()
                        .put("id", conversation.id)
                        .put("title", conversation.title)
                        .put("remoteId", conversation.remoteId)
                    val messages = repository.listMessagesPage(conversation.id, limit = 500, offset = 0)
                    messageCount += messages.size
                    val mArray = JSONArray()
                    for (message in messages) {
                        mArray.put(
                            JSONObject()
                                .put("id", message.id)
                                .put("body", message.body)
                                .put("timestamp", message.timestamp)
                                .put("direction", message.direction.name)
                                .put("sender", message.senderDisplayName),
                        )
                    }
                    cObj.put("messages", mArray)
                    convArray.put(cObj)
                }
                root.put("conversations", convArray)
                File(destinationPath).writeText(root.toString(2))
                BackupExportResult.Success(
                    uriOrPath = destinationPath,
                    messageCount = messageCount,
                    conversationCount = conversations.size,
                )
            } catch (e: Exception) {
                BackupExportResult.Failure(e.message ?: "Backup failed")
            }
        }

    override suspend fun disconnect(accountId: String?) = withContext(Dispatchers.IO) {
        val ids = if (accountId != null) listOf(accountId) else sessions.keys.toList()
        for (id in ids) {
            imapEngines.remove(id)?.stop()
            pop3Engines.remove(id)?.stop()
            sessions.remove(id)?.close()
            repository.upsertAccount(
                Account(
                    id = id,
                    protocol = ProtocolId.EMAIL,
                    displayName = credentialStore.getDisplayName(id) ?: id,
                    connectionState = ConnectionState.DISCONNECTED,
                ),
            )
        }
        refreshConnectionState()
    }

    private fun refreshConnectionState(preferErrorIfEmpty: Boolean = false) {
        _connectionState.value = when {
            sessions.values.any { it.isConnected() } -> ConnectionState.CONNECTED
            sessions.isNotEmpty() -> ConnectionState.CONNECTING
            preferErrorIfEmpty -> ConnectionState.ERROR
            else -> ConnectionState.DISCONNECTED
        }
    }

    private fun resolveSession(accountId: String?): EmailSession? = when {
        accountId != null -> sessions[accountId]
        sessions.size == 1 -> sessions.values.single()
        else -> null
    }

    private suspend fun syncJmap(session: EmailSession) {
        val client = session.jmapClient ?: return
        val ids = client.listRecentEmailIds(50)
        val emails = client.getEmails(ids)
        for (email in emails) {
            val messageId = email.messageId?.firstOrNull()
                ?: email.id
            val inReplyTo = email.inReplyTo?.firstOrNull()
            val references = email.references?.joinToString(" ")
            val root = EmailThreading.rootMessageId(messageId, inReplyTo, references)
            val conversationId = EmailThreading.conversationId(session.accountId, root)
            val from = email.from?.firstOrNull()?.let { addr ->
                val name = addr.name
                val mail = addr.email ?: return@let null
                if (!name.isNullOrBlank()) "$name <$mail>" else mail
            } ?: "unknown"
            val body = email.bodyValues?.values?.firstOrNull()?.value
                ?: email.preview
                ?: ""
            val subject = email.subject?.ifBlank { null } ?: "(sans objet)"
            val timestamp = parseInstant(email.receivedAt ?: email.sentAt)
            val parsedMessageId = EmailThreading.normalizeMessageId(messageId)
            val outgoing = EmailAddress.extract(from)
                .equals(session.config.email, ignoreCase = true)
            val msgId = "${session.accountId}:msg:$parsedMessageId"
            val alreadyPersisted = repository.getMessage(msgId) != null
            repository.upsertMessage(
                Message(
                    id = msgId,
                    conversationId = conversationId,
                    protocol = ProtocolId.EMAIL,
                    body = body,
                    timestamp = timestamp,
                    direction = if (outgoing) MessageDirection.OUTGOING else MessageDirection.INCOMING,
                    deliveryState = DeliveryState.DELIVERED,
                    senderDisplayName = from,
                    kind = MessageKind.TEXT,
                    payloadJson = JSONObject()
                        .put("messageId", parsedMessageId)
                        .put("subject", subject)
                        .put("rootMessageId", root)
                        .toString(),
                ),
            )
            val existing = repository.getConversation(conversationId)
            val bumpUnread = !alreadyPersisted && !outgoing
            repository.upsertConversation(
                Conversation(
                    id = conversationId,
                    protocol = ProtocolId.EMAIL,
                    accountId = session.accountId,
                    remoteId = root,
                    title = subject,
                    lastMessagePreview = body.take(160),
                    lastMessageAt = timestamp,
                    unreadCount = if (bumpUnread) {
                        (existing?.unreadCount ?: 0) + 1
                    } else {
                        existing?.unreadCount ?: 0
                    },
                ),
            )
        }
    }

    private fun parseInstant(raw: String?): Long {
        if (raw.isNullOrBlank()) return System.currentTimeMillis()
        return runCatching { Instant.parse(raw).toEpochMilli() }
            .getOrDefault(System.currentTimeMillis())
    }
}

/** Parses optional leading `Subject: …` line (NewChat / mailto-style). */
internal object EmailSubject {
    fun parse(raw: String): Pair<String?, String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null to raw
        val firstLine = trimmed.lineSequence().first()
        if (!firstLine.startsWith("Subject:", ignoreCase = true)) return null to raw
        val subject = firstLine.substringAfter(':').trim().takeIf { it.isNotEmpty() }
        val body = trimmed.lineSequence().drop(1).joinToString("\n").trim()
        return subject to body
    }
}
