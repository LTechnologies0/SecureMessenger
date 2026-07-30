package ltechnologies.onionphone.securemessenger.protocol.telegram

import ltechnologies.onionphone.securemessenger.core.model.Attachment
import ltechnologies.onionphone.securemessenger.core.model.AttachmentState
import ltechnologies.onionphone.securemessenger.core.model.Contact
import ltechnologies.onionphone.securemessenger.core.model.Conversation
import ltechnologies.onionphone.securemessenger.core.model.DeliveryState
import ltechnologies.onionphone.securemessenger.core.model.Message
import ltechnologies.onionphone.securemessenger.core.model.MessageDirection
import ltechnologies.onionphone.securemessenger.core.model.MessageKind
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import org.drinkless.tdlib.TdApi
import org.json.JSONArray
import org.json.JSONObject

/** Maps TDLib API objects onto the unified [MessengerProtocol] domain model. */
object TdLibMapper {

    fun conversationId(accountId: String, chatId: Long): String =
        "${accountId}_$chatId"

    fun messageId(conversationId: String, tdMessageId: Long): String =
        "${conversationId}_$tdMessageId"

    fun chatIdFromConversation(conversationId: String): Long? =
        conversationId.substringAfterLast('_').toLongOrNull()

    /** UUID account ids contain hyphens; chat id follows the last underscore. */
    fun accountIdFromConversation(conversationId: String): String? {
        val sep = conversationId.lastIndexOf('_')
        if (sep <= 0) return null
        return conversationId.substring(0, sep)
    }

    fun toConversation(accountId: String, chat: TdApi.Chat): Conversation {
        val convId = conversationId(accountId, chat.id)
        val preview = chat.lastMessage?.let { messageBody(it) }
        val lastAt = chat.lastMessage?.date?.times(1000L) ?: 0L
        return Conversation(
            id = convId,
            protocol = ProtocolId.TELEGRAM,
            accountId = accountId,
            remoteId = chat.id.toString(),
            title = chat.title,
            lastMessagePreview = preview?.take(100),
            lastMessageAt = lastAt,
            unreadCount = chat.unreadCount,
        )
    }

    fun toContact(accountId: String, user: TdApi.User): Contact {
        val display = "${user.firstName} ${user.lastName}".trim().ifBlank {
            user.usernames?.editableUsername?.takeIf { it.isNotBlank() }
                ?: user.phoneNumber.takeIf { it.isNotBlank() }
                ?: user.id.toString()
        }
        val handle = user.usernames?.editableUsername?.takeIf { it.isNotBlank() }
            ?: user.usernames?.activeUsernames?.firstOrNull()?.takeIf { it.isNotBlank() }
        return Contact(
            id = "${accountId}_${user.id}",
            protocol = ProtocolId.TELEGRAM,
            accountId = accountId,
            remoteId = user.id.toString(),
            displayName = display,
            handle = handle?.let { "@$it" },
            phone = user.phoneNumber.takeIf { it.isNotBlank() },
            avatarLocalPath = user.profilePhoto?.small?.local?.path?.takeIf { it.isNotBlank() },
        )
    }

    fun toMessage(accountId: String, msg: TdApi.Message): Message {
        val body = messageBody(msg)
        val convId = conversationId(accountId, msg.chatId)
        val msgKey = messageId(convId, msg.id)
        val kind = messageKind(msg.content)
        val expire = when {
            msg.selfDestructType is TdApi.MessageSelfDestructTypeTimer ->
                (msg.selfDestructType as TdApi.MessageSelfDestructTypeTimer).selfDestructTime
            msg.selfDestructIn > 0 -> msg.selfDestructIn.toInt()
            else -> null
        }
        return Message(
            id = msgKey,
            conversationId = convId,
            protocol = ProtocolId.TELEGRAM,
            body = body,
            timestamp = msg.date * 1000L,
            direction = if (msg.isOutgoing) MessageDirection.OUTGOING else MessageDirection.INCOMING,
            deliveryState = deliveryState(msg),
            senderDisplayName = senderLabel(msg.senderId),
            attachments = attachmentsFromContent(msg.content, msgKey),
            kind = kind,
            payloadJson = payloadJson(msg.content),
            expireSeconds = expire,
        )
    }

