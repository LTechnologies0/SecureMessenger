package ltechnologies.onionphone.securemessenger.protocol.matrix

import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.ContentType
import io.ktor.http.Url
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import ltechnologies.onionphone.securemessenger.core.model.Attachment
import ltechnologies.onionphone.securemessenger.core.model.AttachmentState
import ltechnologies.onionphone.securemessenger.core.model.Contact
import ltechnologies.onionphone.securemessenger.core.model.Conversation
import ltechnologies.onionphone.securemessenger.core.model.DeliveryState
import ltechnologies.onionphone.securemessenger.core.model.Message
import ltechnologies.onionphone.securemessenger.core.model.MessageDirection
import ltechnologies.onionphone.securemessenger.core.model.MessageKind
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.data.MessengerRepository
import net.folivo.trixnity.client.MatrixClient
import net.folivo.trixnity.client.flattenValues
import net.folivo.trixnity.client.fromStore
import net.folivo.trixnity.client.loginWith
import net.folivo.trixnity.client.loginWithPassword
import net.folivo.trixnity.client.loginWithToken
import net.folivo.trixnity.client.media.MediaService
import net.folivo.trixnity.client.media.okio.createOkioMediaStoreModule
import net.folivo.trixnity.client.room.GetTimelineEventsConfig
import net.folivo.trixnity.client.room.RoomService
import net.folivo.trixnity.client.room.message.audio
import net.folivo.trixnity.client.room.message.file
import net.folivo.trixnity.client.room.message.image
import net.folivo.trixnity.client.room.message.text
import net.folivo.trixnity.client.room.message.video
import net.folivo.trixnity.client.store.TimelineEvent
import net.folivo.trixnity.client.store.eventId
import net.folivo.trixnity.client.store.originTimestamp
import net.folivo.trixnity.client.store.repository.exposed.createExposedRepositoriesModule
import net.folivo.trixnity.client.store.roomId
import net.folivo.trixnity.client.store.sender
import net.folivo.trixnity.client.user.UserService
import net.folivo.trixnity.clientserverapi.model.authentication.IdentifierType
import net.folivo.trixnity.clientserverapi.model.media.FileTransferProgress
import net.folivo.trixnity.clientserverapi.model.rooms.GetEvents
import net.folivo.trixnity.core.model.EventId
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.UserId
import net.folivo.trixnity.core.model.events.m.ReceiptType
import net.folivo.trixnity.core.model.events.m.room.EncryptedFile
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent
import okhttp3.OkHttpClient
import okio.Path.Companion.toPath
import org.jetbrains.exposed.sql.Database
import org.koin.core.component.get
import org.koin.core.module.Module
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

