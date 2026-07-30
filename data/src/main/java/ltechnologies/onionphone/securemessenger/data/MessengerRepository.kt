package ltechnologies.onionphone.securemessenger.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import ltechnologies.onionphone.securemessenger.core.model.Account
import ltechnologies.onionphone.securemessenger.core.model.Contact
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
    /** Opens SQLCipher only when the Flow is collected (after app unlock). */
    fun observeAccounts(): Flow<List<Account>> = flow {
        emitAll(database.get().accountDao().observeAll().map { list -> list.map { it.toDomain() } })
    }

    fun observeConversations(): Flow<List<Conversation>> = flow {
        emitAll(database.get().conversationDao().observeAll().map { list -> list.map { it.toDomain() } })
    }

    suspend fun listConversationsForAccount(accountId: String): List<Conversation> =
        database.get().conversationDao().listForAccount(accountId).map { it.toDomain() }

    fun observeMessages(conversationId: String): Flow<List<Message>> = flow {
        emitAll(
            database.get().messageDao().observeForConversation(conversationId)
                .map { list -> list.map { it.toDomain() } },
        )
    }

    fun observeContacts(accountId: String): Flow<List<Contact>> = flow {
        emitAll(
            database.get().contactDao().observeForAccount(accountId)
                .map { list -> list.map { it.toDomain() } },
        )
    }

    fun observeProxySettings(): Flow<ProxyConfig?> = flow {
        emitAll(
            database.get().proxySettingsDao().observe().map { entity ->
                entity?.let {
                    ProxyConfig(
                        host = it.host,
                        port = it.port,
                        username = it.username,
                        torRequired = it.torRequired,
                        remoteDns = it.remoteDns,
                        torProvider = TorProvider.fromStored(it.torProvider),
                    )
                }
            },
        )
    }

    suspend fun upsertAccount(account: Account) {
        database.get().accountDao().upsert(account.toEntity())
    }

    suspend fun upsertConversation(conversation: Conversation) {
        database.get().conversationDao().upsert(conversation.toEntity())
    }

    suspend fun upsertConversations(conversations: List<Conversation>) {
        database.get().conversationDao().upsertAll(conversations.map { it.toEntity() })
    }

    suspend fun upsertMessage(message: Message) {
        database.get().messageDao().upsert(message.toEntity())
    }

    suspend fun upsertMessages(messages: List<Message>) {
        if (messages.isEmpty()) return
        database.get().messageDao().upsertAll(messages.map { it.toEntity() })
    }

    suspend fun replaceContacts(accountId: String, contacts: List<Contact>) {
        val dao = database.get().contactDao()
        dao.deleteForAccount(accountId)
        if (contacts.isNotEmpty()) {
            dao.upsertAll(contacts.map { it.toEntity() })
        }
    }

    suspend fun deleteMessages(ids: List<String>) {
        if (ids.isEmpty()) return
        database.get().messageDao().deleteByIds(ids)
    }

    suspend fun saveProxySettings(config: ProxyConfig) {
        database.get().proxySettingsDao().upsert(
            ProxySettingsEntity(
                host = config.host,
                port = config.port,
                torRequired = config.torRequired,
                remoteDns = config.remoteDns,
                username = config.username,
                torProvider = config.torProvider.name,
            ),
        )
    }

    suspend fun deleteAccount(id: String) {
        database.get().accountDao().delete(id)
        database.get().contactDao().deleteForAccount(id)
    }

    /** Flat export snapshot for backup JSON. */
    suspend fun exportSnapshot(accountId: String): Pair<List<Conversation>, List<Message>> {
        val convs = listConversationsForAccount(accountId)
        val messages = convs.flatMap { conv ->
            observeMessages(conv.id).first()
        }
        return convs to messages
    }
}
