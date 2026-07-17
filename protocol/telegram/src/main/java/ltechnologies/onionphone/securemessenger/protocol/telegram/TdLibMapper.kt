package ltechnologies.onionphone.securemessenger.protocol.telegram

import ltechnologies.onionphone.securemessenger.core.model.Attachment
import ltechnologies.onionphone.securemessenger.core.model.AttachmentState
import ltechnologies.onionphone.securemessenger.core.model.Conversation
import ltechnologies.onionphone.securemessenger.core.model.DeliveryState
import ltechnologies.onionphone.securemessenger.core.model.Message
import ltechnologies.onionphone.securemessenger.core.model.MessageDirection
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import org.drinkless.tdlib.TdApi

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

    fun toMessage(accountId: String, msg: TdApi.Message): Message {
        val body = messageBody(msg)
        val convId = conversationId(accountId, msg.chatId)
        val msgKey = messageId(convId, msg.id)
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
        )
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
        is TdApi.MessageSticker -> "[Sticker]"
        is TdApi.MessageAnimation -> content.caption.text.ifBlank { "[GIF]" }
        is TdApi.MessageLocation -> "[Position]"
        is TdApi.MessageContact -> "[Contact]"
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