class TrixnityMatrixEngine(
    private val repository: MessengerRepository,
    private val filesDir: File,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: MatrixClient? = null
    private var observeJob: Job? = null

    /** conversationId → display names / user ids currently typing. */
    private val typingFlows = ConcurrentHashMap<String, MutableStateFlow<List<String>>>()

    fun observeTyping(conversationId: String): StateFlow<List<String>> =
        typingFlows.getOrPut(conversationId) { MutableStateFlow(emptyList()) }.asStateFlow()

    suspend fun loginWithPassword(
        accountId: String,
        apiBaseUrl: String,
        userId: String,
        password: String,
        proxy: ProxyConfig,
    ): Result<MatrixClient> = withContext(Dispatchers.IO) {
        try {
            val paths = accountPaths(accountId)
            val baseUrl = Url(MatrixUrls.normalizeHomeserver(apiBaseUrl))
            val localPart = MatrixUrls.loginLocalPart(userId)
            val proxyEngine = proxiedOkHttp(proxy)

            val result = MatrixClient.loginWithPassword(
                baseUrl = baseUrl,
                identifier = IdentifierType.User(localPart),
                password = password,
                deviceId = "SM_${accountId.take(8)}",
                initialDeviceDisplayName = "SecureMessenger",
                repositoriesModuleFactory = { createPersistentRepositoriesModule(paths.cryptoDir) },
                mediaStoreModuleFactory = { createOkioMediaStoreModule(paths.mediaPath) },
            ) {
                name = "SecureMessenger"
                httpClientEngine = OkHttp.create { preconfigured = proxyEngine }
                storeTimelineEventContentUnencrypted = false
            }

            result.onSuccess { matrixClient ->
                client = matrixClient
                matrixClient.startSync()
                startObserving(accountId, matrixClient)
            }
            result
        } catch (e: Exception) {
            Timber.w(e, "Trixnity password login failed")
            Result.failure(e)
        }
    }

    suspend fun loginWithAccessToken(
        accountId: String,
        apiBaseUrl: String,
        userId: String,
        accessToken: String,
        proxy: ProxyConfig,
    ): Result<MatrixClient> = withContext(Dispatchers.IO) {
        try {
            val paths = accountPaths(accountId)
            // Prefer restoring Olm/Megolm state from the persistent Exposed store.
            restoreFromStore(accountId, proxy).getOrNull()?.let { restored ->
                return@withContext Result.success(restored)
            }
            val baseUrl = Url(MatrixUrls.normalizeHomeserver(apiBaseUrl))
            val proxyEngine = proxiedOkHttp(proxy)
            val deviceId = "SM_${accountId.take(8)}"
            val mxUser = UserId(userId)

            // Soft-login: reuse CS API access_token — do NOT call m.login.token (that's SSO one-shot).
            val result = MatrixClient.loginWith(
                baseUrl = baseUrl,
                repositoriesModuleFactory = { createPersistentRepositoriesModule(paths.cryptoDir) },
                mediaStoreModuleFactory = { createOkioMediaStoreModule(paths.mediaPath) },
                getLoginInfo = {
                    Result.success(
                        MatrixClient.LoginInfo(
                            userId = mxUser,
                            deviceId = deviceId,
                            accessToken = accessToken,
                            refreshToken = null,
                        ),
                    )
                },
            ) {
                name = "SecureMessenger"
                httpClientEngine = OkHttp.create { preconfigured = proxyEngine }
                storeTimelineEventContentUnencrypted = false
            }

            result.onSuccess { matrixClient ->
                client = matrixClient
                matrixClient.startSync()
                startObserving(accountId, matrixClient)
            }
            result
        } catch (e: Exception) {
            Timber.w(e, "Trixnity access-token soft login failed")
            Result.failure(e)
        }
    }

    /** Exchange an SSO one-time loginToken via Trixnity m.login.token. */
    suspend fun loginWithSsoLoginToken(
        accountId: String,
        apiBaseUrl: String,
        userIdHint: String,
        loginToken: String,
        proxy: ProxyConfig,
    ): Result<MatrixClient> = withContext(Dispatchers.IO) {
        try {
            val paths = accountPaths(accountId)
            val baseUrl = Url(MatrixUrls.normalizeHomeserver(apiBaseUrl))
            val localPart = MatrixUrls.loginLocalPart(userIdHint)
            val proxyEngine = proxiedOkHttp(proxy)
            val result = MatrixClient.loginWithToken(
                baseUrl = baseUrl,
                identifier = IdentifierType.User(localPart),
                token = loginToken,
                deviceId = "SM_${accountId.take(8)}",
                initialDeviceDisplayName = "SecureMessenger",
                repositoriesModuleFactory = { createPersistentRepositoriesModule(paths.cryptoDir) },
                mediaStoreModuleFactory = { createOkioMediaStoreModule(paths.mediaPath) },
            ) {
                name = "SecureMessenger"
                httpClientEngine = OkHttp.create { preconfigured = proxyEngine }
                storeTimelineEventContentUnencrypted = false
            }
            result.onSuccess { matrixClient ->
                client = matrixClient
                matrixClient.startSync()
                startObserving(accountId, matrixClient)
            }
            result
        } catch (e: Exception) {
            Timber.w(e, "Trixnity SSO loginToken failed")
            Result.failure(e)
        }
    }

    /**
     * Restores a MatrixClient from the per-account H2 crypto store when Olm account data exists.
     * Returns failure/null when the store is empty (first login) so callers can fall through to login.
     */
    suspend fun restoreFromStore(accountId: String, proxy: ProxyConfig): Result<MatrixClient?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val paths = accountPaths(accountId)
                val dbFile = File(paths.cryptoDir, "trixnity.mv.db")
                if (!dbFile.exists() && !File(paths.cryptoDir, "trixnity.mv.db").exists()) {
                    // H2 may create trixnity.mv.db or trixnity.db.trace — also check any file in dir.
                    if (paths.cryptoDir.listFiles()?.none { it.name.startsWith("trixnity") } == true) {
                        return@runCatching null
                    }
                }
                val proxyEngine = proxiedOkHttp(proxy)
                val repositoriesModule = createPersistentRepositoriesModule(paths.cryptoDir)
                val mediaModule = createOkioMediaStoreModule(paths.mediaPath)
                val restored = MatrixClient.fromStore(
                    repositoriesModule = repositoriesModule,
                    mediaStoreModule = mediaModule,
                ) {
                    name = "SecureMessenger"
                    httpClientEngine = OkHttp.create { preconfigured = proxyEngine }
                    storeTimelineEventContentUnencrypted = false
                }.getOrThrow()
                if (restored != null) {
                    client = restored
                    restored.startSync()
                    startObserving(accountId, restored)
                    Timber.i("Matrix Trixnity restored from store for $accountId")
                }
                restored
            }.onFailure { e ->
                Timber.w(e, "Matrix fromStore failed for $accountId")
            }
        }

    /** @deprecated Use [loginWithPassword] — kept for callers outside MatrixProtocol.connect. */
    suspend fun login(
        accountId: String,
        homeserver: String,
        userId: String,
        password: String,
        proxy: ProxyConfig,
    ): Result<MatrixClient> = loginWithPassword(accountId, homeserver, userId, password, proxy)

    private data class AccountPaths(val cryptoDir: File, val mediaPath: okio.Path)

    private fun accountPaths(accountId: String): AccountPaths {
        val cryptoDir = filesDir.resolve("matrix_crypto_$accountId").also { it.mkdirs() }
        val mediaPath = filesDir.resolve("matrix_media_$accountId").also { it.mkdirs() }.absolutePath.toPath()
        Timber.d("Matrix crypto dir for $accountId: ${cryptoDir.absolutePath}")
        return AccountPaths(cryptoDir, mediaPath)
    }

    private suspend fun createPersistentRepositoriesModule(cryptoDir: File): Module {
        val dbFile = cryptoDir.resolve("trixnity").absolutePath
        val database = Database.connect(
            url = "jdbc:h2:$dbFile;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        return createExposedRepositoriesModule(database)
    }

    private fun startObserving(accountId: String, matrixClient: MatrixClient) {
        observeJob?.cancel()
        val roomService = matrixClient.di.get<RoomService>()
        val userService = matrixClient.di.get<UserService>()
        val self = matrixClient.userId
        observeJob = scope.launch {
            launch {
                try {
                    roomService.getAll().flattenValues().collect { rooms ->
                        val conversations = rooms.mapNotNull { room ->
                            val roomId = room.roomId
                            val title = room.name?.explicitName ?: roomId.full
                            Conversation(
                                id = MatrixProtocol.conversationIdFor(accountId, roomId.full),
                                protocol = ProtocolId.MATRIX,
                                accountId = accountId,
                                remoteId = roomId.full,
                                title = title,
                                lastMessagePreview = null,
                                lastMessageAt = room.lastRelevantEventTimestamp?.toEpochMilliseconds() ?: 0L,
                            )
                        }
                        if (conversations.isNotEmpty()) repository.upsertConversations(conversations)
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Trixnity room observe failed")
                }
            }
            launch {
                try {
                    roomService.getTimelineEventsFromNowOn().collect { event ->
                        persistTimelineEvent(accountId, matrixClient, event, downloadMedia = true)
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Trixnity timeline observe failed")
                }
            }
            launch {
                try {
                    roomService.usersTyping.collect { typingByRoom ->
                        val activeConvIds = mutableSetOf<String>()
                        for ((roomId, content) in typingByRoom) {
                            val convId = MatrixProtocol.conversationIdFor(accountId, roomId.full)
                            activeConvIds.add(convId)
                            val flow = typingFlows.getOrPut(convId) { MutableStateFlow(emptyList()) }
                            val labels = mutableListOf<String>()
                            for (userId in content.users) {
                                if (userId == self) continue
                                val name = runCatching {
                                    userService.getById(roomId, userId).firstOrNull()
                                        ?.name
                                        ?.takeIf { it.isNotBlank() }
                                }.getOrNull()
                                labels.add(name ?: userId.full)
                            }
                            flow.value = labels
                        }
                        for ((convId, flow) in typingFlows) {
                            if (convId !in activeConvIds && flow.value.isNotEmpty()) {
                                flow.value = emptyList()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Trixnity typing observe failed")
                }
            }
        }
    }

    /**
     * Backfills older timeline events for [roomIdFull] via Trixnity
     * [RoomService.fillTimelineGaps], [RoomService.getLastTimelineEvents], and
     * [RoomService.getTimelineEvents] (BACKWARDS).
     * Returns the number of room-message events persisted.
     */
    suspend fun loadHistory(accountId: String, roomIdFull: String, limit: Int = 50): Int =
        withContext(Dispatchers.IO) {
            val matrixClient = client ?: return@withContext 0
            val roomService = matrixClient.di.get<RoomService>()
            val roomId = RoomId(roomIdFull)
            val pageSize = limit.coerceIn(1, 100).toLong()
            val room = roomService.getById(roomId).firstOrNull()
            val startFrom = room?.lastEventId ?: room?.lastRelevantEventId
            if (startFrom != null) {
                runCatching { roomService.fillTimelineGaps(roomId, startFrom, pageSize) }
                    .onFailure { Timber.w(it, "Matrix fillTimelineGaps failed for $roomIdFull") }
            }
            val config: GetTimelineEventsConfig.() -> Unit = {
                maxSize = pageSize
                fetchSize = pageSize
                decryptionTimeout = 15.seconds
            }
            val eventsFlow = roomService.getLastTimelineEvents(roomId, config).firstOrNull()
                ?: startFrom?.let {
                    roomService.getTimelineEvents(
                        roomId = roomId,
                        startFrom = it,
                        direction = GetEvents.Direction.BACKWARDS,
                        config = config,
                    )
                }
                ?: return@withContext 0
            val messages = mutableListOf<Message>()
            eventsFlow.collect { eventFlow ->
                val event = eventFlow.first { it.content != null }
                toMessage(accountId, matrixClient, event, downloadMedia = false)?.let { messages.add(it) }
            }
            if (messages.isNotEmpty()) {
                repository.upsertMessages(messages)
            }
            messages.size
        }

    /**
     * Builds contacts from members of joined rooms (DMs and groups), excluding self.
     * Returns how many contacts were written.
     */
    suspend fun refreshContactsFromDmRooms(accountId: String): Int = withContext(Dispatchers.IO) {
        val matrixClient = client ?: return@withContext 0
        val roomService = matrixClient.di.get<RoomService>()
        val userService = matrixClient.di.get<UserService>()
        val self = matrixClient.userId
        val rooms = roomService.getAll().flattenValues().first()
        val contacts = linkedMapOf<String, Contact>()
        for (room in rooms) {
            val roomId = room.roomId
            runCatching { userService.loadMembers(roomId, false) }
            val members = userService.getAll(roomId).first()
            for ((userId, userFlow) in members) {
                if (userId == self) continue
                val roomUser = userFlow.firstOrNull() ?: continue
                val remote = userId.full
                val existing = contacts[remote]
                val displayName = roomUser.name.ifBlank { remote }
                // Prefer a non-blank display name from a DM when already seen in a group.
                if (existing == null || (room.isDirect && displayName != remote)) {
                    contacts[remote] = Contact(
                        id = "${accountId}_$remote",
                        protocol = ProtocolId.MATRIX,
                        accountId = accountId,
                        remoteId = remote,
                        displayName = displayName,
                        handle = remote,
                    )
                }
            }
        }
        val list = contacts.values.toList()
        repository.replaceContacts(accountId, list)
        list.size
    }

    suspend fun setTyping(roomIdFull: String, typing: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        val matrixClient = client
            ?: return@withContext Result.failure(IllegalStateException("Not connected"))
        runCatching {
            matrixClient.api.room.setTyping(
                roomId = RoomId(roomIdFull),
                userId = matrixClient.userId,
                typing = typing,
                timeout = if (typing) 30_000L else null,
            ).getOrThrow()
        }
    }

    suspend fun markRead(roomIdFull: String, eventIdFull: String?): Result<Unit> =
        withContext(Dispatchers.IO) {
            val matrixClient = client
                ?: return@withContext Result.failure(IllegalStateException("Not connected"))
            runCatching {
                val roomId = RoomId(roomIdFull)
                val roomService = matrixClient.di.get<RoomService>()
                val eventId = eventIdFull?.takeIf { it.isNotBlank() }?.let { EventId(it) }
                    ?: roomService.getById(roomId).firstOrNull()?.lastEventId
                    ?: roomService.getById(roomId).firstOrNull()?.lastRelevantEventId
                    ?: error("No event to mark read in $roomIdFull")
                matrixClient.api.room.setReadMarkers(
                    roomId = roomId,
                    fullyRead = eventId,
                    read = eventId,
                ).getOrThrow()
                matrixClient.api.room.setReceipt(
                    roomId = roomId,
                    eventId = eventId,
                    receiptType = ReceiptType.Read,
                ).getOrThrow()
            }
        }

    suspend fun setDisplayName(displayName: String): Result<Unit> = withContext(Dispatchers.IO) {
        val matrixClient = client
            ?: return@withContext Result.failure(IllegalStateException("Not connected"))
        matrixClient.setDisplayName(displayName.trim().ifBlank { null })
    }

    fun currentDisplayName(): String? = client?.displayName?.value

    fun currentUserId(): String? = client?.userId?.full

    private suspend fun persistTimelineEvent(
        accountId: String,
        matrixClient: MatrixClient,
        event: TimelineEvent,
        downloadMedia: Boolean,
    ) {
        val message = toMessage(accountId, matrixClient, event, downloadMedia) ?: return
        repository.upsertMessage(message)
        val parsed = parseRoomMessage(event.eventId.full, event.content?.getOrNull()) ?: return
        repository.upsertConversation(
            Conversation(
                id = message.conversationId,
                protocol = ProtocolId.MATRIX,
                accountId = accountId,
                remoteId = event.roomId.full,
                title = event.roomId.full,
                lastMessagePreview = parsed.preview.take(100),
                lastMessageAt = event.originTimestamp,
            ),
        )
    }

    private suspend fun toMessage(
        accountId: String,
        matrixClient: MatrixClient,
        event: TimelineEvent,
        downloadMedia: Boolean,
    ): Message? {
        val parsed = parseRoomMessage(event.eventId.full, event.content?.getOrNull()) ?: return null
        val attachments = if (downloadMedia) {
            downloadAttachments(matrixClient, accountId, parsed.attachments)
        } else {
            parsed.attachments.map { it.attachment }
        }
        return Message(
            id = event.eventId.full,
            conversationId = MatrixProtocol.conversationIdFor(accountId, event.roomId.full),
            protocol = ProtocolId.MATRIX,
            body = parsed.body,
            timestamp = event.originTimestamp,
            direction = if (event.sender == matrixClient.userId) {
                MessageDirection.OUTGOING
            } else {
                MessageDirection.INCOMING
            },
            deliveryState = DeliveryState.DELIVERED,
            senderDisplayName = event.sender.full,
            attachments = attachments,
            kind = parsed.kind,
            payloadJson = parsed.payloadJson,
        )
    }

    private fun parseRoomMessage(eventId: String, content: Any?): ParsedRoomMessage? = when (content) {
        is RoomMessageEventContent.FileBased.Image -> {
            val mime = content.info?.mimeType ?: "image/*"
            val kind = if (mime.equals("image/gif", ignoreCase = true) ||
                content.fileName?.endsWith(".gif", ignoreCase = true) == true
            ) {
                MessageKind.GIF
            } else {
                MessageKind.IMAGE
            }
            val attachment = attachmentFromFileContent(
                attachmentId = "${eventId}_image",
                mimeType = mime,
                fileName = content.fileName,
                remoteRef = content.url ?: content.file?.url?.toString(),
                sizeBytes = content.info?.size ?: 0L,
                encryptedFile = content.file,
            )
            ParsedRoomMessage(
                body = content.body,
                preview = content.body.ifBlank { content.fileName ?: "Image" },
                kind = kind,
                attachments = listOf(attachment),
            )
        }
        is RoomMessageEventContent.FileBased.Video -> {
            val attachment = attachmentFromFileContent(
                attachmentId = "${eventId}_video",
                mimeType = content.info?.mimeType ?: "video/*",
                fileName = content.fileName,
                remoteRef = content.url ?: content.file?.url?.toString(),
                sizeBytes = content.info?.size ?: 0L,
                encryptedFile = content.file,
            )
            ParsedRoomMessage(
                body = content.body,
                preview = content.body.ifBlank { content.fileName ?: "Video" },
                kind = MessageKind.VIDEO,
                attachments = listOf(attachment),
            )
        }
        is RoomMessageEventContent.FileBased.Audio -> {
            val attachment = attachmentFromFileContent(
                attachmentId = "${eventId}_audio",
                mimeType = content.info?.mimeType ?: "audio/*",
                fileName = content.fileName,
                remoteRef = content.url ?: content.file?.url?.toString(),
                sizeBytes = content.info?.size ?: 0L,
                encryptedFile = content.file,
            )
            ParsedRoomMessage(
                body = content.body,
                preview = content.body.ifBlank { content.fileName ?: "Audio" },
                kind = MessageKind.VOICE,
                attachments = listOf(attachment),
            )
        }
        is RoomMessageEventContent.FileBased.File -> {
            val attachment = attachmentFromFileContent(
                attachmentId = "${eventId}_file",
                mimeType = content.info?.mimeType ?: "application/octet-stream",
                fileName = content.fileName,
                remoteRef = content.url ?: content.file?.url?.toString(),
                sizeBytes = content.info?.size ?: 0L,
                encryptedFile = content.file,
            )
            ParsedRoomMessage(
                body = content.body,
                preview = content.body.ifBlank { content.fileName ?: "File" },
                kind = MessageKind.FILE,
                attachments = listOf(attachment),
            )
        }
        is RoomMessageEventContent.Location -> {
            val geo = content.geoUri
            val coords = geo.removePrefix("geo:").substringBefore(';').split(',')
            val lat = coords.getOrNull(0)?.toDoubleOrNull()
            val lon = coords.getOrNull(1)?.toDoubleOrNull()
            val payload = buildString {
                append('{')
                append("\"geoUri\":")
                append('"').append(geo.replace("\"", "\\\"")).append('"')
                if (lat != null && lon != null) {
                    append(",\"lat\":").append(lat)
                    append(",\"lon\":").append(lon)
                }
                append('}')
            }
            ParsedRoomMessage(
                body = content.body.ifBlank { geo },
                preview = content.body.ifBlank { "Location" },
                kind = MessageKind.LOCATION,
                attachments = emptyList(),
                payloadJson = payload,
            )
        }
        is RoomMessageEventContent.TextBased -> {
            ParsedRoomMessage(
                body = content.body,
                preview = content.body,
                kind = MessageKind.TEXT,
                attachments = emptyList(),
            )
        }
        is RoomMessageEventContent -> {
            ParsedRoomMessage(
                body = content.body,
                preview = content.body,
                kind = MessageKind.UNKNOWN,
                attachments = emptyList(),
            )
        }
        else -> null
    }

    private data class AttachmentSeed(
        val attachment: Attachment,
        val encryptedFile: EncryptedFile?,
    )

    private fun attachmentFromFileContent(
        attachmentId: String,
        mimeType: String,
        fileName: String?,
        remoteRef: String?,
        sizeBytes: Long,
        encryptedFile: EncryptedFile? = null,
    ): AttachmentSeed = AttachmentSeed(
        attachment = Attachment(
            id = attachmentId,
            mimeType = mimeType,
            fileName = fileName,
            remoteRef = remoteRef,
            sizeBytes = sizeBytes,
            state = if (remoteRef != null) AttachmentState.PENDING else AttachmentState.FAILED,
        ),
        encryptedFile = encryptedFile,
    )

    private suspend fun downloadAttachments(
        matrixClient: MatrixClient,
        accountId: String,
        seeds: List<AttachmentSeed>,
    ): List<Attachment> {
        if (seeds.isEmpty()) return emptyList()
        val mediaService = matrixClient.di.get<MediaService>()
        val destDir = filesDir.resolve("matrix_downloads_$accountId").also { it.mkdirs() }
        return seeds.map { seed ->
            val base = seed.attachment
            val mxc = base.remoteRef ?: return@map base.copy(state = AttachmentState.FAILED)
            try {
                val progress = MutableStateFlow<FileTransferProgress?>(null)
                val platformMedia = if (seed.encryptedFile != null) {
                    mediaService.getEncryptedMedia(seed.encryptedFile, progress, false).getOrThrow()
                } else {
                    mediaService.getMedia(mxc, progress, false).getOrThrow()
                }
                val safeName = (base.fileName ?: "media").replace(Regex("[^A-Za-z0-9._-]"), "_")
                val out = File(destDir, "${base.id}_$safeName")
                var total = 0L
                BufferedOutputStream(out.outputStream()).use { fos ->
                    platformMedia.collect { chunk ->
                        total += chunk.size
                        if (total > MAX_MEDIA_BYTES) {
                            error("Media exceeds $MAX_MEDIA_BYTES bytes")
                        }
                        fos.write(chunk)
                    }
                }
                base.copy(
                    localPath = out.absolutePath,
                    sizeBytes = out.length(),
                    state = AttachmentState.READY,
                )
            } catch (e: Exception) {
                Timber.w(e, "Matrix media download failed for $mxc")
                base.copy(state = AttachmentState.FAILED)
            }
        }
    }

    private data class ParsedRoomMessage(
        val body: String,
        val preview: String,
        val kind: MessageKind,
        val attachments: List<AttachmentSeed>,
        val payloadJson: String? = null,
    )

    suspend fun sendText(roomId: String, body: String) {
        val matrixClient = client ?: return
        val roomService = matrixClient.di.get<RoomService>()
        roomService.sendMessage(RoomId(roomId)) {
            text(body)
        }
    }

    suspend fun sendMedia(
        roomId: String,
        localPath: String,
        mimeType: String,
        fileName: String?,
        caption: String?,
        kind: MessageKind = MessageKind.FILE,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val matrixClient = client ?: return@withContext Result.failure(IllegalStateException("Not connected"))
        val file = File(localPath)
        if (!file.exists()) {
            return@withContext Result.failure(IllegalStateException("File not found"))
        }
        if (file.length() > MAX_MEDIA_BYTES) {
            return@withContext Result.failure(IllegalStateException("File too large"))
        }
        val roomService = matrixClient.di.get<RoomService>()
        val displayName = fileName ?: file.name
        val body = caption?.takeIf { it.isNotBlank() } ?: displayName
        val normalizedMime = when {
            kind == MessageKind.GIF ||
                mimeType.equals("image/gif", ignoreCase = true) ||
                displayName.endsWith(".gif", ignoreCase = true) -> "image/gif"
            else -> mimeType
        }
        val contentType = runCatching { ContentType.parse(normalizedMime) }
            .getOrDefault(ContentType.Application.OctetStream)
        val mediaFlow = fileChunkFlow(file)
        return@withContext runCatching {
            roomService.sendMessage(RoomId(roomId)) {
                when {
                    kind == MessageKind.VIDEO || normalizedMime.startsWith("video/") -> {
                        video(
                            body = body,
                            video = mediaFlow,
                            fileName = displayName,
                            type = contentType,
                            size = file.length(),
                        )
                    }
                    kind == MessageKind.VOICE || normalizedMime.startsWith("audio/") -> {
                        audio(
                            body = body,
                            audio = mediaFlow,
                            fileName = displayName,
                            type = contentType,
                            size = file.length(),
                        )
                    }
                    kind == MessageKind.IMAGE ||
                        kind == MessageKind.GIF ||
                        normalizedMime.startsWith("image/") -> {
                        image(
                            body = body,
                            image = mediaFlow,
                            fileName = displayName,
                            type = contentType,
                            size = file.length(),
                        )
                    }
                    else -> {
                        file(
                            body = body,
                            file = mediaFlow,
                            fileName = displayName,
                            type = contentType,
                            size = file.length(),
                        )
                    }
                }
            }
            Unit
        }
    }

    suspend fun sendVoiceNote(
        roomId: String,
        localPath: String,
        mimeType: String,
        fileName: String?,
        durationMs: Int,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val matrixClient = client ?: return@withContext Result.failure(IllegalStateException("Not connected"))
        val file = File(localPath)
        if (!file.exists()) {
            return@withContext Result.failure(IllegalStateException("File not found"))
        }
        if (file.length() > MAX_MEDIA_BYTES) {
            return@withContext Result.failure(IllegalStateException("File too large"))
        }
        val roomService = matrixClient.di.get<RoomService>()
        val displayName = fileName ?: file.name
        val audioMime = mimeType.ifBlank { "audio/ogg" }
        val contentType = runCatching { ContentType.parse(audioMime) }
            .getOrDefault(ContentType.parse("audio/ogg"))
        return@withContext runCatching {
            roomService.sendMessage(RoomId(roomId)) {
                audio(
                    body = displayName,
                    audio = fileChunkFlow(file),
                    fileName = displayName,
                    type = contentType,
                    size = file.length(),
                    duration = durationMs.takeIf { it > 0 }?.toLong(),
                )
            }
            Unit
        }
    }

    private fun fileChunkFlow(file: File, chunkSize: Int = MEDIA_CHUNK_BYTES) = flow {
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(chunkSize)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                emit(if (n == buf.size) buf.copyOf() else buf.copyOf(n))
            }
        }
    }

    suspend fun sendLocation(
        roomId: String,
        latitude: Double,
        longitude: Double,
        horizontalAccuracy: Double = 0.0,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val matrixClient = client ?: return@withContext Result.failure(IllegalStateException("Not connected"))
        val roomService = matrixClient.di.get<RoomService>()
        val geoUri = buildString {
            append("geo:")
            append(latitude)
            append(',')
            append(longitude)
            if (horizontalAccuracy > 0.0) {
                append(";u=")
                append(horizontalAccuracy)
            }
        }
        return@withContext runCatching {
            roomService.sendMessage(RoomId(roomId)) {
                content(
                    RoomMessageEventContent.Location(
                        body = geoUri,
                        geoUri = geoUri,
                    ),
                )
            }
            Unit
        }
    }

    fun close() {
        observeJob?.cancel()
        observeJob = null
        typingFlows.values.forEach { it.value = emptyList() }
        typingFlows.clear()
        try {
            client?.close()
        } catch (_: Exception) {
        }
        client = null
        scope.cancel()
    }

    private fun proxiedOkHttp(proxy: ProxyConfig): OkHttpClient =
        MatrixHttpClientFactory.buildOkHttp(proxy)

    companion object {
        private const val MAX_MEDIA_BYTES = 100L * 1024L * 1024L
        private const val MEDIA_CHUNK_BYTES = 64 * 1024

        fun wipeAccountStore(filesDir: File, accountId: String) {
            runCatching {
                filesDir.resolve("matrix_crypto_$accountId").deleteRecursively()
            }
            runCatching {
                filesDir.resolve("matrix_media_$accountId").deleteRecursively()
            }
            runCatching {
                filesDir.resolve("matrix_downloads_$accountId").deleteRecursively()
            }
        }
    }
}
