package ltechnologies.onionphone.securemessenger.protocol.stub

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import ltechnologies.onionphone.securemessenger.core.model.AccountCredentials
import ltechnologies.onionphone.securemessenger.core.model.ConnectionResult
import ltechnologies.onionphone.securemessenger.core.model.ConnectionState
import ltechnologies.onionphone.securemessenger.core.model.Conversation
import ltechnologies.onionphone.securemessenger.core.model.Message
import ltechnologies.onionphone.securemessenger.core.model.ProtocolCapabilities
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.model.SanitizedText
import ltechnologies.onionphone.securemessenger.core.model.SendResult
import ltechnologies.onionphone.securemessenger.protocol.api.MessengerProtocol
import ltechnologies.onionphone.securemessenger.protocol.api.ProtocolNotEnabledException

abstract class DisabledMessengerProtocol(
    override val id: ProtocolId,
) : MessengerProtocol {
    override val capabilities: ProtocolCapabilities = ProtocolCapabilities()
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    override suspend fun connect(account: AccountCredentials, proxy: ProxyConfig): ConnectionResult =
        ConnectionResult.Failure(ProtocolNotEnabledException(id).message ?: "Not enabled")

    override fun observeConversations(): Flow<List<Conversation>> = flowOf(emptyList())

    override fun observeMessages(conversationId: String): Flow<List<Message>> = flowOf(emptyList())

    override suspend fun sendMessage(conversationId: String, body: SanitizedText, accountId: String?): SendResult =
        SendResult.Failure(ProtocolNotEnabledException(id).message ?: "Not enabled")

    override suspend fun disconnect(accountId: String?) {
        _connectionState.value = ConnectionState.DISCONNECTED
    }
}
