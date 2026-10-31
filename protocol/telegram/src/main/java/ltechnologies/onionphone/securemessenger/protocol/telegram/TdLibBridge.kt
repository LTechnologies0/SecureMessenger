package ltechnologies.onionphone.securemessenger.protocol.telegram

import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import timber.log.Timber

interface TdLibClient {
    fun send(request: TdApi.Function<*>, handler: (TdApi.Object?) -> Unit = {})
    fun setUpdateHandler(handler: (TdApi.Object) -> Unit)
    fun close()
}

class TdLibNativeClient : TdLibClient {
    private var client: Client? = null
    private var updateHandler: ((TdApi.Object) -> Unit)? = null

    init {
        System.loadLibrary("tdjni")
        client = Client.create(
            { obj -> updateHandler?.invoke(obj) },
            { e -> Timber.e(e, "TDLib fatal") },
            { e -> Timber.w(e, "TDLib error") },
        )
    }

    override fun send(request: TdApi.Function<*>, handler: (TdApi.Object?) -> Unit) {
        client?.send(request) { result ->
            if (result is TdApi.Error) {
                Timber.w("TDLib error ${result.code}: ${result.message}")
            }
            handler(result)
        }
    }

    override fun setUpdateHandler(handler: (TdApi.Object) -> Unit) {
        updateHandler = handler
    }

    override fun close() {
        try {
            client?.send(TdApi.Close(), {})
        } catch (_: Exception) {
        }
        client = null
        updateHandler = null
    }
}

class TdLibNotAvailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

object TdLibClientFactory {
    fun create(): TdLibClient = try {
        TdLibNativeClient()
    } catch (e: UnsatisfiedLinkError) {
        Timber.w(e, "libtdjni.so missing")
        TdLibStubClient()
    }
}

class TdLibStubClient : TdLibClient {
    override fun send(request: TdApi.Function<*>, handler: (TdApi.Object?) -> Unit) {
        handler(null)
    }

    override fun setUpdateHandler(handler: (TdApi.Object) -> Unit) = Unit

    override fun close() = Unit
}

/** TDLib [TdApi.SendMessage] outcome — message id is required for optimistic UI reconciliation. */
sealed class TdSendResult {
    data class Ok(val message: TdApi.Message) : TdSendResult()
    data class Err(val reason: String) : TdSendResult()
}

/** Optional [TdApi.SendMessage] flags (reply / silent / schedule / forum topic). */
data class TelegramSendOptions(
    val replyToMessageId: Long? = null,
    val silent: Boolean = false,
    val scheduleAtUnixSec: Int? = null,
    val forumTopicId: Int? = null,
    val protectContent: Boolean = false,
)

data class TelegramChatFolder(
    val id: Int,
    val title: String,
)

data class TelegramForumTopic(
    val id: Int,
    val name: String,
    val isGeneral: Boolean,
    val isClosed: Boolean,
    val unreadCount: Int,
)

data class TelegramSearchHit(
    val conversationId: String,
    val messageId: String,
    val chatId: Long,
    val tdMessageId: Long,
    val preview: String,
    val timestampMs: Long,
)

data class TelegramCallbackAnswer(
    val text: String,
    val showAlert: Boolean,
    val url: String?,
)

class TdLibFacade(private val client: TdLibClient) {
    private var authState: TdApi.AuthorizationState? = null

