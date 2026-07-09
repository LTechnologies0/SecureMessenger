package ltechnologies.onionphone.securemessenger.protocol.matrix

import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.Url
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import net.folivo.trixnity.client.login
import net.folivo.trixnity.client.media.okio.createOkioMediaStoreModule
import net.folivo.trixnity.client.room.RoomService
import net.folivo.trixnity.client.room.message.text
import net.folivo.trixnity.client.store.eventId
import net.folivo.trixnity.client.store.originTimestamp
import net.folivo.trixnity.client.store.repository.createInMemoryRepositoriesModule
import net.folivo.trixnity.client.store.roomId
import net.folivo.trixnity.client.store.sender
import net.folivo.trixnity.clientserverapi.model.authentication.IdentifierType
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent
import okhttp3.OkHttpClient
import okio.Path.Companion.toPath
import org.koin.core.component.get
import timber.log.Timber

class TrixnityMatrixEngine(
    private val repository: MessengerRepository,
    private val filesDir: File,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: MatrixClient? = null
    private var observeJob: Job? = null

    suspend fun login(
        accountId: String,
        homeserver: String,
        userId: String,
        password: String,
        proxy: ProxyConfig,
    ): Result<MatrixClient> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = Url(MatrixUrls.normalizeHomeserver(homeserver))
            val localPart = MatrixUrls.loginLocalPart(userId)
            val proxyEngine = proxiedOkHttp(proxy)
            val mediaPath = filesDir.resolve("matrix_media_$accountId").absolutePath.toPath()

            val result = MatrixClient.login(
                baseUrl = baseUrl,
                identifier = IdentifierType.User(localPart),
                password = password,
                repositoriesModuleFactory = { createInMemoryRepositoriesModule() },
                mediaStoreModuleFactory = { createOkioMediaStoreModule(mediaPath) },
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
            Timber.w(e, "Trixnity login failed")
            Result.failure(e)
        }
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
                        val content = event.content?.getOrNull() as? RoomMessageEventContent ?: return@collect
                        val body = content.body
                        val roomId = event.roomId
                        val convId = MatrixProtocol.conversationIdFor(accountId, roomId.full)
                        val message = Message(
                            id = event.eventId.full,
                            conversationId = convId,
                            protocol = ProtocolId.MATRIX,
                            body = body,
                            timestamp = event.originTimestamp,
                            direction = if (event.sender == matrixClient.userId) {
                                MessageDirection.OUTGOING
                            } else {
                                MessageDirection.INCOMING
                            },
                            deliveryState = DeliveryState.DELIVERED,
                            senderDisplayName = event.sender.full,
                        )
                        repository.upsertMessage(message)
                        repository.upsertConversation(
                            Conversation(
                                id = convId,
                                protocol = ProtocolId.MATRIX,
                                accountId = accountId,
                                remoteId = roomId.full,
                                title = roomId.full,
                                lastMessagePreview = body.take(100),
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

    suspend fun sendText(roomId: String, body: String) {
        val matrixClient = client ?: return
        val roomService = matrixClient.di.get<RoomService>()
        roomService.sendMessage(RoomId(roomId)) {
            text(body)
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
        return OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, proxy.port)))
            .build()
    }
}
