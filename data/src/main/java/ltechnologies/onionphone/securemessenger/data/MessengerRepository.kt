package ltechnologies.onionphone.securemessenger.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ltechnologies.onionphone.securemessenger.core.model.Account
import ltechnologies.onionphone.securemessenger.core.model.Conversation
import ltechnologies.onionphone.securemessenger.core.model.Message
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.model.TorProvider
import ltechnologies.onionphone.securemessenger.data.db.ProxySettingsEntity
import ltechnologies.onionphone.securemessenger.data.db.toDomain
import ltechnologies.onionphone.securemessenger.data.db.toEntity

@Singleton
class MessengerRepository @Inject constructor(
    private val database: EncryptedMessengerDatabase,
) {
    private val accountDao get() = database.get().accountDao()
    private val conversationDao get() = database.get().conversationDao()
    private val messageDao get() = database.get().messageDao()
    private val proxySettingsDao get() = database.get().proxySettingsDao()

    fun observeAccounts(): Flow<List<Account>> =
        accountDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeConversations(): Flow<List<Conversation>> =
        conversationDao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun listConversationsForAccount(accountId: String): List<Conversation> =
        conversationDao.listForAccount(accountId).map { it.toDomain() }

    fun observeMessages(conversationId: String): Flow<List<Message>> =
        messageDao.observeForConversation(conversationId).map { list -> list.map { it.toDomain() } }

    fun observeProxySettings(): Flow<ProxyConfig?> =
        proxySettingsDao.observe().map { entity ->
            entity?.let {
                ProxyConfig(
                    host = it.host,
                    port = it.port,
                    username = it.username,
                    torRequired = true,
                    remoteDns = it.remoteDns,
                    torProvider = runCatching { TorProvider.valueOf(it.torProvider) }
                        .getOrDefault(TorProvider.CUSTOM),
                )
            }
        }

    suspend fun upsertAccount(account: Account) {
        accountDao.upsert(account.toEntity())
    }

    suspend fun upsertConversation(conversation: Conversation) {
        conversationDao.upsert(conversation.toEntity())
    }

    suspend fun upsertConversations(conversations: List<Conversation>) {
        conversationDao.upsertAll(conversations.map { it.toEntity() })
    }

    suspend fun upsertMessage(message: Message) {
        messageDao.upsert(message.toEntity())
    }

    suspend fun upsertMessages(messages: List<Message>) {
        if (messages.isEmpty()) return
        messageDao.upsertAll(messages.map { it.toEntity() })
    }

    suspend fun deleteMessages(ids: List<String>) {
        if (ids.isEmpty()) return
        messageDao.deleteByIds(ids)
    }

    suspend fun saveProxySettings(config: ProxyConfig) {
        val torOnly = config.copy(torRequired = true)
        proxySettingsDao.upsert(
            ProxySettingsEntity(
                host = torOnly.host,
                port = torOnly.port,
                torRequired = true,
                remoteDns = torOnly.remoteDns,
                username = torOnly.username,
                torProvider = torOnly.torProvider.name,
            ),
        )
    }

    suspend fun deleteAccount(id: String) {
        accountDao.delete(id)
    }
}
