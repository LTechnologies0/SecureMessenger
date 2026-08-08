package ltechnologies.onionphone.securemessenger.protocol.signal

import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Optional
import java.util.concurrent.Executors
import ltechnologies.onionphone.securemessenger.core.security.EncryptedCredentialStore
import ltechnologies.onionphone.securemessenger.protocol.signal.store.AndroidSignalProtocolStore
import org.signal.core.models.ServiceId.ACI
import org.signal.core.models.ServiceId.PNI
import org.signal.core.util.SignalSocksHolder
import org.signal.core.util.UptimeSleepTimer
import org.signal.libsignal.net.Network
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.whispersystems.signalservice.api.SignalServiceAccountManager
import org.whispersystems.signalservice.api.SignalServiceMessageSender
import org.whispersystems.signalservice.api.account.AccountApi
import org.whispersystems.signalservice.api.crypto.SignalServiceCipher
import org.whispersystems.signalservice.api.groupsv2.ClientZkOperations
import org.whispersystems.signalservice.api.groupsv2.GroupsV2Operations
import org.whispersystems.signalservice.api.keys.KeysApi
import org.whispersystems.signalservice.api.keys.PreKeyRepository
import org.whispersystems.signalservice.api.message.MessageApi
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.api.websocket.HealthMonitor
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import org.whispersystems.signalservice.api.websocket.WebSocketConnectionState
import org.whispersystems.signalservice.api.websocket.WebSocketFactory
import org.whispersystems.signalservice.internal.push.PushServiceSocket
import org.whispersystems.signalservice.internal.util.StaticCredentialsProvider
import org.whispersystems.signalservice.internal.websocket.LibSignalChatConnection
import org.whispersystems.signalservice.internal.websocket.applyConfiguration
import timber.log.Timber

internal data class SignalSessionContext(
    val e164: String,
    val aci: ACI,
    val pni: PNI,
    val deviceId: Int,
    val localAddress: SignalServiceAddress,
    val localProtocolAddress: SignalProtocolAddress,
    val credentials: StaticCredentialsProvider,
    val configuration: org.whispersystems.signalservice.internal.configuration.SignalServiceConfiguration,
    val pushServiceSocket: PushServiceSocket,
    val network: Network,
    val protocolStore: AndroidSignalProtocolStore,
    val accountManager: SignalServiceAccountManager,
    val authWebSocket: SignalWebSocket.AuthenticatedWebSocket,
    val unauthWebSocket: SignalWebSocket.UnauthenticatedWebSocket,
    val messageSender: SignalServiceMessageSender,
    val cipher: SignalServiceCipher,
    val keysApi: KeysApi,
    val groupsV2Operations: GroupsV2Operations,
    val profileApi: org.whispersystems.signalservice.api.profiles.ProfileApi,
    val profileKey: org.signal.libsignal.zkgroup.profiles.ProfileKey?,
    val clientZkOperations: ClientZkOperations,
    private val messageSenderExecutor: java.util.concurrent.ExecutorService,
) {
    /** Re-apply Tor SOCKS (or clearnet) onto the libsignal [Network] used by chat websockets. */
    fun applyNetworkProxyFromSocksHolder() {
        SignalRuntimeFactory.applyNetworkSocks(network)
    }

    fun shutdown() {
        runCatching { messageSender.cancelInFlightRequests() }
        runCatching { pushServiceSocket.cancelInFlightRequests() }
        runCatching { authWebSocket.disconnect() }
        runCatching { unauthWebSocket.disconnect() }
        runCatching { network.clearProxy() }
        runCatching { messageSenderExecutor.shutdownNow() }
    }
}

internal object SignalRuntimeFactory {
    private val loggingHealthMonitor = object : HealthMonitor {
        override fun onKeepAliveResponse(sentTimestamp: Long, isIdentifiedWebSocket: Boolean) = Unit
        override fun onMessageError(status: Int, isIdentifiedWebSocket: Boolean) {
            Timber.w(
                "Signal websocket HTTP error status=%d identified=%s",
                status,
                isIdentifiedWebSocket,
            )
        }
        override fun onReceivedAlerts(alerts: Array<out String>, isIdentifiedWebSocket: Boolean) {
            if (alerts.isNotEmpty()) {
                Timber.w("Signal websocket alerts identified=%s: %s", isIdentifiedWebSocket, alerts.joinToString())
            }
        }
    }

