package ltechnologies.onionphone.securemessenger.protocol.api

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import ltechnologies.onionphone.securemessenger.core.model.AccountCredentials
import ltechnologies.onionphone.securemessenger.core.model.AuthStep
import ltechnologies.onionphone.securemessenger.core.model.ConnectionResult
import ltechnologies.onionphone.securemessenger.core.model.ConnectionState
import ltechnologies.onionphone.securemessenger.core.model.Conversation
import ltechnologies.onionphone.securemessenger.core.model.FeatureFlags
import ltechnologies.onionphone.securemessenger.core.model.Message
import ltechnologies.onionphone.securemessenger.core.model.ProtocolCapabilities
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.model.SanitizedText
import ltechnologies.onionphone.securemessenger.core.model.SendResult
import ltechnologies.onionphone.securemessenger.core.proxy.ProxyManager

/**
 * Single entry point for the UI and foreground service to talk to every enabled messenger SDK.
 */
@Singleton
class UnifiedMessengerClient @Inject constructor(
    private val registry: ProtocolRegistry,
    private val proxyManager: ProxyManager,
) {
    fun enabledProtocols(): Set<ProtocolId> = FeatureFlags.enabled

    fun protocol(id: ProtocolId): MessengerProtocol? = registry.get(id)

    fun capabilities(id: ProtocolId): ProtocolCapabilities? = registry.get(id)?.capabilities

    fun connectionState(id: ProtocolId): StateFlow<ConnectionState>? = registry.get(id)?.connectionState

    suspend fun connect(account: AccountCredentials): ConnectionResult {
        val impl = registry.get(account.protocol)
            ?: return ConnectionResult.Failure("Protocol not registered")
        if (!proxyManager.ensureProxyReady()) {
            return ConnectionResult.Failure(
                "Tor requis : démarrez Orbot ou InviZible (Paramètres → Proxy), puis réessayez.",
            )
        }
        return impl.connect(account, proxyManager.currentConfig())
    }

    suspend fun continueAuth(protocol: ProtocolId, fields: Map<String, String>): ConnectionResult {
        val impl = registry.get(protocol) ?: return ConnectionResult.Failure("Protocol not registered")
        return impl.continueAuthentication(fields)
    }

    suspend fun pendingAuth(protocol: ProtocolId): AuthStep? = registry.get(protocol)?.pendingAuthStep()

    fun observeConversations(protocol: ProtocolId? = null): Flow<List<Conversation>> {
        if (protocol != null) {
            return registry.get(protocol)?.observeConversations()
                ?: kotlinx.coroutines.flow.flowOf(emptyList())
        }
        return registry.get(ProtocolId.XMPP)?.observeConversations()
            ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }

    fun observeMessages(conversationId: String, protocol: ProtocolId): Flow<List<Message>> =
        registry.get(protocol)?.observeMessages(conversationId)
            ?: kotlinx.coroutines.flow.flowOf(emptyList())

    suspend fun sendMessage(protocol: ProtocolId, conversationId: String, body: SanitizedText): SendResult {
        val impl = registry.get(protocol) ?: return SendResult.Failure("Protocol not registered")
        return impl.sendMessage(conversationId, body)
    }

    suspend fun startConversation(
        protocol: ProtocolId,
        remoteId: String,
        initialMessage: SanitizedText? = null,
    ): SendResult {
        val impl = registry.get(protocol) ?: return SendResult.Failure("Protocol not registered")
        return impl.startConversation(remoteId, initialMessage)
    }

    suspend fun disconnect(protocol: ProtocolId) {
        registry.get(protocol)?.disconnect()
    }

    suspend fun disconnectAll() {
        FeatureFlags.enabled.forEach { id -> registry.get(id)?.disconnect() }
    }
}

interface ProtocolRegistry {
    fun get(id: ProtocolId): MessengerProtocol?
    fun all(): List<MessengerProtocol>
}
