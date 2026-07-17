package ltechnologies.onionphone.securemessenger.protocol.telegram

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import ltechnologies.onionphone.securemessenger.core.model.AuthStepKind
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig

/**
 * Live TDLib session state for one connected Telegram account.
 * Keyed by [accountId] in [TelegramProtocol.sessions] so multiple Telegram accounts can stay
 * connected simultaneously without tearing down sibling sessions.
 */
internal class TelegramSession(
    val accountId: String,
    val facade: TdLibFacade,
) {
    val dispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tdlib-$accountId")
    }.asCoroutineDispatcher()

    var nativeAvailable: Boolean = false
    var awaitingAuth: AuthStepKind = AuthStepKind.NONE
    var authPrompt: String = ""
    var otherDeviceLink: String? = null
    var openChatId: Long? = null

    var pendingPhone: String? = null
    var pendingApiId: Int = 0
    var pendingApiHash: String = ""
    var pendingDbDir: String = ""
    var pendingProxy: ProxyConfig? = null

    /** Maps TDLib file id → message/attachment ids for [TdApi.UpdateFile] progress. */
    val fileDownloads = ConcurrentHashMap<Int, FileDownloadTarget>()

    fun close() {
        facade.disableProxy()
        facade.close()
        fileDownloads.clear()
    }
}

internal data class FileDownloadTarget(
    val messageId: String,
    val attachmentId: String,
)