    fun open(
        trustStore: SignalAndroidTrustStore,
        credentialStore: EncryptedCredentialStore,
        accountId: String,
        secrets: Map<String, String>,
    ): SignalSessionContext {
        SignalLogging.install()
        val e164 = secrets[SignalCredentialKeys.E164] ?: error("E164 missing")
        val aci = ACI.parseOrThrow(secrets[SignalCredentialKeys.ACI] ?: error("ACI missing"))
        val pni = PNI.parseOrThrow(secrets[SignalCredentialKeys.PNI] ?: error("PNI missing"))
        val password = secrets[SignalCredentialKeys.PASSWORD] ?: error("password missing")
        val deviceId = secrets[SignalCredentialKeys.DEVICE_ID]?.toIntOrNull()
            ?: SignalServiceAddress.DEFAULT_DEVICE_ID

        val configuration = SignalServiceEnvironment.configuration(trustStore)
        val credentials = StaticCredentialsProvider(aci, pni, e164, deviceId, password)
        Timber.i(
            "Signal session open deviceId=%d username=%s",
            deviceId,
            credentials.username,
        )
        val pushServiceSocket = PushServiceSocket(
            configuration,
            credentials,
            SignalServiceEnvironment.SIGNAL_AGENT,
            false,
        )
        val protocolStore = AndroidSignalProtocolStore.fromSecrets(credentialStore, accountId, secrets)
        // Linked devices always operate in multi-device mode (needed for sync messages).
        if (deviceId != SignalServiceAddress.DEFAULT_DEVICE_ID) {
            protocolStore.aci().setMultiDevice(true)
            protocolStore.pni().setMultiDevice(true)
        }
        val network = Network(Network.Environment.PRODUCTION, SignalServiceEnvironment.SIGNAL_AGENT)
        // Apply censorship/proxy flags from service config, then Tor SOCKS if SignalSocksHolder is set.
        // OkHttp (link/REST) already honors SignalSocksHolder; libsignal Network does not unless we set it.
        network.applyConfiguration(configuration)
        applyNetworkSocks(network)
        val sleepTimer = UptimeSleepTimer()
        val canConnect = SignalWebSocket.CanConnect { true }

        val authFactory = websocketFactory(network, credentials, receiveStories = false, "secure-messenger-auth")
        val unauthFactory = websocketFactory(network, credentials = null, receiveStories = false, "secure-messenger-unauth")

        val authWebSocket = SignalWebSocket.AuthenticatedWebSocket(authFactory, canConnect, sleepTimer, 30_000L)
        val unauthWebSocket = SignalWebSocket.UnauthenticatedWebSocket(unauthFactory, canConnect, sleepTimer, 30_000L)
        authWebSocket.state.subscribe { state: WebSocketConnectionState ->
            Timber.i("Signal auth websocket → %s", state)
        }
        unauthWebSocket.state.subscribe { state: WebSocketConnectionState ->
            Timber.d("Signal unauth websocket → %s", state)
        }
        val accountApi = AccountApi(authWebSocket)
        val clientZkOperations = ClientZkOperations.create(configuration)
        val groupsV2Operations = GroupsV2Operations(
            clientZkOperations,
            SignalServiceEnvironment.MAX_GROUP_SIZE,
        )
        val accountManager = SignalServiceAccountManager(
            authWebSocket,
            accountApi,
            pushServiceSocket,
            groupsV2Operations,
        )
        val keysApi = KeysApi(authWebSocket, unauthWebSocket)
        val messageApi = MessageApi(authWebSocket, unauthWebSocket)
        val localAddress = SignalServiceAddress(aci, e164)
        val localProtocolAddress = SignalProtocolAddress(localAddress.identifier, deviceId)
        val preKeyRepository = PreKeyRepository(
            keysApi,
            protocolStore.aci(),
            localProtocolAddress,
            SignalSessionLockImpl,
            PreKeyRepository.BatchHelper { block -> block.run() },
        )
        val messageSenderExecutor = Executors.newSingleThreadExecutor()
        val messageSender = SignalServiceMessageSender(
            pushServiceSocket,
            protocolStore,
            SignalSessionLockImpl,
            messageApi,
            keysApi,
            Optional.empty(),
            messageSenderExecutor,
            0L,
            0,
            { true },
            preKeyRepository,
        )
        val cipher = SignalServiceCipher(
            localAddress,
            deviceId,
            protocolStore.aci(),
            SignalSessionLockImpl,
            SignalCertificateUtil.validator,
        )

        val profileApi = org.whispersystems.signalservice.api.profiles.ProfileApi(
            authWebSocket,
            unauthWebSocket,
            pushServiceSocket,
            clientZkOperations.profileOperations,
        )
        val profileKey = secrets[SignalCredentialKeys.PROFILE_KEY]?.let { encoded ->
            runCatching {
                val bytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
                org.signal.libsignal.zkgroup.profiles.ProfileKey(bytes)
            }.getOrNull()
        }

        authWebSocket.registerKeepAliveToken(SignalWebSocket.FOREGROUND_KEEPALIVE)
        unauthWebSocket.registerKeepAliveToken(SignalWebSocket.FOREGROUND_KEEPALIVE)
        authWebSocket.connect()
        runCatching { unauthWebSocket.connect() }
            .onFailure { Timber.w(it, "Signal unauth websocket connect failed") }

        return SignalSessionContext(
            e164 = e164,
            aci = aci,
            pni = pni,
            deviceId = deviceId,
            localAddress = localAddress,
            localProtocolAddress = localProtocolAddress,
            credentials = credentials,
            configuration = configuration,
            pushServiceSocket = pushServiceSocket,
            network = network,
            protocolStore = protocolStore,
            accountManager = accountManager,
            authWebSocket = authWebSocket,
            unauthWebSocket = unauthWebSocket,
            messageSender = messageSender,
            cipher = cipher,
            keysApi = keysApi,
            groupsV2Operations = groupsV2Operations,
            profileApi = profileApi,
            profileKey = profileKey,
            clientZkOperations = clientZkOperations,
            messageSenderExecutor = messageSenderExecutor,
        )
    }

