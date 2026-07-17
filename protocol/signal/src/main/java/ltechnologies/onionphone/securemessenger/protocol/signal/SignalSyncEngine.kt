package ltechnologies.onionphone.securemessenger.protocol.signal

import android.content.Context
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.data.MessengerRepository
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import org.whispersystems.signalservice.api.websocket.WebSocketUnavailableException
import timber.log.Timber

/**
 * Tor-only websocket message receiver (no FCM). Drains the authenticated websocket queue,
 * decrypts envelopes, and persists conversations/messages locally.
 */
internal class SignalSyncEngine(
    private val context: Context,
    private val accountId: String,
    private val session: SignalSessionContext,
    private val repository: MessengerRepository,
    private val proxy: ProxyConfig,
    private val groupHelper: SignalGroupHelper,
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
        cipher = session.cipher,
        authWebSocket = session.authWebSocket,
        messageReceiver = messageReceiver,
        groupHelper = groupHelper,
    )

    fun start(scope: CoroutineScope) {
        stop()
        job = scope.launch {
            while (isActive) {
                try {
                    SignalTor.withSocks(proxy, kotlinx.coroutines.Dispatchers.IO) {
                        SignalRuntimeFactory.applyTorProxy(session.network, proxy)
                        kotlinx.coroutines.runBlocking {
                            drainMessages()
                        }
                    }
                } catch (e: TimeoutException) {
                    Timber.d("Signal websocket read timeout for $accountId")
                } catch (e: WebSocketUnavailableException) {
                    Timber.w(e, "Signal websocket unavailable for $accountId, reconnecting")
                    runCatching { session.authWebSocket.connect() }
                } catch (e: Exception) {
                    Timber.w(e, "Signal sync failed for $accountId")
                }
            }
        }
        Timber.i("Signal sync engine started for $accountId")
    }

    private suspend fun drainMessages() {
        session.authWebSocket.registerKeepAliveToken(WEB_SOCKET_KEEP_ALIVE_TOKEN)
        try {
            session.authWebSocket.readMessageBatch(WEBSOCKET_READ_TIMEOUT_MS, BATCH_SIZE) { batch ->
                Timber.i("Signal retrieved ${batch.size} envelopes for $accountId")
                kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    messageHandler.processBatch(batch)
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
    }
}
