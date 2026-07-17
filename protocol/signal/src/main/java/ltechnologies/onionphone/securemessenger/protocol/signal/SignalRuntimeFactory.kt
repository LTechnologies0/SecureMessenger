package ltechnologies.onionphone.securemessenger.protocol.signal

import java.util.Locale
import java.util.Optional
import java.util.concurrent.Executors
import org.signal.core.models.ServiceId.ACI
import org.signal.core.models.ServiceId.PNI
import org.signal.core.util.UptimeSleepTimer
import org.signal.libsignal.net.Network
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.whispersystems.signalservice.api.SignalServiceAccountManager
import org.whispersystems.signalservice.api.SignalServiceMessageSender
import org.whispersystems.signalservice.api.account.AccountApi
import org.whispersystems.signalservice.api.crypto.ContentHint
import org.whispersystems.signalservice.api.crypto.SignalServiceCipher
import org.whispersystems.signalservice.api.groupsv2.ClientZkOperations
import org.whispersystems.signalservice.api.groupsv2.GroupsV2Operations
import org.whispersystems.signalservice.api.keys.KeysApi
import org.whispersystems.signalservice.api.keys.PreKeyRepository
import org.whispersystems.signalservice.api.message.MessageApi
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.api.websocket.HealthMonitor
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import org.whispersystems.signalservice.api.websocket.WebSocketFactory
import org.whispersystems.signalservice.internal.push.PushServiceSocket
import org.whispersystems.signalservice.internal.util.StaticCredentialsProvider
import org.whispersystems.signalservice.internal.websocket.LibSignalChatConnection
import org.whispersystems.signalservice.internal.websocket.WebSocketConnection
import ltechnologies.onionphone.securemessenger.core.security.EncryptedCredentialStore
import ltechnologies.onionphone.securemessenger.protocol.signal.store.AndroidSignalProtocolStore

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
) {
    fun shutdown() {
        runCatching { messageSender.cancelInFlightRequests() }
        runCatching { pushServiceSocket.cancelInFlightRequests() }
        runCatching { authWebSocket.disconnect() }
        runCatching { unauthWebSocket.disconnect() }
        runCatching { network.clearProxy() }
    }
}

internal object SignalRuntimeFactory {
    private val noopHealthMonitor = object : HealthMonitor {
        override fun onKeepAliveResponse(sentTimestamp: Long, isIdentifiedWebSocket: Boolean) = Unit
        override fun onMessageError(status: Int, isIdentifiedWebSocket: Boolean) = Unit
        override fun onReceivedAlerts(alerts: Array<out String>, isIdentifiedWebSocket: Boolean) = Unit
    }

    fun open(
        trustStore: SignalAndroidTrustStore,
        credentialStore: EncryptedCredentialStore,
        accountId: String,
        secrets: Map<String, String>,
    ): SignalSessionContext {
        val e164 = secrets[SignalCredentialKeys.E164] ?: error("E164 missing")
        val aci = ACI.parseOrThrow(secrets[SignalCredentialKeys.ACI] ?: error("ACI missing"))
        val pni = PNI.parseOrThrow(secrets[SignalCredentialKeys.PNI] ?: error("PNI missing"))
        val password = secrets[SignalCredentialKeys.PASSWORD] ?: error("password missing")
        val deviceId = secrets[SignalCredentialKeys.DEVICE_ID]?.toIntOrNull()
            ?: SignalServiceAddress.DEFAULT_DEVICE_ID

        val configuration = SignalServiceEnvironment.configuration(trustStore)
        val credentials = StaticCredentialsProvider(aci, pni, e164, deviceId, password)
        val pushServiceSocket = PushServiceSocket(
            configuration,
            credentials,
            SignalServiceEnvironment.SIGNAL_AGENT,
            false,
        )
        val protocolStore = AndroidSignalProtocolStore.fromSecrets(credentialStore, accountId, secrets)
        val network = Network(Network.Environment.PRODUCTION, SignalServiceEnvironment.SIGNAL_AGENT)
        val sleepTimer = UptimeSleepTimer()
        val canConnect = SignalWebSocket.CanConnect { true }

        val authFactory = websocketFactory(network, credentials, receiveStories = false, "secure-messenger-auth")
        val unauthFactory = websocketFactory(network, credentials = null, receiveStories = false, "secure-messenger-unauth")

        val authWebSocket = SignalWebSocket.AuthenticatedWebSocket(authFactory, canConnect, sleepTimer, 30_000L)
        val unauthWebSocket = SignalWebSocket.UnauthenticatedWebSocket(unauthFactory, canConnect, sleepTimer, 30_000L)
        val accountApi = AccountApi(authWebSocket)
        val groupsV2Operations = GroupsV2Operations(
            ClientZkOperations.create(configuration),
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
            PreKeyRepository.BatchHelper { block -> block.run() },
        )
        val messageSender = SignalServiceMessageSender(
            pushServiceSocket,
            protocolStore,
            SignalSessionLockImpl,
            messageApi,
            keysApi,
            Optional.empty(),
            Executors.newSingleThreadExecutor(),
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

        authWebSocket.registerKeepAliveToken(SignalWebSocket.FOREGROUND_KEEPALIVE)
        authWebSocket.connect()

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
        )
    }

    fun applyTorProxy(network: Network, proxy: ltechnologies.onionphone.securemessenger.core.model.ProxyConfig) {
        val host = ltechnologies.onionphone.securemessenger.core.proxy.SocksEndpointResolver
            .resolveReachableHost(proxy.host, proxy.port)
        network.setProxy(host, proxy.port)
    }

    private fun websocketFactory(
        network: Network,
        credentials: StaticCredentialsProvider?,
        receiveStories: Boolean,
        name: String,
    ): WebSocketFactory = WebSocketFactory {
        LibSignalChatConnection(name, network, credentials, receiveStories, noopHealthMonitor)
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