    private fun websocketFactory(
        network: Network,
        credentials: StaticCredentialsProvider?,
        receiveStories: Boolean,
        name: String,
    ): WebSocketFactory = WebSocketFactory {
        LibSignalChatConnection(name, network, credentials, receiveStories, loggingHealthMonitor)
    }

    /**
     * Libsignal chat websockets ignore [SignalSocksHolder] (OkHttp-only). When Tor is enabled we must
     * mirror the SOCKS5 endpoint onto [Network], otherwise REST link succeeds while WS dies with
     * "Connection closed!".
     */
    fun applyNetworkSocks(network: Network) {
        val socks = SignalSocksHolder.get()
        if (socks != null && socks.type() == Proxy.Type.SOCKS) {
            val addr = socks.address() as? InetSocketAddress
            val host = addr?.hostString?.takeIf { it.isNotBlank() }
                ?: addr?.address?.hostAddress
            val port = addr?.port ?: -1
            if (host.isNullOrBlank() || port <= 0) {
                Timber.w("Signal Network SOCKS holder set but address invalid — clearing")
                runCatching { network.clearProxy() }
                return
            }
            runCatching {
                network.setProxy("socks5", host, port, "", "")
                Timber.i("Signal Network SOCKS5 %s:%d", host, port)
            }.onFailure {
                Timber.e(it, "Failed to set Signal Network SOCKS5")
                runCatching { network.setInvalidProxy() }
            }
        } else {
            runCatching { network.clearProxy() }
            Timber.d("Signal Network clearnet (no SOCKS)")
        }
    }
}

internal fun signalConversationId(accountId: String, remoteId: String): String = "${accountId}_$remoteId"

internal fun resolveSignalAddress(remoteId: String): SignalServiceAddress {
    val trimmed = remoteId.trim()
    return when {
        trimmed.startsWith("+") || trimmed.all { it.isDigit() } -> {
            SignalServiceAddress.fromRaw(trimmed, null)
                .orElseThrow { IllegalArgumentException("Numéro Signal invalide") }
        }
        else -> {
            val aci = ACI.parseOrThrow(trimmed)
            SignalServiceAddress(aci)
        }
    }
}
