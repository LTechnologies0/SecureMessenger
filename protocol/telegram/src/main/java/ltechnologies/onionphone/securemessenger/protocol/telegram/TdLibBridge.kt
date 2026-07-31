package ltechnologies.onionphone.securemessenger.protocol.telegram

import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
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

    suspend fun configureProxy(host: String, port: Int, username: String?, password: String?): Boolean =
        suspendCancellableCoroutine { cont ->
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
                        if (cont.isActive) cont.resume(true)
                    }
                    is TdApi.Proxy -> {
                        Timber.i("TDLib SOCKS5 enabled ${result.server}:${result.port}")
                        if (cont.isActive) cont.resume(true)
                    }
                    is TdApi.Error -> {
                        Timber.w("AddProxy error ${result.code}: ${result.message} ($host:$port)")
                        if (cont.isActive) cont.resume(false)
                    }
                    else -> if (cont.isActive) cont.resume(false)
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

    fun setParameters(databaseDirectory: String, apiId: Int, apiHash: String) {
        client.send(
            TdApi.SetTdlibParameters().apply {
                useTestDc = false
                this.databaseDirectory = databaseDirectory
                filesDirectory = databaseDirectory
                databaseEncryptionKey = ByteArray(0)
                useFileDatabase = true
                useChatInfoDatabase = true
                useMessageDatabase = true
                useSecretChats = true
                this.apiId = apiId
                this.apiHash = apiHash
                systemLanguageCode = "fr"
                deviceModel = "Android"
                systemVersion = "SecureMessenger"
                applicationVersion = "1.0.5"
            },
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
    ): String? {
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
    ): String? {
        val durationSec = (durationMs / 1000).coerceAtLeast(0)
        val selfDestruct = selfDestructSeconds?.takeIf { it > 0 }?.let {
            TdApi.MessageSelfDestructTypeTimer(it)
        }
        val content = TdApi.InputMessageVoiceNote(
            TdApi.InputFileLocal(localPath),
            durationSec,
            ByteArray(0),
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
    ): String? {
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

    suspend fun sendTextMessage(chatId: Long, text: String): String? {
        val linkPreview = TdApi.LinkPreviewOptions().apply {
            isDisabled = false
            url = ""
            forceSmallMedia = false
            forceLargeMedia = false
            showAboveText = false
        }
        return sendMessageContent(
            chatId,
            TdApi.InputMessageText(
                TdApi.FormattedText(text, emptyArray()),
                linkPreview,
                true,
            ),
        )
    }

    suspend fun sendLocation(
        chatId: Long,
        latitude: Double,
        longitude: Double,
        horizontalAccuracy: Double = 0.0,
        livePeriodSec: Int? = null,
    ): String? {
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
    ): String? {
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
    ): String? {
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
    ): String? {
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
    ): String? {
        val content = TdApi.InputMessageSticker(
            TdApi.InputFileLocal(localPath),
            null,
            0,
            0,
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

    private suspend fun sendMessageContent(
        chatId: Long,
        content: TdApi.InputMessageContent,
    ): String? = awaitResult {
        client.send(
            TdApi.SendMessage().apply {
                this.chatId = chatId
                topicId = null
                replyTo = null
                options = TdApi.MessageSendOptions()
                replyMarkup = null
                inputMessageContent = content
            },
            it,
        )
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
    ): List<TdApi.Message> = suspendCancellableCoroutine { cont ->
        client.send(TdApi.GetChatHistory(chatId, fromMessageId, 0, limit, onlyLocal)) { result ->
            when (result) {
                is TdApi.Messages -> {
                    val list = result.messages?.toList() ?: emptyList()
                    if (cont.isActive) cont.resume(list)
                }
                is TdApi.Error -> {
                    Timber.w("GetChatHistory error ${result.code}: ${result.message}")
                    if (cont.isActive) cont.resume(emptyList())
                }
                else -> if (cont.isActive) cont.resume(emptyList())
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
            if (page.isEmpty()) break
            onPage(page)
            total += page.size
            pages++
            if (page.size < pageSize) break
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

    suspend fun syncChatList(limit: Int = 200): List<TdApi.Chat> {
        loadChats(limit)
        val ids = getChatIds(limit)
        return ids.mapNotNull { getChat(it) }
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