    fun messageKind(content: TdApi.MessageContent): MessageKind = when (content) {
        is TdApi.MessageText -> MessageKind.TEXT
        is TdApi.MessagePhoto -> MessageKind.IMAGE
        is TdApi.MessageVideo -> MessageKind.VIDEO
        is TdApi.MessageDocument, is TdApi.MessageAudio -> MessageKind.FILE
        is TdApi.MessageAnimation -> MessageKind.GIF
        is TdApi.MessageSticker -> MessageKind.STICKER
        is TdApi.MessageVoiceNote, is TdApi.MessageVideoNote -> MessageKind.VOICE
        is TdApi.MessageLocation, is TdApi.MessageLiveLocation -> MessageKind.LOCATION
        is TdApi.MessagePoll -> MessageKind.POLL
        is TdApi.MessageContact -> MessageKind.CONTACT
        is TdApi.MessageChatChangeTitle,
        is TdApi.MessageChatAddMembers,
        is TdApi.MessageChatDeleteMember,
        is TdApi.MessageChatJoinByLink,
        is TdApi.MessagePinMessage,
        is TdApi.MessageScreenshotTaken,
        is TdApi.MessageCall,
        -> MessageKind.SYSTEM
        else -> MessageKind.UNKNOWN
    }

    fun payloadJson(content: TdApi.MessageContent): String? = when (content) {
        is TdApi.MessageLocation -> JSONObject().apply {
            put("latitude", content.location.latitude)
            put("longitude", content.location.longitude)
            put("horizontalAccuracy", content.location.horizontalAccuracy)
        }.toString()
        is TdApi.MessageLiveLocation -> JSONObject().apply {
            val loc = content.location.location
            put("latitude", loc.latitude)
            put("longitude", loc.longitude)
            put("horizontalAccuracy", loc.horizontalAccuracy)
            put("livePeriod", content.location.livePeriod)
            put("expiresIn", content.expiresIn)
        }.toString()
        is TdApi.MessagePoll -> JSONObject().apply {
            put("question", content.poll.question.text)
            put("anonymous", content.poll.isAnonymous)
            put("multipleAnswers", content.poll.allowsMultipleAnswers)
            put("totalVoterCount", content.poll.totalVoterCount)
            put("isClosed", content.poll.isClosed)
            put(
                "options",
                JSONArray().apply {
                    content.poll.options.forEach { opt ->
                        put(
                            JSONObject().apply {
                                put("text", opt.text.text)
                                put("voterCount", opt.voterCount)
                                put("isChosen", opt.isChosen)
                            },
                        )
                    }
                },
            )
        }.toString()
        is TdApi.MessageContact -> JSONObject().apply {
            put("firstName", content.contact.firstName)
            put("lastName", content.contact.lastName)
            put("phoneNumber", content.contact.phoneNumber)
            put("userId", content.contact.userId)
        }.toString()
        else -> null
    }

    fun attachmentsFromContent(content: TdApi.MessageContent, messageId: String): List<Attachment> =
        when (content) {
            is TdApi.MessagePhoto -> listOf(attachmentFromFile(
                messageId = messageId,
                suffix = "photo",
                file = largestPhotoFile(content.photo),
                mimeType = "image/jpeg",
                fileName = null,
            ))
            is TdApi.MessageVideo -> listOf(attachmentFromFile(
                messageId = messageId,
                suffix = "video",
                file = content.video.video,
                mimeType = content.video.mimeType.ifBlank { "video/mp4" },
                fileName = null,
            ))
            is TdApi.MessageDocument -> listOf(attachmentFromFile(
                messageId = messageId,
                suffix = "document",
                file = content.document.document,
                mimeType = content.document.mimeType.ifBlank { "application/octet-stream" },
                fileName = content.document.fileName,
            ))
            is TdApi.MessageVoiceNote -> listOf(attachmentFromFile(
                messageId = messageId,
                suffix = "voice",
                file = content.voiceNote.voice,
                mimeType = content.voiceNote.mimeType.ifBlank { "audio/ogg" },
                fileName = null,
            ))
            is TdApi.MessageSticker -> listOf(attachmentFromFile(
                messageId = messageId,
                suffix = "sticker",
                file = content.sticker.sticker,
                mimeType = "image/webp",
                fileName = null,
            ))
            is TdApi.MessageAnimation -> listOf(attachmentFromFile(
                messageId = messageId,
                suffix = "animation",
                file = content.animation.animation,
                mimeType = content.animation.mimeType.ifBlank { "video/mp4" },
                fileName = content.animation.fileName,
            ))
            else -> emptyList()
        }