    fun onUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> authState = update.authorizationState
            else -> Unit
        }
    }

    fun authorizationState(): TdApi.AuthorizationState? = authState

    fun disableProxy() {
        client.send(TdApi.DisableProxy())
    }

    /**
     * Adds [host]:[port] SOCKS5 and enables it, then removes every *other* proxy.
     * Add-first avoids a clearnet window on live (AuthorizationStateReady) clients
     * that clear-then-add would open between RemoveProxy and AddProxy.
     */
    suspend fun configureProxy(host: String, port: Int, username: String?, password: String?): Boolean {
        val existing = listProxies() ?: return false
        val addedId = suspendCancellableCoroutine<Int?> { cont ->
            client.send(
                TdApi.AddProxy().apply {
                    proxy = TdApi.Proxy(
                        host,
                        port,
                        TdApi.ProxyTypeSocks5(username.orEmpty(), password.orEmpty()),
                    )
                    enable = true
                    comment = "SecureMessenger"
                },
            ) { result ->
                when (result) {
                    is TdApi.AddedProxy -> {
                        Timber.i("TDLib SOCKS5 enabled ${result.proxy.server}:${result.proxy.port}")
                        if (cont.isActive) cont.resume(result.id)
                    }
                    is TdApi.Proxy -> {
                        Timber.i("TDLib SOCKS5 enabled ${result.server}:${result.port}")
                        // Older TDLib shapes — treat as success without id for cleanup.
                        if (cont.isActive) cont.resume(-1)
                    }
                    is TdApi.Error -> {
                        Timber.w("AddProxy error ${result.code}: ${result.message} ($host:$port)")
                        if (cont.isActive) cont.resume(null)
                    }
                    else -> if (cont.isActive) cont.resume(null)
                }
            }
        } ?: return false
        for (proxy in existing) {
            if (addedId >= 0 && proxy.id == addedId) continue
            val removed = suspendCancellableCoroutine { cont ->
                client.send(TdApi.RemoveProxy(proxy.id)) { result ->
                    when (result) {
                        is TdApi.Ok -> if (cont.isActive) cont.resume(true)
                        is TdApi.Error -> {
                            Timber.w("RemoveProxy error ${result.code}: ${result.message}")
                            if (cont.isActive) cont.resume(false)
                        }
                        else -> if (cont.isActive) cont.resume(false)
                    }
                }
            }
            if (!removed) {
                Timber.w("Failed to remove stale TDLib proxy id=${proxy.id}")
            }
        }
        return true
    }

    /** @return null when [TdApi.GetProxies] fails (do not AddProxy blindly). */
    private suspend fun listProxies(): Array<TdApi.AddedProxy>? =
        suspendCancellableCoroutine { cont ->
            client.send(TdApi.GetProxies()) { result ->
                when (result) {
                    is TdApi.AddedProxies -> {
                        if (cont.isActive) cont.resume(result.proxies ?: emptyArray())
                    }
                    is TdApi.Error -> {
                        Timber.w("GetProxies error ${result.code}: ${result.message}")
                        if (cont.isActive) cont.resume(null)
                    }
                    else -> if (cont.isActive) cont.resume(null)
                }
            }
        }

    @Deprecated(
        "Use suspend configureProxy() and await the result before TDLib auth steps",
        ReplaceWith("configureProxy(host, port, username, password)"),
    )
    fun configureProxyFireAndForget(host: String, port: Int, username: String?, password: String?) {
        client.send(
            TdApi.AddProxy().apply {
                proxy = TdApi.Proxy(
                    host,
                    port,
                    TdApi.ProxyTypeSocks5(username.orEmpty(), password.orEmpty()),
                )
                enable = true
                comment = "SecureMessenger"
            },
        )
    }

    /**
     * Sends [TdApi.SetTdlibParameters]. Callers must enable SOCKS with [configureProxy] first:
     * TDLib starts networking when this request is accepted.
     * [TdApi.SetTdlibParameters.useSecretChats] stays false — secret chats are out of scope.
     */
    suspend fun setParameters(
        databaseDirectory: String,
        apiId: Int,
        apiHash: String,
        databaseEncryptionKey: ByteArray,
    ): String? = awaitResult {
        client.send(
            TdApi.SetTdlibParameters().apply {
                useTestDc = false
                this.databaseDirectory = databaseDirectory
                filesDirectory = databaseDirectory
                this.databaseEncryptionKey = databaseEncryptionKey
                useFileDatabase = true
                useChatInfoDatabase = true
                useMessageDatabase = true
                useSecretChats = false
                this.apiId = apiId
                this.apiHash = apiHash
                systemLanguageCode = "fr"
                deviceModel = "Android"
                systemVersion = "SecureMessenger"
                applicationVersion = "1.0.5"
            },
            it,
        )
    }

    suspend fun getMe(): TdApi.User? = suspendCancellableCoroutine { cont ->
        client.send(TdApi.GetMe()) { result ->
            when (result) {
                is TdApi.User -> if (cont.isActive) cont.resume(result)
                else -> if (cont.isActive) cont.resume(null)
            }
        }
    }

    fun setPhoneNumber(phone: String) {
        val settings = TdApi.PhoneNumberAuthenticationSettings().apply {
            allowFlashCall = false
            allowMissedCall = false
            isCurrentPhoneNumber = false
            hasUnknownPhoneNumber = true
            allowSmsRetrieverApi = false
            authenticationTokens = emptyArray()
        }
        client.send(TdApi.SetAuthenticationPhoneNumber(phone, settings))
    }

    suspend fun checkCode(code: String): String? = awaitResult {
        client.send(TdApi.CheckAuthenticationCode(code), it)
    }

    suspend fun checkPassword(password: String): String? = awaitResult {
        client.send(TdApi.CheckAuthenticationPassword(password), it)
    }

    suspend fun resendCode(): String? = awaitResult {
        client.send(TdApi.ResendAuthenticationCode(), it)
    }

    suspend fun registerUser(firstName: String, lastName: String): String? = awaitResult {
        client.send(TdApi.RegisterUser(firstName, lastName, false), it)
    }

    suspend fun setAuthenticationEmailAddress(email: String): String? = awaitResult {
        client.send(TdApi.SetAuthenticationEmailAddress(email.trim()), it)
    }

    suspend fun checkAuthenticationEmailCode(code: String): String? = awaitResult {
        client.send(
            TdApi.CheckAuthenticationEmailCode(TdApi.EmailAddressAuthenticationCode(code.trim())),
            it,
        )
    }

    suspend fun downloadFile(fileId: Int, priority: Int = 32): TdApi.File? =
        suspendCancellableCoroutine { cont ->
            client.send(TdApi.DownloadFile(fileId, priority, 0, 0, true)) { result ->
                when (result) {
                    is TdApi.File -> if (cont.isActive) cont.resume(result)
                    is TdApi.Error -> {
                        Timber.w("DownloadFile error ${result.code}: ${result.message}")
                        if (cont.isActive) cont.resume(null)
                    }
                    else -> if (cont.isActive) cont.resume(null)
                }
            }
        }

    suspend fun getContacts(): List<Long> = suspendCancellableCoroutine { cont ->
        client.send(TdApi.GetContacts()) { result ->
            when (result) {
                is TdApi.Users -> {
                    val ids = result.userIds?.toList() ?: emptyList()
                    if (cont.isActive) cont.resume(ids)
                }
                is TdApi.Error -> {
                    Timber.w("GetContacts error ${result.code}: ${result.message}")
                    if (cont.isActive) cont.resume(emptyList())
                }
                else -> if (cont.isActive) cont.resume(emptyList())
            }
        }
    }

    suspend fun getUser(userId: Long): TdApi.User? = suspendCancellableCoroutine { cont ->
        client.send(TdApi.GetUser(userId)) { result ->
            when (result) {
                is TdApi.User -> if (cont.isActive) cont.resume(result)
                is TdApi.Error -> {
                    Timber.w("GetUser error ${result.code}: ${result.message}")
                    if (cont.isActive) cont.resume(null)
                }
                else -> if (cont.isActive) cont.resume(null)
            }
        }
    }

    suspend fun getUserFullInfo(userId: Long): TdApi.UserFullInfo? = suspendCancellableCoroutine { cont ->
        client.send(TdApi.GetUserFullInfo(userId)) { result ->
            when (result) {
                is TdApi.UserFullInfo -> if (cont.isActive) cont.resume(result)
                is TdApi.Error -> {
                    Timber.w("GetUserFullInfo error ${result.code}: ${result.message}")
                    if (cont.isActive) cont.resume(null)
                }
                else -> if (cont.isActive) cont.resume(null)
            }
        }
    }

    suspend fun sendMedia(
        chatId: Long,
        localPath: String,
        mimeType: String,
        caption: String?,
        selfDestructSeconds: Int? = null,
    ): TdSendResult {
        val formattedCaption = TdApi.FormattedText(caption.orEmpty(), emptyArray())
        val localFile = TdApi.InputFileLocal(localPath)
        val selfDestruct = selfDestructSeconds?.takeIf { it > 0 }?.let {
            TdApi.MessageSelfDestructTypeTimer(it)
        }
        val content: TdApi.InputMessageContent = when {
            mimeType.startsWith("image/") -> TdApi.InputMessagePhoto(
                TdApi.InputPhoto(localFile, null, null, intArrayOf(), 0, 0),
                formattedCaption,
                false,
                selfDestruct,
                false,
            )
            mimeType.startsWith("video/") -> TdApi.InputMessageVideo(
                TdApi.InputVideo(localFile, null, null, 0, intArrayOf(), 0, 0, 0, true),
                formattedCaption,
                false,
                null,
                false,
            )
            mimeType.startsWith("audio/") -> TdApi.InputMessageAudio(
                TdApi.InputAudio(localFile, null, 0, "", ""),
                formattedCaption,
            )
            else -> TdApi.InputMessageDocument(
                TdApi.InputDocument(localFile, null, false),
                formattedCaption,
            )
        }
        return sendMessageContent(chatId, content)
    }

    suspend fun sendVoiceNote(
        chatId: Long,
        localPath: String,
        durationMs: Int = 0,
        selfDestructSeconds: Int? = null,
    ): TdSendResult {
        val durationSec = (durationMs / 1000).coerceAtLeast(0)
        val selfDestruct = selfDestructSeconds?.takeIf { it > 0 }?.let {
            TdApi.MessageSelfDestructTypeTimer(it)
        }
        val content = TdApi.InputMessageVoiceNote(
            TdApi.InputVoiceNote(
                TdApi.InputFileLocal(localPath),
                durationSec,
                ByteArray(0),
            ),
            TdApi.FormattedText("", emptyArray()),
            selfDestruct,
        )
        return sendMessageContent(chatId, content)
    }

    /** Photo with optional [MessageSelfDestructTypeTimer] (view-once / timed). */
    suspend fun sendPhoto(
        chatId: Long,
        localPath: String,
        caption: String? = null,
        selfDestructSeconds: Int? = null,
    ): TdSendResult {
        val selfDestruct = selfDestructSeconds?.takeIf { it > 0 }?.let {
            TdApi.MessageSelfDestructTypeTimer(it)
        }
        val content = TdApi.InputMessagePhoto(
            TdApi.InputPhoto(TdApi.InputFileLocal(localPath), null, null, intArrayOf(), 0, 0),
            TdApi.FormattedText(caption.orEmpty(), emptyArray()),
            false,
            selfDestruct,
            false,
        )
        return sendMessageContent(chatId, content)
    }

    suspend fun setChatMessageAutoDeleteTime(chatId: Long, messageAutoDeleteTime: Int): String? =
        awaitResult {
            client.send(TdApi.SetChatMessageAutoDeleteTime(chatId, messageAutoDeleteTime), it)
        }

    suspend fun setPollAnswer(chatId: Long, messageId: Long, optionIds: IntArray): String? =
        awaitResult {
            client.send(TdApi.SetPollAnswer(chatId, messageId, optionIds), it)
        }

    suspend fun searchUserByPhoneNumber(phoneNumber: String, onlyLocal: Boolean = false): TdApi.User? =
        suspendCancellableCoroutine { cont ->
            client.send(TdApi.SearchUserByPhoneNumber(phoneNumber, onlyLocal)) { result ->
                when (result) {
                    is TdApi.User -> if (cont.isActive) cont.resume(result)
                    is TdApi.Error -> {
                        Timber.w("SearchUserByPhoneNumber error ${result.code}: ${result.message}")
                        if (cont.isActive) cont.resume(null)
                    }
                    else -> if (cont.isActive) cont.resume(null)
                }
            }
        }

    suspend fun createPrivateChat(userId: Long, force: Boolean = false): TdApi.Chat? =
        suspendCancellableCoroutine { cont ->
            client.send(TdApi.CreatePrivateChat(userId, force)) { result ->
                when (result) {
                    is TdApi.Chat -> if (cont.isActive) cont.resume(result)
                    is TdApi.Error -> {
                        Timber.w("CreatePrivateChat error ${result.code}: ${result.message}")
                        if (cont.isActive) cont.resume(null)
                    }
                    else -> if (cont.isActive) cont.resume(null)
                }
            }
        }

    suspend fun setProfilePhoto(localPath: String, isPublic: Boolean = true): String? = awaitResult {
        client.send(
            TdApi.SetProfilePhoto(
                TdApi.InputChatPhotoStatic(TdApi.InputFileLocal(localPath)),
                isPublic,
            ),
            it,
        )
    }

    suspend fun importContacts(contacts: Array<TdApi.ImportedContact>): TdApi.ImportedContacts? =
        suspendCancellableCoroutine { cont ->
            client.send(TdApi.ImportContacts(contacts)) { result ->
                when (result) {
                    is TdApi.ImportedContacts -> if (cont.isActive) cont.resume(result)
                    is TdApi.Error -> {
                        Timber.w("ImportContacts error ${result.code}: ${result.message}")
                        if (cont.isActive) cont.resume(null)
                    }
                    else -> if (cont.isActive) cont.resume(null)
                }
            }
        }

    suspend fun getInstalledStickerSets(): List<TdApi.StickerSetInfo> =
        suspendCancellableCoroutine { cont ->
            client.send(TdApi.GetInstalledStickerSets(TdApi.StickerTypeRegular())) { result ->
                when (result) {
                    is TdApi.StickerSets -> {
                        val sets = result.sets?.toList() ?: emptyList()
                        if (cont.isActive) cont.resume(sets)
                    }
                    is TdApi.Error -> {
                        Timber.w("GetInstalledStickerSets error ${result.code}: ${result.message}")
                        if (cont.isActive) cont.resume(emptyList())
                    }
                    else -> if (cont.isActive) cont.resume(emptyList())
                }
            }
        }

    suspend fun getStickerSet(setId: Long): TdApi.StickerSet? =
        suspendCancellableCoroutine { cont ->
            client.send(TdApi.GetStickerSet(setId)) { result ->
                when (result) {
                    is TdApi.StickerSet -> if (cont.isActive) cont.resume(result)
                    is TdApi.Error -> {
                        Timber.w("GetStickerSet error ${result.code}: ${result.message}")
                        if (cont.isActive) cont.resume(null)
                    }
                    else -> if (cont.isActive) cont.resume(null)
                }
            }
        }

    suspend fun getStickers(query: String = "", limit: Int = 50, chatId: Long = 0L): List<TdApi.Sticker> =
        suspendCancellableCoroutine { cont ->
            client.send(
                TdApi.GetStickers(TdApi.StickerTypeRegular(), query, limit, chatId),
            ) { result ->
                when (result) {
                    is TdApi.Stickers -> {
                        val list = result.stickers?.toList() ?: emptyList()
                        if (cont.isActive) cont.resume(list)
                    }
                    is TdApi.Error -> {
                        Timber.w("GetStickers error ${result.code}: ${result.message}")
                        if (cont.isActive) cont.resume(emptyList())
                    }
                    else -> if (cont.isActive) cont.resume(emptyList())
                }
            }
        }

    suspend fun addMessageReaction(
        chatId: Long,
        messageId: Long,
        emoji: String,
        isBig: Boolean = false,
    ): String? = awaitResult {
        client.send(
            TdApi.AddMessageReaction(
                chatId,
                messageId,
                TdApi.ReactionTypeEmoji(emoji),
                isBig,
                true,
            ),
            it,
        )
    }

    suspend fun removeMessageReaction(
        chatId: Long,
        messageId: Long,
        emoji: String,
    ): String? = awaitResult {
        client.send(
            TdApi.RemoveMessageReaction(chatId, messageId, TdApi.ReactionTypeEmoji(emoji)),
            it,
        )
    }

    suspend fun pinChatMessage(
        chatId: Long,
        messageId: Long,
        disableNotification: Boolean = true,
        onlyForSelf: Boolean = false,
    ): String? = awaitResult {
        client.send(TdApi.PinChatMessage(chatId, messageId, disableNotification, onlyForSelf), it)
    }

    suspend fun unpinChatMessage(chatId: Long, messageId: Long): String? = awaitResult {
        client.send(TdApi.UnpinChatMessage(chatId, messageId), it)
    }

    suspend fun unpinAllChatMessages(chatId: Long): String? = awaitResult {
        client.send(TdApi.UnpinAllChatMessages(chatId), it)
    }

    suspend fun stopPoll(chatId: Long, messageId: Long): String? = awaitResult {
        client.send(TdApi.StopPoll(chatId, messageId, null), it)
    }

    suspend fun editMessageCaption(
        chatId: Long,
        messageId: Long,
        caption: String,
    ): String? = awaitResult {
        client.send(
            TdApi.EditMessageCaption(
                chatId,
                messageId,
                null,
                TdApi.FormattedText(caption, emptyArray()),
                false,
            ),
            it,
        )
    }

    suspend fun leaveChat(chatId: Long): String? = awaitResult {
        client.send(TdApi.LeaveChat(chatId), it)
    }

    suspend fun joinChat(chatId: Long): TdApi.ChatJoinResult? =
        suspendCancellableCoroutine { cont ->
            client.send(TdApi.JoinChat(chatId)) { result ->
                when (result) {
                    is TdApi.ChatJoinResult -> if (cont.isActive) cont.resume(result)
                    is TdApi.Error -> {
                        Timber.w("JoinChat error ${result.code}: ${result.message}")
                        if (cont.isActive) cont.resume(null)
                    }
                    else -> if (cont.isActive) cont.resume(null)
                }
            }
        }

    suspend fun joinChatByInviteLink(inviteLink: String): TdApi.ChatJoinResult? =
        suspendCancellableCoroutine { cont ->
            client.send(TdApi.JoinChatByInviteLink(inviteLink.trim())) { result ->
                when (result) {
                    is TdApi.ChatJoinResult -> if (cont.isActive) cont.resume(result)
                    is TdApi.Error -> {
                        Timber.w("JoinChatByInviteLink error ${result.code}: ${result.message}")
                        if (cont.isActive) cont.resume(null)
                    }
                    else -> if (cont.isActive) cont.resume(null)
                }
            }
        }

    suspend fun setUserBlocked(userId: Long, blocked: Boolean): String? = awaitResult {
        val list: TdApi.BlockList? = if (blocked) TdApi.BlockListMain() else null
        client.send(
            TdApi.SetMessageSenderBlockList(TdApi.MessageSenderUser(userId), list),
            it,
        )
    }

    suspend fun toggleChatMarkedAsUnread(chatId: Long, marked: Boolean): String? = awaitResult {
        client.send(TdApi.ToggleChatIsMarkedAsUnread(chatId, marked), it)
    }

    suspend fun setChatDraft(chatId: Long, text: String, replyToMessageId: Long? = null): String? {
        val draft = if (text.isBlank()) {
            null
        } else {
            TdApi.DraftMessage(
                replyToMessageId?.takeIf { it > 0 }?.let {
                    TdApi.InputMessageReplyToMessage(it, null, 0, null)
                },
                (System.currentTimeMillis() / 1000L).toInt(),
                TdApi.DraftMessageContentText(
                    TdApi.FormattedText(text, emptyArray()),
                    null,
                ),
                0L,
                null,
            )
        }
        return awaitResult {
            client.send(TdApi.SetChatDraftMessage(chatId, null, draft), it)
        }
    }

    suspend fun searchChatMessages(
        chatId: Long,
        query: String,
        fromMessageId: Long = 0L,
        limit: Int = 50,
    ): TdApi.FoundChatMessages? = suspendCancellableCoroutine { cont ->
        client.send(
            TdApi.SearchChatMessages(
                chatId,
                null,
                query,
                null,
                fromMessageId,
                0,
                limit,
                null,
            ),
        ) { result ->
            when (result) {
                is TdApi.FoundChatMessages -> if (cont.isActive) cont.resume(result)
                is TdApi.Error -> {
                    Timber.w("SearchChatMessages error ${result.code}: ${result.message}")
                    if (cont.isActive) cont.resume(null)
                }
                else -> if (cont.isActive) cont.resume(null)
            }
        }
    }

    suspend fun searchMessages(
        query: String,
        limit: Int = 50,
        offset: String = "",
    ): TdApi.FoundMessages? = suspendCancellableCoroutine { cont ->
        client.send(
            TdApi.SearchMessages(
                TdApi.ChatListMain(),
                query,
                offset,
                limit,
                null,
                null,
                0,
                0,
            ),
        ) { result ->
            when (result) {
                is TdApi.FoundMessages -> if (cont.isActive) cont.resume(result)
                is TdApi.Error -> {
                    Timber.w("SearchMessages error ${result.code}: ${result.message}")
                    if (cont.isActive) cont.resume(null)
                }
                else -> if (cont.isActive) cont.resume(null)
            }
        }
    }

    suspend fun getChatScheduledMessages(chatId: Long): List<TdApi.Message> =
        suspendCancellableCoroutine { cont ->
            client.send(TdApi.GetChatScheduledMessages(chatId)) { result ->
                when (result) {
                    is TdApi.Messages -> {
                        val list = result.messages?.toList() ?: emptyList()
                        if (cont.isActive) cont.resume(list)
                    }
                    is TdApi.Error -> {
                        Timber.w("GetChatScheduledMessages error ${result.code}: ${result.message}")
                        if (cont.isActive) cont.resume(emptyList())
                    }
                    else -> if (cont.isActive) cont.resume(emptyList())
                }
            }
        }

    suspend fun getChatFolder(folderId: Int): TdApi.ChatFolder? =
        suspendCancellableCoroutine { cont ->
            client.send(TdApi.GetChatFolder(folderId)) { result ->
                when (result) {
                    is TdApi.ChatFolder -> if (cont.isActive) cont.resume(result)
                    is TdApi.Error -> {
                        Timber.w("GetChatFolder error ${result.code}: ${result.message}")
                        if (cont.isActive) cont.resume(null)
                    }
                    else -> if (cont.isActive) cont.resume(null)
                }
            }
        }

    suspend fun getChatsInFolder(folderId: Int, limit: Int = 100): List<Long> =
        suspendCancellableCoroutine { cont ->
            client.send(TdApi.GetChats(TdApi.ChatListFolder(folderId), limit)) { result ->
                when (result) {
                    is TdApi.Chats -> {
                        val ids = result.chatIds?.toList() ?: emptyList()
                        if (cont.isActive) cont.resume(ids)
                    }
                    is TdApi.Error -> {
                        Timber.w("GetChats folder error ${result.code}: ${result.message}")
                        if (cont.isActive) cont.resume(emptyList())
                    }
                    else -> if (cont.isActive) cont.resume(emptyList())
                }
            }
        }

    suspend fun createChatFolder(
        title: String,
        includedChatIds: LongArray,
        includeContacts: Boolean = true,
        includeNonContacts: Boolean = true,
        includeBots: Boolean = true,
        includeGroups: Boolean = true,
        includeChannels: Boolean = true,
    ): TdApi.ChatFolderInfo? = suspendCancellableCoroutine { cont ->
        val folder = TdApi.ChatFolder(
            TdApi.ChatFolderName(TdApi.FormattedText(title, emptyArray()), false),
            null,
            -1,
            false,
            longArrayOf(),
            includedChatIds,
            longArrayOf(),
            false,
            false,
            false,
            includeContacts,
            includeNonContacts,
            includeBots,
            includeGroups,
            includeChannels,
        )
        client.send(TdApi.CreateChatFolder(folder)) { result ->
            when (result) {
                is TdApi.ChatFolderInfo -> if (cont.isActive) cont.resume(result)
                is TdApi.Error -> {
                    Timber.w("CreateChatFolder error ${result.code}: ${result.message}")
                    if (cont.isActive) cont.resume(null)
                }
                else -> if (cont.isActive) cont.resume(null)
            }
        }
    }

    suspend fun deleteChatFolder(folderId: Int): String? = awaitResult {
        client.send(TdApi.DeleteChatFolder(folderId, longArrayOf()), it)
    }

    suspend fun getForumTopics(
        chatId: Long,
        query: String = "",
        limit: Int = 50,
    ): List<TdApi.ForumTopic> = suspendCancellableCoroutine { cont ->
        client.send(TdApi.GetForumTopics(chatId, query, 0, 0L, 0, limit)) { result ->
            when (result) {
                is TdApi.ForumTopics -> {
                    val list = result.topics?.toList() ?: emptyList()
                    if (cont.isActive) cont.resume(list)
                }
                is TdApi.Error -> {
                    Timber.w("GetForumTopics error ${result.code}: ${result.message}")
                    if (cont.isActive) cont.resume(emptyList())
                }
                else -> if (cont.isActive) cont.resume(emptyList())
            }
        }
    }

    suspend fun createForumTopic(chatId: Long, name: String): TdApi.ForumTopicInfo? =
        suspendCancellableCoroutine { cont ->
            client.send(
                TdApi.CreateForumTopic(chatId, name, false, TdApi.ForumTopicIcon(0, 0L)),
            ) { result ->
                when (result) {
                    is TdApi.ForumTopicInfo -> if (cont.isActive) cont.resume(result)
                    is TdApi.Error -> {
                        Timber.w("CreateForumTopic error ${result.code}: ${result.message}")
                        if (cont.isActive) cont.resume(null)
                    }
                    else -> if (cont.isActive) cont.resume(null)
                }
            }
        }

    suspend fun sendBotStartMessage(
        botUserId: Long,
        chatId: Long,
        parameter: String = "",
    ): TdSendResult = suspendCancellableCoroutine { cont ->
        client.send(TdApi.SendBotStartMessage(botUserId, chatId, parameter)) { result ->
            when (result) {
                is TdApi.Message -> if (cont.isActive) cont.resume(TdSendResult.Ok(result))
                is TdApi.Error -> {
                    Timber.w("SendBotStartMessage error ${result.code}: ${result.message}")
                    if (cont.isActive) cont.resume(TdSendResult.Err(result.message))
                }
                null -> if (cont.isActive) cont.resume(TdSendResult.Err("TDLib unavailable"))
                else -> if (cont.isActive) {
                    cont.resume(TdSendResult.Err("Unexpected: ${result::class.simpleName}"))
                }
            }
        }
    }

    suspend fun getCallbackQueryAnswer(
        chatId: Long,
        messageId: Long,
        data: ByteArray,
    ): TelegramCallbackAnswer? = suspendCancellableCoroutine { cont ->
        client.send(
            TdApi.GetCallbackQueryAnswer(
                chatId,
                messageId,
                TdApi.CallbackQueryPayloadData(data),
            ),
        ) { result ->
            when (result) {
                is TdApi.CallbackQueryAnswer -> if (cont.isActive) {
                    cont.resume(
                        TelegramCallbackAnswer(
                            text = result.text.orEmpty(),
                            showAlert = result.showAlert,
                            url = result.url?.takeIf { it.isNotBlank() },
                        ),
                    )
                }
                is TdApi.Error -> {
                    Timber.w("GetCallbackQueryAnswer error ${result.code}: ${result.message}")
                    if (cont.isActive) cont.resume(null)
                }
                else -> if (cont.isActive) cont.resume(null)
            }
        }
    }

    suspend fun createNewSupergroupChat(
        title: String,
        isChannel: Boolean,
        description: String = "",
        isForum: Boolean = false,
    ): TdApi.Chat? = suspendCancellableCoroutine { cont ->
        client.send(
            TdApi.CreateNewSupergroupChat(
                title,
                isForum,
                isChannel,
                description,
                null,
                0,
                false,
            ),
        ) { result ->
            when (result) {
                is TdApi.Chat -> if (cont.isActive) cont.resume(result)
                is TdApi.Error -> {
                    Timber.w("CreateNewSupergroupChat error ${result.code}: ${result.message}")
                    if (cont.isActive) cont.resume(null)
                }
                else -> if (cont.isActive) cont.resume(null)
            }
        }
    }

    suspend fun sendVideoNote(
        chatId: Long,
        localPath: String,
        durationSec: Int = 0,
        length: Int = 240,
        options: TelegramSendOptions = TelegramSendOptions(),
    ): TdSendResult {
        val content = TdApi.InputMessageVideoNote(
            TdApi.InputVideoNote(TdApi.InputFileLocal(localPath), null, durationSec, length),
            null,
        )
        return sendMessageContent(chatId, content, options)
    }

    suspend fun setChatTitle(chatId: Long, title: String): String? = awaitResult {
        client.send(TdApi.SetChatTitle(chatId, title), it)
    }

    suspend fun setChatDescription(chatId: Long, description: String): String? = awaitResult {
        client.send(TdApi.SetChatDescription(chatId, description), it)
    }

    suspend fun loadActiveStories(): String? = awaitResult {
        client.send(TdApi.LoadActiveStories(TdApi.StoryListMain()), it)
    }

    suspend fun getChatActiveStories(chatId: Long): TdApi.ChatActiveStories? =
        suspendCancellableCoroutine { cont ->
            client.send(TdApi.GetChatActiveStories(chatId)) { result ->
                when (result) {
                    is TdApi.ChatActiveStories -> if (cont.isActive) cont.resume(result)
                    is TdApi.Error -> {
                        Timber.w("GetChatActiveStories error ${result.code}: ${result.message}")
                        if (cont.isActive) cont.resume(null)
                    }
                    else -> if (cont.isActive) cont.resume(null)
                }
            }
        }

    suspend fun editMessageText(chatId: Long, messageId: Long, text: String): String? = awaitResult {
        val content = TdApi.InputMessageText().apply {
            this.text = TdApi.FormattedText(text, emptyArray())
            clearDraft = false
        }
        client.send(TdApi.EditMessageText(chatId, messageId, null, content), it)
    }

    suspend fun deleteMessages(chatId: Long, messageIds: LongArray, revoke: Boolean = true): String? =
        awaitResult {
            client.send(TdApi.DeleteMessages(chatId, messageIds, revoke), it)
        }

    suspend fun setChatNotificationMute(chatId: Long, muteForSeconds: Int): String? {
        val chat = getChat(chatId) ?: return "Chat introuvable"
        val settings = chat.notificationSettings ?: TdApi.ChatNotificationSettings()
        settings.useDefaultMuteFor = false
        settings.muteFor = muteForSeconds
        return awaitResult {
            client.send(TdApi.SetChatNotificationSettings(chatId, settings), it)
        }
    }

    suspend fun toggleChatIsPinned(chatId: Long, isPinned: Boolean): String? = awaitResult {
        client.send(TdApi.ToggleChatIsPinned(TdApi.ChatListMain(), chatId, isPinned), it)
    }

    suspend fun setChatArchived(chatId: Long, archived: Boolean): String? = awaitResult {
        val list: TdApi.ChatList = if (archived) TdApi.ChatListArchive() else TdApi.ChatListMain()
        client.send(TdApi.AddChatToList(chatId, list), it)
    }

    suspend fun forwardMessages(
        toChatId: Long,
        fromChatId: Long,
        messageIds: LongArray,
        sendCopy: Boolean = false,
        removeCaption: Boolean = false,
    ): String? = awaitResult {
        client.send(
            TdApi.ForwardMessages(
                toChatId,
                null,
                fromChatId,
                messageIds,
                TdApi.MessageSendOptions(),
                sendCopy,
                removeCaption,
            ),
            it,
        )
    }

    suspend fun sendTextMessage(
        chatId: Long,
        text: String,
        options: TelegramSendOptions = TelegramSendOptions(),
        parseMarkdown: Boolean = false,
    ): TdSendResult {
        val formatted = if (parseMarkdown) {
            parseMarkdownText(text) ?: TdApi.FormattedText(text, emptyArray())
        } else {
            TdApi.FormattedText(text, emptyArray())
        }
        val linkPreview = TdApi.LinkPreviewOptions().apply {
            isDisabled = false
            url = ""
            forceSmallMedia = false
            forceLargeMedia = false
            showAboveText = false
        }
        return sendMessageContent(
            chatId,
            TdApi.InputMessageText(formatted, linkPreview, true),
            options,
        )
    }

    /** Parses Telegram Markdown v2 (incl. `||spoiler||`) via [TdApi.ParseTextEntities]. */
    suspend fun parseMarkdownText(text: String): TdApi.FormattedText? =
        suspendCancellableCoroutine { cont ->
            client.send(TdApi.ParseTextEntities(text, TdApi.TextParseModeMarkdown(2))) { result ->
                when (result) {
                    is TdApi.FormattedText -> if (cont.isActive) cont.resume(result)
                    else -> if (cont.isActive) cont.resume(null)
                }
            }
        }

    suspend fun sendTextWithSpoiler(
        chatId: Long,
        text: String,
        spoilerStart: Int,
        spoilerLength: Int,
        options: TelegramSendOptions = TelegramSendOptions(),
    ): TdSendResult {
        val start = spoilerStart.coerceAtLeast(0)
        val len = spoilerLength.coerceIn(0, (text.length - start).coerceAtLeast(0))
        val entities = if (len > 0) {
            arrayOf(TdApi.TextEntity(start, len, TdApi.TextEntityTypeSpoiler()))
        } else {
            emptyArray()
        }
        val linkPreview = TdApi.LinkPreviewOptions().apply {
            isDisabled = false
            url = ""
            forceSmallMedia = false
            forceLargeMedia = false
            showAboveText = false
        }
        return sendMessageContent(
            chatId,
            TdApi.InputMessageText(TdApi.FormattedText(text, entities), linkPreview, true),
            options,
        )
    }

    suspend fun sendLocation(
        chatId: Long,
        latitude: Double,
        longitude: Double,
        horizontalAccuracy: Double = 0.0,
        livePeriodSec: Int? = null,
    ): TdSendResult {
        val location = TdApi.Location(latitude, longitude, horizontalAccuracy)
        val content: TdApi.InputMessageContent = if (livePeriodSec != null && livePeriodSec > 0) {
            TdApi.InputMessageLiveLocation(
                TdApi.LiveLocation(location, livePeriodSec, 0, 0),
            )
        } else {
            TdApi.InputMessageLocation(location)
        }
        return sendMessageContent(chatId, content)
    }

    suspend fun sendContact(
        chatId: Long,
        firstName: String,
        lastName: String = "",
        phone: String? = null,
        userId: Long = 0L,
    ): TdSendResult {
        val content = TdApi.InputMessageContact(
            TdApi.Contact(phone.orEmpty(), firstName, lastName, "", userId),
        )
        return sendMessageContent(chatId, content)
    }

    suspend fun sendPoll(
        chatId: Long,
        question: String,
        options: List<String>,
        anonymous: Boolean = true,
        multipleAnswers: Boolean = false,
    ): TdSendResult {
        val pollOptions = options.map {
            TdApi.InputPollOption(TdApi.FormattedText(it, emptyArray()), null)
        }.toTypedArray()
        val content = TdApi.InputMessagePoll(
            TdApi.FormattedText(question, emptyArray()),
            pollOptions,
            TdApi.FormattedText("", emptyArray()),
            null,
            anonymous,
            multipleAnswers,
            true,
            false,
            emptyArray(),
            false,
            false,
            TdApi.InputPollTypeRegular(false),
            0,
            0,
            false,
        )
        return sendMessageContent(chatId, content)
    }

    suspend fun sendAnimation(
        chatId: Long,
        localPath: String,
        caption: String? = null,
    ): TdSendResult {
        val content = TdApi.InputMessageAnimation(
            TdApi.InputAnimation(TdApi.InputFileLocal(localPath), null, intArrayOf(), 0, 0, 0),
            TdApi.FormattedText(caption.orEmpty(), emptyArray()),
            false,
            false,
        )
        return sendMessageContent(chatId, content)
    }

    suspend fun sendSticker(
        chatId: Long,
        localPath: String,
        emoji: String = "⭐",
    ): TdSendResult {
        val content = TdApi.InputMessageSticker(
            TdApi.InputSticker(
                TdApi.InputFileLocal(localPath),
                null,
                0,
                0,
            ),
            emoji,
        )
        return sendMessageContent(chatId, content)
    }

    fun setTyping(chatId: Long, typing: Boolean) {
        val action: TdApi.ChatAction =
            if (typing) TdApi.ChatActionTyping() else TdApi.ChatActionCancel()
        client.send(TdApi.SendChatAction(chatId, null, "", action))
    }

    suspend fun setName(firstName: String, lastName: String): String? = awaitResult {
        client.send(TdApi.SetName(firstName, lastName), it)
    }

    suspend fun setBio(bio: String): String? = awaitResult {
        client.send(TdApi.SetBio(bio), it)
    }

    fun sendText(chatId: Long, text: String) {
        val linkPreview = TdApi.LinkPreviewOptions().apply {
            isDisabled = false
            url = ""
            forceSmallMedia = false
            forceLargeMedia = false
            showAboveText = false
        }
        client.send(
            TdApi.SendMessage().apply {
                this.chatId = chatId
                topicId = null
                replyTo = null
                options = TdApi.MessageSendOptions()
                replyMarkup = null
                inputMessageContent = TdApi.InputMessageText(
                    TdApi.FormattedText(text, emptyArray()),
                    linkPreview,
                    true,
                )
            },
        )
    }

    private fun buildSendOptions(options: TelegramSendOptions): TdApi.MessageSendOptions =
        TdApi.MessageSendOptions().apply {
            disableNotification = options.silent
            protectContent = options.protectContent
            options.scheduleAtUnixSec?.takeIf { it > 0 }?.let { at ->
                schedulingState = TdApi.MessageSchedulingStateSendAtDate(at, 0)
            }
        }

    private fun buildReplyTo(options: TelegramSendOptions): TdApi.InputMessageReplyTo? =
        options.replyToMessageId?.takeIf { it > 0 }?.let {
            TdApi.InputMessageReplyToMessage(it, null, 0, null)
        }

    private fun buildTopic(options: TelegramSendOptions): TdApi.MessageTopic? =
        options.forumTopicId?.takeIf { it > 0 }?.let { TdApi.MessageTopicForum(it) }

    private suspend fun sendMessageContent(
        chatId: Long,
        content: TdApi.InputMessageContent,
        options: TelegramSendOptions = TelegramSendOptions(),
    ): TdSendResult = suspendCancellableCoroutine { cont ->
        client.send(
            TdApi.SendMessage().apply {
                this.chatId = chatId
                topicId = buildTopic(options)
                replyTo = buildReplyTo(options)
                this.options = buildSendOptions(options)
                replyMarkup = null
                inputMessageContent = content
            },
        ) { result ->
            when (result) {
                is TdApi.Error -> {
                    Timber.w("SendMessage error ${result.code}: ${result.message}")
                    if (cont.isActive) cont.resume(TdSendResult.Err(result.message))
                }
                is TdApi.Message -> if (cont.isActive) cont.resume(TdSendResult.Ok(result))
                null -> if (cont.isActive) cont.resume(TdSendResult.Err("TDLib unavailable"))
                else -> if (cont.isActive) {
                    cont.resume(TdSendResult.Err("Unexpected TDLib response: ${result::class.simpleName}"))
                }
            }
        }
    }

    /** TDLib Example.java uses LoadChats, not deprecated GetChats. */
    fun loadChats(limit: Int = 100) {
        client.send(TdApi.LoadChats(TdApi.ChatListMain(), limit))
    }

    fun requestChat(chatId: Long) {
        client.send(TdApi.GetChat(chatId))
    }

    fun openChat(chatId: Long) {
        client.send(TdApi.OpenChat(chatId))
    }

    suspend fun getChat(chatId: Long): TdApi.Chat? = suspendCancellableCoroutine { cont ->
        client.send(TdApi.GetChat(chatId)) { result ->
            when (result) {
                is TdApi.Chat -> if (cont.isActive) cont.resume(result)
                is TdApi.Error -> {
                    Timber.w("GetChat error ${result.code}: ${result.message}")
                    if (cont.isActive) cont.resume(null)
                }
                else -> if (cont.isActive) cont.resume(null)
            }
        }
    }

    suspend fun getChatIds(limit: Int = 100): List<Long> = suspendCancellableCoroutine { cont ->
        client.send(TdApi.GetChats(TdApi.ChatListMain(), limit)) { result ->
            when (result) {
                is TdApi.Chats -> {
                    val ids = result.chatIds?.toList() ?: emptyList()
                    if (cont.isActive) cont.resume(ids)
                }
                is TdApi.Error -> {
                    Timber.w("GetChats error ${result.code}: ${result.message}")
                    if (cont.isActive) cont.resume(emptyList())
                }
                else -> if (cont.isActive) cont.resume(emptyList())
            }
        }
    }

    suspend fun searchPublicChat(username: String): TdApi.Chat? = suspendCancellableCoroutine { cont ->
        val normalized = username.removePrefix("@").trim()
        client.send(TdApi.SearchPublicChat(normalized)) { result ->
            when (result) {
                is TdApi.Chat -> if (cont.isActive) cont.resume(result)
                is TdApi.Error -> {
                    Timber.w("SearchPublicChat error ${result.code}: ${result.message}")
                    if (cont.isActive) cont.resume(null)
                }
                else -> if (cont.isActive) cont.resume(null)
            }
        }
    }

    /**
     * Creates a basic group chat. Returns the resulting [TdApi.Chat] after [TdApi.CreateNewBasicGroupChat].
     */
    suspend fun createNewBasicGroupChat(title: String, userIds: LongArray): TdApi.Chat? =
        suspendCancellableCoroutine { cont ->
            client.send(TdApi.CreateNewBasicGroupChat(userIds, title, 0)) { result ->
                when (result) {
                    is TdApi.CreatedBasicGroupChat -> {
                        client.send(TdApi.GetChat(result.chatId)) { chatResult ->
                            when (chatResult) {
                                is TdApi.Chat -> if (cont.isActive) cont.resume(chatResult)
                                is TdApi.Error -> {
                                    Timber.w(
                                        "GetChat after create group error ${chatResult.code}: ${chatResult.message}",
                                    )
                                    if (cont.isActive) cont.resume(null)
                                }
                                else -> if (cont.isActive) cont.resume(null)
                            }
                        }
                    }
                    is TdApi.Error -> {
                        Timber.w("CreateNewBasicGroupChat error ${result.code}: ${result.message}")
                        if (cont.isActive) cont.resume(null)
                    }
                    else -> if (cont.isActive) cont.resume(null)
                }
            }
        }

    fun closeChat(chatId: Long) {
        client.send(TdApi.CloseChat(chatId))
    }

    fun viewMessages(chatId: Long, messageIds: LongArray) {
        if (messageIds.isEmpty()) return
        client.send(
            TdApi.ViewMessages(
                chatId,
                messageIds,
                TdApi.MessageSourceChatHistory(),
                true,
            ),
        )
    }

    suspend fun getChatHistory(
        chatId: Long,
        fromMessageId: Long = 0,
        limit: Int = 100,
        onlyLocal: Boolean = false,
    ): List<TdApi.Message>? = suspendCancellableCoroutine { cont ->
        client.send(TdApi.GetChatHistory(chatId, fromMessageId, 0, limit, onlyLocal)) { result ->
            when (result) {
                is TdApi.Messages -> {
                    val list = result.messages?.toList() ?: emptyList()
                    if (cont.isActive) cont.resume(list)
                }
                is TdApi.Error -> {
                    Timber.w("GetChatHistory error ${result.code}: ${result.message}")
                    if (cont.isActive) cont.resume(null)
                }
                else -> if (cont.isActive) cont.resume(null)
            }
        }
    }

    /**
     * Paginates [GetChatHistory] until the thread is exhausted (newest → oldest).
     * @return number of raw TDLib messages visited across all pages.
     */
    suspend fun paginateChatHistory(
        chatId: Long,
        onlyLocal: Boolean,
        pageSize: Int = 100,
        maxPages: Int = Int.MAX_VALUE,
        onPage: suspend (List<TdApi.Message>) -> Unit,
    ): Int {
        var fromMessageId = 0L
        var total = 0
        var pages = 0
        while (pages < maxPages) {
            val page = getChatHistory(chatId, fromMessageId, pageSize, onlyLocal)
            if (page == null) {
                delay(400)
                val retry = getChatHistory(chatId, fromMessageId, pageSize, onlyLocal)
                if (retry.isNullOrEmpty()) break
                onPage(retry)
                total += retry.size
                pages++
                // TDLib may return fewer than [pageSize] without EOF (td_api.tl).
                val oldestId = retry.last().id
                if (oldestId == fromMessageId) break
                fromMessageId = oldestId
                continue
            }
            if (page.isEmpty()) break
            onPage(page)
            total += page.size
            pages++
            val oldestId = page.last().id
            if (oldestId == fromMessageId) break
            fromMessageId = oldestId
        }
        return total
    }

    /**
     * Loads local TDLib cache first, then syncs from Telegram when the network is reachable.
     */
    suspend fun fetchFullChatHistory(
        chatId: Long,
        pageSize: Int = 100,
        syncRemote: Boolean = true,
        maxPages: Int = 20,
        onPage: suspend (List<TdApi.Message>) -> Unit,
    ): Pair<Int, Int> {
        val local = paginateChatHistory(chatId, onlyLocal = true, pageSize, maxPages, onPage)
        val remote = if (syncRemote) {
            paginateChatHistory(chatId, onlyLocal = false, pageSize, maxPages, onPage)
        } else {
            0
        }
        return local to remote
    }

    /**
     * Mirrors TDLib `Example.getMainChatList`: keep calling [TdApi.LoadChats] until the
     * server answers HTTP-style 404 ("chat list already fully loaded"), then snapshot via
     * [TdApi.GetChats]. Stopping at the first non-empty id list leaves chats unloaded.
     */
    suspend fun syncChatList(limit: Int = 200): List<TdApi.Chat> {
        val deadline = System.currentTimeMillis() + 60_000L
        while (System.currentTimeMillis() < deadline) {
            when (val load = awaitLoadChats(limit)) {
                is TdApi.Error -> {
                    if (load.code == 404) break
                    Timber.w("LoadChats error ${load.code}: ${load.message}")
                    delay(400)
                }
                is TdApi.Ok -> {
                    // Chats arrived via updates; ask again like Example.java.
                    delay(50)
                }
                else -> delay(200)
            }
        }
        val ids = getChatIds(limit)
        return ids.mapNotNull { getChat(it) }
    }

    private suspend fun awaitLoadChats(limit: Int): TdApi.Object? = suspendCancellableCoroutine { cont ->
        client.send(TdApi.LoadChats(TdApi.ChatListMain(), limit)) { result ->
            if (cont.isActive) cont.resume(result)
        }
    }

    fun close() = client.close()

    private suspend fun awaitResult(send: ((TdApi.Object?) -> Unit) -> Unit): String? =
        suspendCancellableCoroutine { cont ->
            send { result ->
                when (result) {
                    is TdApi.Error -> {
                        Timber.w("TDLib request error ${result.code}: ${result.message}")
                        if (cont.isActive) cont.resume(result.message)
                    }
                    null -> if (cont.isActive) cont.resume("TDLib unavailable")
                    else -> if (cont.isActive) cont.resume(null)
                }
            }
        }
}
