package ltechnologies.onionphone.securemessenger.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import ltechnologies.onionphone.securemessenger.core.model.Attachment
import ltechnologies.onionphone.securemessenger.core.model.ConnectionState
import ltechnologies.onionphone.securemessenger.core.model.DeliveryState
import ltechnologies.onionphone.securemessenger.core.model.MessageDirection
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val protocol: String,
    val displayName: String,
    val connectionState: String,
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val protocol: String,
    val accountId: String,
    val remoteId: String,
    val title: String,
    val lastMessagePreview: String?,
    val lastMessageAt: Long,
    val unreadCount: Int,
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val protocol: String,
    val body: String,
    val timestamp: Long,
    val direction: String,
    val deliveryState: String,
    val senderDisplayName: String?,
    val attachmentsJson: String = "[]",
    val kind: String = "TEXT",
    val payloadJson: String? = null,
    val expireSeconds: Int? = null,
)

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val id: String,
    val protocol: String,
    val accountId: String,
    val remoteId: String,
    val displayName: String,
    val handle: String? = null,
    val phone: String? = null,
    val avatarLocalPath: String? = null,
)

@Entity(tableName = "proxy_settings")
data class ProxySettingsEntity(
    @PrimaryKey val id: Int = 1,
    val host: String,
    val port: Int,
    val torRequired: Boolean,
    val remoteDns: Boolean,
    val username: String?,
    val torProvider: String = "CUSTOM",
)

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY displayName")
    fun observeAll(): Flow<List<AccountEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY lastMessageAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE accountId = :accountId")
    suspend fun listForAccount(accountId: String): List<ConversationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(conversations: List<ConversationEntity>)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun observeForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: String)
}

@Dao
interface ProxySettingsDao {
    @Query("SELECT * FROM proxy_settings WHERE id = 1")
    fun observe(): Flow<ProxySettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: ProxySettingsEntity)
}

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts WHERE accountId = :accountId ORDER BY displayName COLLATE NOCASE ASC")
    fun observeForAccount(accountId: String): Flow<List<ContactEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(contacts: List<ContactEntity>)

    @Query("DELETE FROM contacts WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)
}

@Database(
    entities = [
        AccountEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        ProxySettingsEntity::class,
        ContactEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class MessengerDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun proxySettingsDao(): ProxySettingsDao
    abstract fun contactDao(): ContactDao
}

fun AccountEntity.toDomain() = ltechnologies.onionphone.securemessenger.core.model.Account(
    id = id,
    protocol = ProtocolId.valueOf(protocol),
    displayName = displayName,
    connectionState = ConnectionState.valueOf(connectionState),
)

fun ConversationEntity.toDomain() = ltechnologies.onionphone.securemessenger.core.model.Conversation(
    id = id,
    protocol = ProtocolId.valueOf(protocol),
    accountId = accountId,
    remoteId = remoteId,
    title = title,
    lastMessagePreview = lastMessagePreview,
    lastMessageAt = lastMessageAt,
    unreadCount = unreadCount,
)

fun MessageEntity.toDomain() = ltechnologies.onionphone.securemessenger.core.model.Message(
    id = id,
    conversationId = conversationId,
    protocol = ProtocolId.valueOf(protocol),
    body = body,
    timestamp = timestamp,
    direction = MessageDirection.valueOf(direction),
    deliveryState = DeliveryState.valueOf(deliveryState),
    senderDisplayName = senderDisplayName,
    attachments = AttachmentConverters.toAttachments(attachmentsJson),
    kind = runCatching {
        ltechnologies.onionphone.securemessenger.core.model.MessageKind.valueOf(kind)
    }.getOrDefault(ltechnologies.onionphone.securemessenger.core.model.MessageKind.TEXT),
    payloadJson = payloadJson,
    expireSeconds = expireSeconds,
)

fun ContactEntity.toDomain() = ltechnologies.onionphone.securemessenger.core.model.Contact(
    id = id,
    protocol = ProtocolId.valueOf(protocol),
    accountId = accountId,
    remoteId = remoteId,
    displayName = displayName,
    handle = handle,
    phone = phone,
    avatarLocalPath = avatarLocalPath,
)

fun ltechnologies.onionphone.securemessenger.core.model.Account.toEntity() = AccountEntity(
    id = id,
    protocol = protocol.name,
    displayName = displayName,
    connectionState = connectionState.name,
)

fun ltechnologies.onionphone.securemessenger.core.model.Conversation.toEntity() = ConversationEntity(
    id = id,
    protocol = protocol.name,
    accountId = accountId,
    remoteId = remoteId,
    title = title,
    lastMessagePreview = lastMessagePreview,
    lastMessageAt = lastMessageAt,
    unreadCount = unreadCount,
)

fun ltechnologies.onionphone.securemessenger.core.model.Message.toEntity() = MessageEntity(
    id = id,
    conversationId = conversationId,
    protocol = protocol.name,
    body = body,
    timestamp = timestamp,
    direction = direction.name,
    deliveryState = deliveryState.name,
    senderDisplayName = senderDisplayName,
    attachmentsJson = AttachmentConverters.fromAttachments(attachments),
    kind = kind.name,
    payloadJson = payloadJson,
    expireSeconds = expireSeconds,
)

fun ltechnologies.onionphone.securemessenger.core.model.Contact.toEntity() = ContactEntity(
    id = id,
    protocol = protocol.name,
    accountId = accountId,
    remoteId = remoteId,
    displayName = displayName,
    handle = handle,
    phone = phone,
    avatarLocalPath = avatarLocalPath,
)
