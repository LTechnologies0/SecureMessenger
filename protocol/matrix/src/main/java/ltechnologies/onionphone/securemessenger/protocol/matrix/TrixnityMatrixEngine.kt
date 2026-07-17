package ltechnologies.onionphone.securemessenger.protocol.matrix

import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.ContentType
import io.ktor.http.Url
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ltechnologies.onionphone.securemessenger.core.model.Attachment
import ltechnologies.onionphone.securemessenger.core.model.AttachmentState
import ltechnologies.onionphone.securemessenger.core.model.Conversation
import ltechnologies.onionphone.securemessenger.core.model.DeliveryState
import ltechnologies.onionphone.securemessenger.core.model.Message
import ltechnologies.onionphone.securemessenger.core.model.MessageDirection
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.proxy.SocksEndpointResolver
import ltechnologies.onionphone.securemessenger.data.MessengerRepository
import net.folivo.trixnity.client.MatrixClient
import net.folivo.trixnity.client.flattenValues
import net.folivo.trixnity.client.fromStore
import net.folivo.trixnity.client.loginWith
import net.folivo.trixnity.client.loginWithPassword
import net.folivo.trixnity.client.loginWithToken
import net.folivo.trixnity.client.media.MediaService
import net.folivo.trixnity.client.media.okio.createOkioMediaStoreModule
import net.folivo.trixnity.client.room.RoomService
import net.folivo.trixnity.client.room.message.file
import net.folivo.trixnity.client.room.message.image
import net.folivo.trixnity.client.room.message.text
import net.folivo.trixnity.client.store.eventId
import net.folivo.trixnity.client.store.originTimestamp
import net.folivo.trixnity.client.store.repository.exposed.createExposedRepositoriesModule
import net.folivo.trixnity.client.store.roomId
import net.folivo.trixnity.client.store.sender
import net.folivo.trixnity.clientserverapi.model.authentication.IdentifierType
import net.folivo.trixnity.clientserverapi.model.media.FileTransferProgress
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.UserId
import net.folivo.trixnity.core.model.events.m.room.EncryptedFile
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent
import okhttp3.OkHttpClient
import okio.Path.Companion.toPath
import org.jetbrains.exposed.sql.Database
import org.koin.core.component.get
import org.koin.core.module.Module
import timber.log.Timber

class TrixnityMatrixEngine(
    private val repository: MessengerRepository,
    private val filesDir: File,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: MatrixClient? = null
    private var observeJob: Job? = null

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
                        val parsed = parseRoomMessage(event.content?.getOrNull()) ?: return@collect
                        val roomId = event.roomId
                        val convId = MatrixProtocol.conversationIdFor(accountId, roomId.full)
                        val attachments = downloadAttachments(matrixClient, accountId, parsed.attachments)
                        val message = Message(
                            id = event.eventId.full,
                            conversationId = convId,
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
                        )
                        repository.upsertMessage(message)
                        repository.upsertConversation(
                            Conversation(
                                id = convId,
                                protocol = ProtocolId.MATRIX,
                                accountId = accountId,
                                remoteId = roomId.full,
                                title = roomId.full,
                                lastMessagePreview = parsed.preview.take(100),
                                lastMessageAt = event.originTimestamp,
                            ),
                        )
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Trixnity timeline observe failed")
                }
            }
        }
    }

    private fun parseRoomMessage(content: Any?): ParsedRoomMessage? = when (content) {
        is RoomMessageEventContent.FileBased.Image -> {
            val attachment = attachmentFromFileContent(
                eventSuffix = "image",
                mimeType = content.info?.mimeType ?: "image/*",
                fileName = content.fileName,
                remoteRef = content.url ?: content.file?.url?.toString(),
                sizeBytes = content.info?.size ?: 0L,
                encryptedFile = content.file,
            )
            ParsedRoomMessage(
                body = content.body,
                preview = content.body.ifBlank { content.fileName ?: "Image" },
                attachments = listOf(attachment),
            )
        }
        is RoomMessageEventContent.FileBased.File -> {
            val attachment = attachmentFromFileContent(
                eventSuffix = "file",
                mimeType = content.info?.mimeType ?: "application/octet-stream",
                fileName = content.fileName,
                remoteRef = content.url ?: content.file?.url?.toString(),
                sizeBytes = content.info?.size ?: 0L,
                encryptedFile = content.file,
            )
            ParsedRoomMessage(
                body = content.body,
                preview = content.body.ifBlank { content.fileName ?: "File" },
                attachments = listOf(attachment),
            )
        }
        is RoomMessageEventContent -> {
            ParsedRoomMessage(body = content.body, preview = content.body, attachments = emptyList())
        }
        else -> null
    }

    private data class AttachmentSeed(
        val attachment: Attachment,
        val encryptedFile: EncryptedFile?,
    )

    private fun attachmentFromFileContent(
        eventSuffix: String,
        mimeType: String,
        fileName: String?,
        remoteRef: String?,
        sizeBytes: Long,
        encryptedFile: EncryptedFile? = null,
    ): AttachmentSeed = AttachmentSeed(
        attachment = Attachment(
            id = "${eventSuffix}_${System.nanoTime()}",
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
                val chunks = platformMedia.toList()
                val bytes = if (chunks.isEmpty()) {
                    ByteArray(0)
                } else {
                    chunks.reduce { acc, chunk -> acc + chunk }
                }
                val safeName = (base.fileName ?: "media").replace(Regex("[^A-Za-z0-9._-]"), "_")
                val out = File(destDir, "${base.id}_$safeName")
                out.writeBytes(bytes)
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
        val attachments: List<AttachmentSeed>,
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
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val matrixClient = client ?: return@withContext Result.failure(IllegalStateException("Not connected"))
        val file = File(localPath)
        if (!file.exists()) {
            return@withContext Result.failure(IllegalStateException("File not found"))
        }
        val roomService = matrixClient.di.get<RoomService>()
        val bytes = file.readBytes()
        val contentType = runCatching { ContentType.parse(mimeType) }.getOrDefault(ContentType.Application.OctetStream)
        val displayName = fileName ?: file.name
        val body = caption?.takeIf { it.isNotBlank() } ?: displayName
        return@withContext runCatching {
            roomService.sendMessage(RoomId(roomId)) {
                if (mimeType.startsWith("image/")) {
                    image(
                        body = body,
                        image = flowOf(bytes),
                        fileName = displayName,
                        type = contentType,
                        size = file.length(),
                    )
                } else {
                    file(
                        body = body,
                        file = flowOf(bytes),
                        fileName = displayName,
                        type = contentType,
                        size = file.length(),
                    )
                }
            }
            Unit
        }
    }

    fun close() {
        observeJob?.cancel()
        observeJob = null
        try {
            client?.close()
        } catch (_: Exception) {
        }
        client = null
    }

    private fun proxiedOkHttp(proxy: ProxyConfig): OkHttpClient {
        val socksHost = SocksEndpointResolver.resolveReachableHost(proxy.host, proxy.port)
        try {
            java.net.Socket().use { socket ->
                socket.connect(InetSocketAddress(socksHost, proxy.port), 3_000)
            }
        } catch (e: Exception) {
            throw IllegalStateException(
                "Tor requis : SOCKS $socksHost:${proxy.port} injoignable — démarrez Orbot ou InviZible",
                e,
            )
        }
        return OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, proxy.port)))
            .build()
    }

    companion object {
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
