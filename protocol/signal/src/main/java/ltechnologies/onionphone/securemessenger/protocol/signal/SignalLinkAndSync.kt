package ltechnologies.onionphone.securemessenger.protocol.signal

import android.content.Context
import android.util.Base64
import java.io.File
import java.io.FileInputStream
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
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
import timber.log.Timber

/**
 * Secondary-device link-and-sync: long-poll for a primary-provided transfer archive,
 * download + validate, then import frames into local conversations/messages.
 */
internal object SignalLinkAndSync {
    private const val POLL_TIMEOUT_SEC = 30L

    fun maybeFetchBackup(
        context: Context,
        accountId: String,
        session: SignalSessionContext,
        credentialStore: EncryptedCredentialStore,
        repository: MessengerRepository,
    ) {
        val encodedKey = credentialStore.get(accountId, SignalCredentialKeys.EPHEMERAL_BACKUP_KEY)
            ?: return
        if (credentialStore.get(accountId, SignalCredentialKeys.LINK_SYNC_IMPORTED) == "1") {
            Timber.d("Link-and-sync already imported for %s", accountId)
            return
        }

        val existingPath = credentialStore.get(accountId, SignalCredentialKeys.LINK_SYNC_BACKUP_PATH)
        if (existingPath != null && File(existingPath).exists()) {
            runBlocking {
                SignalBackupImporter.importIfNeeded(accountId, session, credentialStore, repository)
            }
            return
        }

        runCatching {
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
                is NetworkResult.Success -> handleResponse(
                    context,
                    accountId,
                    session,
                    credentialStore,
                    repository,
                    encodedKey,
                    result.result,
                )
                is NetworkResult.StatusCodeError -> {
                    if (result.code == 404) {
                        Timber.i("Link-and-sync transfer_archive 404 — continuing without backup")
                    } else {
                        Timber.w(
                            result.exception,
                            "Link-and-sync transfer_archive HTTP %s",
                            result.code,
                        )
                    }
                }
                is NetworkResult.NetworkError ->
                    Timber.w(result.exception, "Link-and-sync transfer_archive network error")
                is NetworkResult.ApplicationError ->
                    Timber.w(result.throwable, "Link-and-sync transfer_archive application error")
            }
        }.onFailure { Timber.w(it, "Link-and-sync transfer_archive failed (non-fatal)") }
    }

    private fun handleResponse(
        context: Context,
        accountId: String,
        session: SignalSessionContext,
        credentialStore: EncryptedCredentialStore,
        repository: MessengerRepository,
        encodedEphemeralKey: String,
        response: TransferArchiveResponse,
    ) {
        when {
            response.hasArchive -> {
                val cdn = response.cdn ?: return
                val key = response.key ?: return
                val dest = File(context.filesDir, "signal_link_sync_$accountId.bak")
                val receiver = SignalServiceMessageReceiver(session.pushServiceSocket)
                when (val dl = receiver.retrieveLinkAndSyncBackup(cdn, key, dest, null)) {
                    is NetworkResult.Success -> {
                        val valid = validateBackup(session, encodedEphemeralKey, dest)
                        credentialStore.put(
                            accountId,
                            SignalCredentialKeys.LINK_SYNC_BACKUP_PATH,
                            dest.absolutePath,
                        )
                        if (!valid) {
                            Timber.w("Link-and-sync backup validation failed; attempting import anyway")
                        } else {
                            Timber.i(
                                "Link-and-sync backup stored path=%s size=%d",
                                dest.absolutePath,
                                dest.length(),
                            )
                        }
                        runBlocking {
                            SignalBackupImporter.importIfNeeded(
                                accountId,
                                session,
                                credentialStore,
                                repository,
                            )
                        }
                    }
                    else -> Timber.w("Link-and-sync backup download failed: %s", dl)
                }
            }
            response.error == TransferArchiveResponse.ERROR_CONTINUE_WITHOUT_UPLOAD ->
                Timber.i("Link-and-sync: primary CONTINUE_WITHOUT_UPLOAD — continuing")
            response.error == TransferArchiveResponse.ERROR_RELINK_REQUESTED ->
                Timber.i("Link-and-sync: primary RELINK_REQUESTED — continuing without backup")
            !response.error.isNullOrBlank() ->
                Timber.i("Link-and-sync: primary error=%s — continuing", response.error)
            else ->
                Timber.d("Link-and-sync: empty transfer_archive response")
        }
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
