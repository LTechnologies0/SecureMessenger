package ltechnologies.onionphone.securemessenger.protocol.signal

import android.content.Context
import java.io.IOException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.data.MessengerRepository
import org.whispersystems.signalservice.api.messages.EnvelopeResponse
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import org.whispersystems.signalservice.api.websocket.WebSocketConnectionState
import org.whispersystems.signalservice.api.websocket.WebSocketUnavailableException
import timber.log.Timber

/**
 * Websocket message receiver (honors Tor via SignalSocksHolder + libsignal Network SOCKS).
 * Drains the authenticated websocket queue, decrypts envelopes, and persists locally.
 */
internal class SignalSyncEngine(
    private val context: Context,
    private val accountId: String,
    private val session: SignalSessionContext,
    private val repository: MessengerRepository,
    private val credentialStore: ltechnologies.onionphone.securemessenger.core.security.EncryptedCredentialStore,
    @Suppress("UNUSED_PARAMETER") private val proxy: ProxyConfig,
    private val groupHelper: SignalGroupHelper,
    private val onTyping: (conversationId: String, peerLabel: String, started: Boolean) -> Unit,
    private val onContactsSynced: (count: Int) -> Unit,
    private val onKeysSynced: () -> Unit = {},
    private val onFetchLatest: (org.whispersystems.signalservice.internal.push.SyncMessage.FetchLatest.Type?) -> Unit = {},
) {
    private var job: Job? = null
    private val messageReceiver = org.whispersystems.signalservice.api.SignalServiceMessageReceiver(
        session.pushServiceSocket,
    )
    private val messageHandler = SignalMessageHandler(
        context = context,
        accountId = accountId,
        localAci = session.aci.toString(),
        repository = repository,
        credentialStore = credentialStore,
        cipher = session.cipher,
        authWebSocket = session.authWebSocket,
        messageReceiver = messageReceiver,
        groupHelper = groupHelper,
        protocolStore = session.protocolStore,
        messageSender = session.messageSender,
        onTyping = onTyping,
        onContactsSynced = onContactsSynced,
        onKeysSynced = onKeysSynced,
        onFetchLatest = onFetchLatest,
    )

    fun start(scope: CoroutineScope) {
        stop()
        job = scope.launch {
            var backoffMs = INITIAL_BACKOFF_MS
            while (isActive) {
                try {
                    ensureConnected()
                    drainMessages()
                    backoffMs = INITIAL_BACKOFF_MS
                } catch (e: TimeoutException) {
                    Timber.d("Signal websocket read timeout for $accountId")
                    backoffMs = INITIAL_BACKOFF_MS
                } catch (e: WebSocketUnavailableException) {
                    Timber.w(e, "Signal websocket unavailable for $accountId, reconnecting")
                    reconnectWebSocket()
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                } catch (e: IOException) {
                    Timber.w(
                        e,
                        "Signal sync IO for $accountId state=%s — forcing reconnect",
                        session.authWebSocket.stateSnapshot,
                    )
                    reconnectWebSocket()
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                } catch (e: Exception) {
                    Timber.w(
                        e,
                        "Signal sync failed for $accountId state=%s",
                        session.authWebSocket.stateSnapshot,
                    )
                    reconnectWebSocket()
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
        }
        Timber.i("Signal sync engine started for $accountId")
    }

    private fun ensureConnected() {
        session.applyNetworkProxyFromSocksHolder()
        session.authWebSocket.registerKeepAliveToken(SignalWebSocket.FOREGROUND_KEEPALIVE)
        session.authWebSocket.registerKeepAliveToken(WEB_SOCKET_KEEP_ALIVE_TOKEN)
        when (session.authWebSocket.stateSnapshot) {
            WebSocketConnectionState.CONNECTED,
            WebSocketConnectionState.CONNECTING,
            -> Unit
            else -> {
                Timber.i(
                    "Signal auth websocket not live (%s) — connect()",
                    session.authWebSocket.stateSnapshot,
                )
                runCatching { session.authWebSocket.connect() }
            }
        }
    }

    private fun reconnectWebSocket() {
        session.applyNetworkProxyFromSocksHolder()
        runCatching {
            session.authWebSocket.forceNewWebSocket()
            session.authWebSocket.registerKeepAliveToken(SignalWebSocket.FOREGROUND_KEEPALIVE)
            session.authWebSocket.registerKeepAliveToken(WEB_SOCKET_KEEP_ALIVE_TOKEN)
            session.authWebSocket.connect()
        }.onFailure { Timber.w(it, "Signal websocket reconnect failed for $accountId") }
    }

    private suspend fun drainMessages() {
        session.authWebSocket.registerKeepAliveToken(WEB_SOCKET_KEEP_ALIVE_TOKEN)
        val batches = Channel<List<EnvelopeResponse>>(capacity = 1)
        try {
            coroutineScope {
                val reader = launch(Dispatchers.IO) {
                    try {
                        session.authWebSocket.readMessageBatch(
                            WEBSOCKET_READ_TIMEOUT_MS,
                            BATCH_SIZE,
                        ) { batch ->
                            Timber.i("Signal retrieved ${batch.size} envelopes for $accountId")
                            // Backpressure: block the reader thread until the consumer accepts the batch.
                            kotlinx.coroutines.runBlocking { batches.send(batch) }
                        }
                    } finally {
                        batches.close()
                    }
                }
                try {
                    for (batch in batches) {
                        messageHandler.processBatch(batch)
                    }
                } finally {
                    reader.cancel()
                }
            }
        } finally {
            session.authWebSocket.removeKeepAliveToken(WEB_SOCKET_KEEP_ALIVE_TOKEN)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching {
            session.authWebSocket.removeKeepAliveToken(WEB_SOCKET_KEEP_ALIVE_TOKEN)
        }
    }

    companion object {
        private const val WEB_SOCKET_KEEP_ALIVE_TOKEN = "secure-messenger-sync"
        private const val WEBSOCKET_READ_TIMEOUT_MS = 30_000L
        private const val BATCH_SIZE = 30
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 60_000L
    }
}
