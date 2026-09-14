package ltechnologies.onionphone.securemessenger.protocol.signal

import android.content.Context
import android.util.Base64
import java.io.File
import java.io.FileInputStream
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import ltechnologies.onionphone.securemessenger.core.security.EncryptedCredentialStore
import ltechnologies.onionphone.securemessenger.data.MessengerRepository
import org.signal.core.models.backup.MessageBackupKey
import org.signal.libsignal.messagebackup.MessageBackup
import org.signal.network.NetworkResult
import org.signal.network.websocket.WebSocketRequestMessage
import org.signal.network.websocket.get
import org.whispersystems.signalservice.api.SignalServiceMessageReceiver
import org.whispersystems.signalservice.api.fromWebSocketRequest
import org.whispersystems.signalservice.api.link.TransferArchiveResponse
import org.whispersystems.signalservice.api.websocket.WebSocketConnectionState
import timber.log.Timber

/** Next action after one GET /v1/devices/transfer_archive response. */
internal enum class TransferArchivePollDecision {
    /** Primary published cdn+key — download and import. */
    DOWNLOAD,
    /** Not ready yet (204/404/empty) or a transient error — long-poll again. */
    RETRY,
    /** Primary aborted the upload; finish linking without history. */
    CONTINUE_WITHOUT,
    /** Primary asked this device to start the QR flow over. */
    RELINK,
}

/**
 * Secondary-device link-and-sync: long-poll for a primary-provided transfer archive,
 * download + validate, then import frames into local conversations/messages.
 *
 * Signal's primary treats a secondary that advertised `backup3` but never fetched
 * the archive as "error on the new device". HTTP 404/204 means **not ready**, not
 * "no backup".
 */
internal object SignalLinkAndSync {
    /** LinkDeviceApi caps long-poll timeout at 30s (Signal-Android `waitForPrimaryDevice`). */
    private const val POLL_TIMEOUT_SEC = 30L
    /** Match Signal-Android `waitForLinkAndSyncBackupDetails(maxWaitTime = 1.hours)`. */
    private const val OVERALL_DEADLINE_MS = 60 * 60 * 1000L
    private const val WS_WAIT_MS = 45_000L

    /**
     * Map one poll result to a decision. Isolated so unit tests can cover the
     * 404-is-retry contract without a live websocket.
     *
     * Official Signal-Android (`RegistrationRepository.waitForLinkAndSyncBackupDetails`):
     * - 200 + body → archive or CONTINUE_WITHOUT / RELINK
     * - 204 → long-poll elapsed → retry
     * - 400 → invalid timeout → give up without archive
     * - 429 / network / other → retry until overall deadline
     */
    internal fun decidePoll(
        httpCode: Int? = null,
        response: TransferArchiveResponse? = null,
        networkError: Boolean = false,
        applicationError: Boolean = false,
    ): TransferArchivePollDecision {
        if (networkError) return TransferArchivePollDecision.RETRY
        // Official waitForLinkAndSyncBackupDetails: ApplicationError → abort wait (no archive).
        if (applicationError) return TransferArchivePollDecision.CONTINUE_WITHOUT
        if (httpCode != null && httpCode != 200) {
            return when (httpCode) {
                400 -> TransferArchivePollDecision.CONTINUE_WITHOUT
                204, 404, 408, 429 -> TransferArchivePollDecision.RETRY
                in 500..599 -> TransferArchivePollDecision.RETRY
                else -> TransferArchivePollDecision.RETRY
            }
        }
        val body = response ?: return TransferArchivePollDecision.RETRY
        return when {
            body.hasArchive -> TransferArchivePollDecision.DOWNLOAD
            body.error == TransferArchiveResponse.ERROR_RELINK_REQUESTED ->
                TransferArchivePollDecision.RELINK
            body.error == TransferArchiveResponse.ERROR_CONTINUE_WITHOUT_UPLOAD ->
                TransferArchivePollDecision.CONTINUE_WITHOUT
            !body.error.isNullOrBlank() -> TransferArchivePollDecision.CONTINUE_WITHOUT
            else -> TransferArchivePollDecision.RETRY
        }
    }

