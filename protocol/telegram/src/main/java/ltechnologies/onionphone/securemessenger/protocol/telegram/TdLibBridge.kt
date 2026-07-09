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

    suspend fun configureProxy(host: String, port: Int, username: String?, password: String?): Boolean {
        disableProxy()
        return suspendCancellableCoroutine { cont ->
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
    }

    fun configureProxyFireAndForget(host: String, port: Int, username: String?, password: String?) {
        disableProxy()
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
                applicationVersion = "1.0"
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
        onPage: suspend (List<TdApi.Message>) -> Unit,
    ): Int {
        var fromMessageId = 0L
        var total = 0
        while (true) {
            val page = getChatHistory(chatId, fromMessageId, pageSize, onlyLocal)
            if (page.isEmpty()) break
            onPage(page)
            total += page.size
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
        onPage: suspend (List<TdApi.Message>) -> Unit,
    ): Pair<Int, Int> {
        val local = paginateChatHistory(chatId, onlyLocal = true, pageSize, onPage)
        val remote = if (syncRemote) {
            paginateChatHistory(chatId, onlyLocal = false, pageSize, onPage)
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
