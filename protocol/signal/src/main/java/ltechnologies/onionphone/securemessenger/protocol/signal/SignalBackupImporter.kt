package ltechnologies.onionphone.securemessenger.protocol.signal

import android.util.Base64
import java.io.File
import java.io.FileInputStream
import ltechnologies.onionphone.securemessenger.core.model.Contact
import ltechnologies.onionphone.securemessenger.core.model.Conversation
import ltechnologies.onionphone.securemessenger.core.model.DeliveryState
import ltechnologies.onionphone.securemessenger.core.model.Message
import ltechnologies.onionphone.securemessenger.core.model.MessageDirection
import ltechnologies.onionphone.securemessenger.core.model.MessageKind
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.core.security.EncryptedCredentialStore
import ltechnologies.onionphone.securemessenger.data.MessengerRepository
import org.json.JSONObject
import org.signal.core.models.ServiceId
import org.signal.core.models.backup.MessageBackupKey
import org.signal.libsignal.messagebackup.BackupJsonExporter
import timber.log.Timber

/**
 * Imports a downloaded link-and-sync transfer archive into local conversations/messages
 * via libsignal [BackupJsonExporter] (decrypt → JSONL frames → Room).
 */
internal object SignalBackupImporter {
    private const val MAX_MESSAGES = 50_000

    suspend fun importIfNeeded(
        accountId: String,
        session: SignalSessionContext,
        credentialStore: EncryptedCredentialStore,
        repository: MessengerRepository,
    ): ImportStats? {
        if (credentialStore.get(accountId, SignalCredentialKeys.LINK_SYNC_IMPORTED) == "1") {
            Timber.d("Link-and-sync already imported for %s", accountId)
            return null
        }
        val path = credentialStore.get(accountId, SignalCredentialKeys.LINK_SYNC_BACKUP_PATH)
            ?: return null
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) return null

        val encodedKey = credentialStore.get(accountId, SignalCredentialKeys.EPHEMERAL_BACKUP_KEY)
            ?: return null