    suspend fun maybeFetchBackup(
        context: Context,
        accountId: String,
        session: SignalSessionContext,
        credentialStore: EncryptedCredentialStore,
        repository: MessengerRepository,
        onProgress: (String) -> Unit = {},
    ) {
        val encodedKey = credentialStore.get(accountId, SignalCredentialKeys.EPHEMERAL_BACKUP_KEY)
            ?: return
        if (credentialStore.get(accountId, SignalCredentialKeys.LINK_SYNC_IMPORTED) == "1") {
            Timber.d("Link-and-sync already imported for %s", accountId)
            return
        }
        if (credentialStore.get(accountId, SignalCredentialKeys.LINK_SYNC_SKIPPED) == "1") {
            Timber.d("Link-and-sync previously skipped for %s", accountId)
            return
        }

        val existingPath = credentialStore.get(accountId, SignalCredentialKeys.LINK_SYNC_BACKUP_PATH)
        if (existingPath != null && File(existingPath).exists()) {
            SignalBackupImporter.importIfNeeded(accountId, session, credentialStore, repository)
            return
        }

        onProgress("Connexion à Signal…")
        val deadline = System.currentTimeMillis() + OVERALL_DEADLINE_MS
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (session.authWebSocket.stateSnapshot != WebSocketConnectionState.CONNECTED) {
                when (session.authWebSocket.stateSnapshot) {
                    WebSocketConnectionState.AUTHENTICATION_FAILED,
                    WebSocketConnectionState.REMOTE_DEPRECATED,
                    WebSocketConnectionState.FAILED,
                    -> {
                        Timber.w(
                            "Link-and-sync websocket terminal state=%s",
                            session.authWebSocket.stateSnapshot,
                        )
                        markSkipped(credentialStore, accountId)
                        onProgress("Connexion Signal refusée — historique non reçu")
                        return
                    }
                    else -> Unit
                }
                onProgress("Connexion à Signal…")
                val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1_000L)
                if (!waitForAuthSocket(session, remaining.coerceAtMost(WS_WAIT_MS))) {
                    delay(1_000)
                    continue
                }
            }
            attempt++
            onProgress("En attente de l'historique (Signal exporte encore)…")
            val decision = pollOnce(session)
            when (decision.first) {
                TransferArchivePollDecision.DOWNLOAD -> {
                    val response = decision.second
                    if (response == null) continue
                    onProgress("Téléchargement de l'historique…")
                    val stored = downloadArchive(
                        context,
                        accountId,
                        session,
                        credentialStore,
                        encodedKey,
                        response,
                    )
                    if (stored) {
                        onProgress("Import des messages…")
                        SignalBackupImporter.importIfNeeded(
                            accountId,
                            session,
                            credentialStore,
                            repository,
                        )
                        return
                    }
                    Timber.w("Link-and-sync download failed, retrying")
                    delay(2_000)
                }
                TransferArchivePollDecision.RETRY -> {
                    Timber.i(
                        "Link-and-sync poll attempt=%d not ready yet — continuing",
                        attempt,
                    )
                    delay(1_000)
                }
                TransferArchivePollDecision.CONTINUE_WITHOUT -> {
                    Timber.i("Link-and-sync: primary CONTINUE_WITHOUT_UPLOAD")
                    markSkipped(credentialStore, accountId)
                    onProgress("Lien réussi sans transfert d'historique")
                    return
                }
                TransferArchivePollDecision.RELINK -> {
                    Timber.w("Link-and-sync: primary RELINK_REQUESTED")
                    markSkipped(credentialStore, accountId)
                    onProgress("Signal demande de relier l'appareil — historique non transféré")
                    return
                }
            }
        }
        Timber.w(
            "Link-and-sync: no archive after %d ms / %d polls — primary likely reports an error",
            OVERALL_DEADLINE_MS,
            attempt,
        )
        markSkipped(credentialStore, accountId)
        onProgress("Délai dépassé — compte lié, historique non reçu")
    }

    private suspend fun pollOnce(
        session: SignalSessionContext,
    ): Pair<TransferArchivePollDecision, TransferArchiveResponse?> {
        return runCatching {
            val request = WebSocketRequestMessage.get(
                "/v1/devices/transfer_archive?timeout=$POLL_TIMEOUT_SEC",
            )
            val result = NetworkResult.fromWebSocketRequest(
                session.authWebSocket,
                request,
                TransferArchiveResponse::class,
                timeout = (POLL_TIMEOUT_SEC + 15).seconds,
            )
            when (result) {
                is NetworkResult.Success ->
                    decidePoll(httpCode = 200, response = result.result) to result.result
                is NetworkResult.StatusCodeError -> {
                    Timber.i("Link-and-sync transfer_archive HTTP %s", result.code)
                    if (result.code == 429) {
                        val waitMs = result.retryAfter()?.inWholeMilliseconds ?: 5_000L
                        delay(waitMs.coerceAtLeast(1_000L))
                    }
                    decidePoll(httpCode = result.code) to null
                }
                is NetworkResult.NetworkError -> {
                    Timber.w(result.exception, "Link-and-sync transfer_archive network error")
                    delay(5_000)
                    decidePoll(networkError = true) to null
                }
                is NetworkResult.ApplicationError -> {
                    Timber.w(result.throwable, "Link-and-sync transfer_archive application error")
                    // Official waitForLinkAndSyncBackupDetails treats ApplicationError as terminal.
                    decidePoll(applicationError = true) to null
                }
            }
        }.getOrElse { throwable ->
            Timber.w(throwable, "Link-and-sync transfer_archive failed")
            delay(5_000)
            decidePoll(networkError = true) to null
        }
    }

    private suspend fun waitForAuthSocket(
        session: SignalSessionContext,
        timeoutMs: Long = WS_WAIT_MS,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            when (session.authWebSocket.stateSnapshot) {
                WebSocketConnectionState.CONNECTED -> return true
                WebSocketConnectionState.AUTHENTICATION_FAILED,
                WebSocketConnectionState.REMOTE_DEPRECATED,
                WebSocketConnectionState.FAILED,
                -> {
                    Timber.w(
                        "Link-and-sync websocket terminal state=%s",
                        session.authWebSocket.stateSnapshot,
                    )
                    return false
                }
                WebSocketConnectionState.CONNECTING,
                WebSocketConnectionState.DISCONNECTING,
                WebSocketConnectionState.DISCONNECTED,
                -> {
                    runCatching { session.authWebSocket.connect() }
                    delay(250)
                }
            }
        }
        return session.authWebSocket.stateSnapshot == WebSocketConnectionState.CONNECTED
    }

    private fun downloadArchive(
        context: Context,
        accountId: String,
        session: SignalSessionContext,
        credentialStore: EncryptedCredentialStore,
        encodedEphemeralKey: String,
        response: TransferArchiveResponse,
    ): Boolean {
        val cdn = response.cdn ?: return false
        val key = response.key ?: return false
        val dest = File(context.filesDir, "signal_link_sync_$accountId.bak")
        val receiver = SignalServiceMessageReceiver(session.pushServiceSocket)
        return when (val dl = receiver.retrieveLinkAndSyncBackup(cdn, key, dest, null)) {
            is NetworkResult.Success -> {
                val valid = validateBackup(session, encodedEphemeralKey, dest)
                if (!valid) {
                    Timber.w("Link-and-sync backup validation failed — not importing")
                    dest.delete()
                    return false
                }
                credentialStore.put(
                    accountId,
                    SignalCredentialKeys.LINK_SYNC_BACKUP_PATH,
                    dest.absolutePath,
                )
                Timber.i(
                    "Link-and-sync backup stored path=%s size=%d",
                    dest.absolutePath,
                    dest.length(),
                )
                true
            }
            else -> {
                Timber.w("Link-and-sync backup download failed: %s", dl)
                false
            }
        }
    }

    private fun markSkipped(credentialStore: EncryptedCredentialStore, accountId: String) {
        credentialStore.put(accountId, SignalCredentialKeys.LINK_SYNC_SKIPPED, "1")
    }

    private fun validateBackup(
        session: SignalSessionContext,
        encodedEphemeralKey: String,
        dest: File,
    ): Boolean {
        if (!dest.exists() || dest.length() <= 0L) return false
        return runCatching {
            val ephemeralBytes = Base64.decode(encodedEphemeralKey, Base64.NO_WRAP)
            val modelsKey = MessageBackupKey(ephemeralBytes)
            val material = modelsKey.deriveBackupSecrets(session.aci, null)
            val libKey = org.signal.libsignal.messagebackup.MessageBackupKey.fromParts(
                material.macKey,
                material.aesKey,
            )
            MessageBackup.validate(
                libKey,
                MessageBackup.Purpose.DEVICE_TRANSFER,
                { FileInputStream(dest) },
                dest.length(),
            )
            true
        }.onFailure { Timber.w(it, "MessageBackup.validate failed") }
            .getOrDefault(false)
    }
}