    fun messageBody(msg: TdApi.Message): String = messageBody(msg.content)

    fun messageBody(content: TdApi.MessageContent): String = when (content) {
        is TdApi.MessageText -> content.text.text
        is TdApi.MessagePhoto -> content.caption.text.ifBlank { "[Photo]" }
        is TdApi.MessageVideo -> content.caption.text.ifBlank { "[Vidéo]" }
        is TdApi.MessageDocument -> content.caption.text.ifBlank { "[Document]" }
        is TdApi.MessageAudio -> content.caption.text.ifBlank { "[Audio]" }
        is TdApi.MessageVoiceNote -> "[Message vocal]"
        is TdApi.MessageVideoNote -> "[Vidéo ronde]"
        is TdApi.MessageSticker -> content.sticker.emoji.ifBlank { "[Sticker]" }
        is TdApi.MessageAnimation -> content.caption.text.ifBlank { "[GIF]" }
        is TdApi.MessageLocation -> "[Position]"
        is TdApi.MessageLiveLocation -> "[Position en direct]"
        is TdApi.MessageContact -> {
            val name = "${content.contact.firstName} ${content.contact.lastName}".trim()
            if (name.isBlank()) "[Contact]" else "[Contact] $name"
        }
        is TdApi.MessagePoll -> content.poll.question.text
        is TdApi.MessageGame -> content.game.title
        is TdApi.MessageInvoice -> content.productInfo.title
        is TdApi.MessageCall -> "[Appel]"
        is TdApi.MessageChatChangeTitle -> "Titre changé : ${content.title}"
        is TdApi.MessageChatAddMembers -> "[Membres ajoutés]"
        is TdApi.MessageChatDeleteMember -> "[Membre retiré]"
        is TdApi.MessageChatJoinByLink -> "[A rejoint via lien]"
        is TdApi.MessagePinMessage -> "[Message épinglé]"
        is TdApi.MessageScreenshotTaken -> "[Capture d'écran]"
        else -> "[Message]"
    }

    fun deliveryState(msg: TdApi.Message): DeliveryState = when (msg.sendingState) {
        is TdApi.MessageSendingStateFailed -> DeliveryState.FAILED
        is TdApi.MessageSendingStatePending -> DeliveryState.PENDING
        null -> if (msg.isOutgoing) DeliveryState.SENT else DeliveryState.DELIVERED
        else -> if (msg.isOutgoing) DeliveryState.DELIVERED else DeliveryState.DELIVERED
    }

    private fun senderLabel(sender: TdApi.MessageSender): String? = when (sender) {
        is TdApi.MessageSenderUser -> null
        is TdApi.MessageSenderChat -> "Chat ${sender.chatId}"
        else -> null
    }

    private fun largestPhotoFile(photo: TdApi.Photo): TdApi.File? =
        photo.sizes?.maxByOrNull { it.width * it.height }?.photo

    private fun attachmentFromFile(
        messageId: String,
        suffix: String,
        file: TdApi.File?,
        mimeType: String,
        fileName: String?,
    ): Attachment {
        val local = file?.local
        val state = when {
            file == null -> AttachmentState.FAILED
            local?.isDownloadingCompleted == true && !local.path.isNullOrBlank() -> AttachmentState.READY
            local?.isDownloadingActive == true -> AttachmentState.DOWNLOADING
            else -> AttachmentState.PENDING
        }
        return Attachment(
            id = "${messageId}_$suffix",
            mimeType = mimeType,
            fileName = fileName,
            localPath = local?.path?.takeIf { it.isNotBlank() },
            remoteRef = file?.id?.toString(),
            sizeBytes = file?.size ?: 0L,
            state = state,
        )
    }
}