        return runCatching {
            importFile(accountId, session, encodedKey, file, repository).also { stats ->
                credentialStore.put(accountId, SignalCredentialKeys.LINK_SYNC_IMPORTED, "1")
                Timber.i(
                    "Link-and-sync import done chats=%d messages=%d contacts=%d",
                    stats.chats,
                    stats.messages,
                    stats.contacts,
                )
            }
        }.onFailure { Timber.e(it, "Link-and-sync import failed") }
            .getOrNull()
    }

    private suspend fun importFile(
        accountId: String,
        session: SignalSessionContext,
        encodedEphemeralKey: String,
        file: File,
        repository: MessengerRepository,
    ): ImportStats {
        val ephemeralBytes = Base64.decode(encodedEphemeralKey, Base64.NO_WRAP)
        val modelsKey = MessageBackupKey(ephemeralBytes)
        val material = modelsKey.deriveBackupSecrets(session.aci, null)

        val recipientRemote = HashMap<Long, RecipientInfo>()
        val chatRemote = HashMap<Long, String>() // chatId → remoteId
        val chatTitle = HashMap<Long, String>()
        var contacts = 0
        var chats = 0
        var messages = 0
        val pendingMessages = ArrayList<Message>(256)
        val pendingContacts = ArrayList<Contact>(64)

        SignalEncryptedBackupStream.openForLinkAndSync(material, file.length()) {
            FileInputStream(file)
        }.use { stream ->
            val (exporter, _) = BackupJsonExporter.start(stream.headerBytes, /* validate = */ false)
            exporter.use { exp ->
                for (frame in stream.frames()) {
                    val results = exp.exportFrames(frame)
                    for (result in results) {
                        val line = result.line ?: continue
                        val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
                        when {
                            obj.has("recipient") -> {
                                val info = parseRecipient(obj.getJSONObject("recipient")) ?: continue
                                recipientRemote[info.id] = info
                                if (info.kind == RecipientKind.CONTACT) {
                                    val remoteId = info.remoteId ?: continue
                                    pendingContacts.add(
                                        Contact(
                                            id = "${accountId}_$remoteId",
                                            protocol = ProtocolId.SIGNAL,
                                            accountId = accountId,
                                            remoteId = remoteId,
                                            displayName = info.displayName,
                                            handle = info.aci,
                                            phone = info.e164,
                                        ),
                                    )
                                    contacts++
                                }
                            }
                            obj.has("chat") -> {
                                val chat = obj.getJSONObject("chat")
                                val chatId = chat.optLong("id", -1L)
                                val recipientId = chat.optLong("recipientId", -1L)
                                if (chatId < 0 || recipientId < 0) continue
                                val info = recipientRemote[recipientId] ?: continue
                                if (info.remoteId == null) continue
                                chatRemote[chatId] = info.remoteId
                                chatTitle[chatId] = info.displayName
                                upsertConversation(
                                    accountId,
                                    info.remoteId,
                                    info.displayName,
                                    repository = repository,
                                )
                                chats++
                            }
                            obj.has("chatItem") -> {
                                if (messages >= MAX_MESSAGES) continue
                                val item = obj.getJSONObject("chatItem")
                                val msg = parseChatItem(accountId, item, chatRemote, chatTitle, recipientRemote)
                                    ?: continue
                                if (repository.getMessage(msg.id) == null) {
                                    pendingMessages.add(msg)
                                    messages++
                                    if (pendingMessages.size >= 200) {
                                        repository.upsertMessages(pendingMessages.toList())
                                        pendingMessages.clear()
                                    }
                                }
                            }
                        }
                    }
                }
                exp.finishExport()
            }
        }
        if (pendingMessages.isNotEmpty()) {
            repository.upsertMessages(pendingMessages)
        }
        if (pendingContacts.isNotEmpty()) {
            repository.upsertContacts(pendingContacts)
        }
        return ImportStats(contacts = contacts, chats = chats, messages = messages)
    }

    private suspend fun upsertConversation(
        accountId: String,
        remoteId: String,
        title: String,
        repository: MessengerRepository,
    ) {
        val conversationId = signalConversationId(accountId, remoteId)
        val existing = repository.getConversation(conversationId)
        repository.upsertConversation(
            Conversation(
                id = conversationId,
                protocol = ProtocolId.SIGNAL,
                accountId = accountId,
                remoteId = remoteId,
                title = title.ifBlank { existing?.title ?: remoteId },
                lastMessagePreview = existing?.lastMessagePreview,
                lastMessageAt = existing?.lastMessageAt ?: 0L,
                unreadCount = existing?.unreadCount ?: 0,
            ),
        )
    }

    private fun parseRecipient(recipient: JSONObject): RecipientInfo? {
        val id = recipient.optLong("id", -1L)
        if (id < 0) return null
        when {
            recipient.has("self") || recipient.has("Self_") ->
                return RecipientInfo(id, RecipientKind.SELF, null, "Moi", null, null)
            recipient.has("releaseNotes") || recipient.has("ReleaseNotes") ->
                return RecipientInfo(id, RecipientKind.OTHER, null, "Release Notes", null, null)
            recipient.has("contact") -> {
                val c = recipient.getJSONObject("contact")
                val aci = decodeAci(c.optString("aci").takeIf { it.isNotBlank() })
                val e164 = c.optString("e164").takeIf { it.isNotBlank() }
                    ?: c.optString("e164Number").takeIf { it.isNotBlank() }
                val given = c.optString("profileGivenName").ifBlank {
                    c.optString("profile_given_name")
                }
                val family = c.optString("profileFamilyName").ifBlank {
                    c.optString("profile_family_name")
                }
                val name = listOf(given, family).filter { it.isNotBlank() }.joinToString(" ")
                    .ifBlank { e164 ?: aci ?: "Contact" }
                val remote = aci ?: e164 ?: return null
                return RecipientInfo(id, RecipientKind.CONTACT, remote, name, aci, e164)
            }
            recipient.has("group") -> {
                val g = recipient.getJSONObject("group")
                val masterKeyB64 = g.optString("masterKey").ifBlank { g.optString("master_key") }
                if (masterKeyB64.isBlank()) return null
                val masterKey = runCatching {
                    Base64.decode(masterKeyB64, Base64.DEFAULT)
                }.getOrNull() ?: return null
                if (masterKey.size != 32) return null
                val remote = "gv2:" + Base64.encodeToString(masterKey, Base64.NO_WRAP)
                val title = g.optString("title").ifBlank { g.optString("name") }.ifBlank { "Groupe" }
                return RecipientInfo(id, RecipientKind.GROUP, remote, title, null, null)
            }
            recipient.has("distributionList") || recipient.has("DistributionList") ->
                return RecipientInfo(id, RecipientKind.OTHER, "story:my", "My Story", null, null)
            else -> return RecipientInfo(id, RecipientKind.OTHER, null, "Unknown", null, null)
        }
    }

    private fun parseChatItem(
        accountId: String,
        item: JSONObject,
        chatRemote: Map<Long, String>,
        chatTitle: Map<Long, String>,
        recipients: Map<Long, RecipientInfo>,
    ): Message? {
        val chatId = item.optLong("chatId", -1L).takeIf { it >= 0 }
            ?: item.optLong("chat_id", -1L).takeIf { it >= 0 }
            ?: return null
        val remoteId = chatRemote[chatId] ?: return null
        val authorId = item.optLong("authorId", -1L).takeIf { it >= 0 }
            ?: item.optLong("author_id", -1L)
        val dateSent = item.optLong("dateSent", 0L).takeIf { it > 0 }
            ?: item.optLong("date_sent", 0L)
        if (dateSent <= 0L) return null

        val direction = when {
            item.has("outgoing") -> MessageDirection.OUTGOING
            item.has("incoming") -> MessageDirection.INCOMING
            else -> MessageDirection.INCOMING
        }
        val (body, kind) = extractBodyAndKind(item)
        val conversationId = signalConversationId(accountId, remoteId)
        val author = recipients[authorId]
        val messageId = "${conversationId}_${dateSent}_$authorId"
        return Message(
            id = messageId,
            conversationId = conversationId,
            protocol = ProtocolId.SIGNAL,
            body = body,
            timestamp = dateSent,
            direction = direction,
            deliveryState = if (direction == MessageDirection.OUTGOING) {
                DeliveryState.SENT
            } else {
                DeliveryState.DELIVERED
            },
            senderDisplayName = author?.displayName ?: chatTitle[chatId],
            kind = kind,
            payloadJson = JSONObject()
                .put("source", "link_sync")
                .put("chatId", chatId)
                .put("authorId", authorId)
                .toString(),
        )
    }

    private fun extractBodyAndKind(item: JSONObject): Pair<String, MessageKind> = when {
        item.has("standardMessage") -> {
            val std = item.getJSONObject("standardMessage")
            val text = std.optJSONObject("text")?.optString("body").orEmpty()
            val hasAttach = (std.optJSONArray("attachments")?.length() ?: 0) > 0
            when {
                text.isNotBlank() -> text to MessageKind.TEXT
                hasAttach -> "📎" to MessageKind.FILE
                else -> "" to MessageKind.TEXT
            }
        }
        item.has("stickerMessage") -> {
            val emoji = item.getJSONObject("stickerMessage")
                .optJSONObject("sticker")
                ?.optString("emoji")
                ?.ifBlank { "⭐" }
                ?: "⭐"
            emoji to MessageKind.STICKER
        }
        item.has("contactMessage") -> "Contact" to MessageKind.CONTACT
        item.has("updateMessage") -> {
            val body = item.optJSONObject("updateMessage")?.toString()?.take(120) ?: "Mise à jour"
            body to MessageKind.SYSTEM
        }
        item.has("remoteDeletedMessage") -> "Message supprimé" to MessageKind.SYSTEM
        item.has("poll") -> {
            val q = item.getJSONObject("poll").optString("question").ifBlank { "Sondage" }
            q to MessageKind.POLL
        }
        item.has("viewOnceMessage") -> "Message vue unique" to MessageKind.SYSTEM
        item.has("directStoryReplyMessage") -> "Réponse à une story" to MessageKind.STORY
        else -> "(message)" to MessageKind.UNKNOWN
    }

    private fun decodeAci(encoded: String?): String? {
        if (encoded.isNullOrBlank()) return null
        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return null
        return runCatching { ServiceId.ACI.parseOrThrow(bytes).toString() }.getOrNull()
    }

    data class ImportStats(val contacts: Int, val chats: Int, val messages: Int)

    private enum class RecipientKind { SELF, CONTACT, GROUP, OTHER }

    private data class RecipientInfo(
        val id: Long,
        val kind: RecipientKind,
        val remoteId: String?,
        val displayName: String,
        val aci: String?,
        val e164: String?,
    )
}
