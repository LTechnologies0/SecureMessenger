package ltechnologies.onionphone.securemessenger.protocol.signal

import android.util.Base64
import kotlinx.coroutines.flow.first
import ltechnologies.onionphone.securemessenger.core.model.Contact
import ltechnologies.onionphone.securemessenger.core.model.Conversation
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.core.security.EncryptedCredentialStore
import ltechnologies.onionphone.securemessenger.data.MessengerRepository
import org.signal.core.models.AccountEntropyPool
import org.signal.network.NetworkResult
import org.whispersystems.signalservice.api.storage.SignalStorageCipher
import org.whispersystems.signalservice.api.storage.StorageServiceApi
import org.whispersystems.signalservice.internal.storage.protos.ManifestRecord
import org.whispersystems.signalservice.internal.storage.protos.ReadOperation
import org.whispersystems.signalservice.internal.storage.protos.StorageRecord
import timber.log.Timber

/**
 * Pulls Storage Service contacts + GV2 catalog using the Account Entropy Pool from Keys sync.
 * Read-only — does not write back to Storage Service.
 */
internal class SignalStorageSync(
    private val accountId: String,
    private val session: SignalSessionContext,
    private val repository: MessengerRepository,
    private val credentialStore: EncryptedCredentialStore,
    private val groupHelper: SignalGroupHelper,
) {
    data class SyncStats(
        val contacts: Int = 0,
        val groups: Int = 0,
        val profileKeys: Int = 0,
    )

    suspend fun sync(): SyncStats {
        val aepRaw = credentialStore.get(accountId, SignalCredentialKeys.ACCOUNT_ENTROPY_POOL)
            ?.takeIf { it.isNotBlank() }
            ?: return SyncStats().also { Timber.d("Storage sync skipped — no AEP") }

        return try {
            val storageKey = AccountEntropyPool(aepRaw).deriveMasterKey().deriveStorageServiceKey()
            val api = StorageServiceApi(session.authWebSocket, session.pushServiceSocket)
            val auth = when (val authResult = api.getAuth()) {
                is NetworkResult.Success -> authResult.result
                else -> {
                    Timber.w("Storage auth failed: %s", authResult)
                    return SyncStats()
                }
            }
            val manifest = when (val manifestResult = api.getStorageManifest(auth)) {
                is NetworkResult.Success -> manifestResult.result
                else -> {
                    Timber.w("Storage manifest fetch failed: %s", manifestResult)
                    return SyncStats()
                }
            }
            val version = manifest.version
            val localVersion = credentialStore.get(accountId, SignalCredentialKeys.STORAGE_MANIFEST_VERSION)
                ?.toLongOrNull()
            if (localVersion != null && localVersion == version) {
                Timber.d("Storage manifest already at version %d", version)
                return SyncStats()
            }

            val decryptedManifest = SignalStorageCipher.decrypt(
                storageKey.deriveManifestKey(version),
                manifest.value_.toByteArray(),
            )
            val record = ManifestRecord.ADAPTER.decode(decryptedManifest)
            val identifiers = record.identifiers.filter { it.raw.size > 0 }
            if (identifiers.isEmpty()) {
                credentialStore.put(accountId, SignalCredentialKeys.STORAGE_MANIFEST_VERSION, version.toString())
                return SyncStats()
            }

            var contactsApplied = 0
            var groupsApplied = 0
            var profileKeys = 0
            val mergedContacts = repository.observeContacts(accountId).first()
                .associateBy { it.remoteId }
                .toMutableMap()

            for (chunk in identifiers.chunked(READ_BATCH)) {
                val readOp = ReadOperation(readKey = chunk.map { it.raw })
                val items = when (val readResult = api.readStorageItems(auth, readOp)) {
                    is NetworkResult.Success -> readResult.result.items
                    else -> {
                        Timber.w("Storage read failed: %s", readResult)
                        emptyList()
                    }
                }
                val typeByKey = chunk.associateBy { it.raw }
                for (item in items) {
                    if (item.key.size == 0 || item.value_.size == 0) continue
                    val type = typeByKey[item.key]?.type ?: ManifestRecord.Identifier.Type.UNKNOWN
                    val itemKey = storageKey.deriveItemKey(item.key.toByteArray())
                    val plaintext = runCatching {
                        SignalStorageCipher.decrypt(itemKey, item.value_.toByteArray())
                    }.onFailure { Timber.w(it, "Storage item decrypt failed") }.getOrNull() ?: continue
                    val storageRecord = runCatching {
                        StorageRecord.ADAPTER.decode(plaintext)
                    }.getOrNull() ?: continue

                    when (type) {
                        ManifestRecord.Identifier.Type.CONTACT -> {
                            val contact = storageRecord.contact ?: continue
                            val remoteId = contact.aci.takeIf { it.isNotBlank() }
                                ?: contact.e164.takeIf { it.isNotBlank() }
                                ?: continue
                            if (contact.profileKey.size > 0 && contact.aci.isNotBlank()) {
                                credentialStore.put(
                                    accountId,
                                    SignalCredentialKeys.peerProfileKey(contact.aci),
                                    Base64.encodeToString(contact.profileKey.toByteArray(), Base64.NO_WRAP),
                                )
                                profileKeys++
                            }
                            val displayName = listOf(contact.givenName, contact.familyName)
                                .filter { it.isNotBlank() }
                                .joinToString(" ")
                                .ifBlank { contact.e164.ifBlank { remoteId } }
                            mergedContacts[remoteId] = Contact(
                                id = "${accountId}_$remoteId",
                                protocol = ProtocolId.SIGNAL,
                                accountId = accountId,
                                remoteId = remoteId,
                                displayName = displayName,
                                handle = contact.aci.takeIf { it.isNotBlank() },
                                phone = contact.e164.takeIf { it.isNotBlank() },
                            )
                            contactsApplied++
                        }
                        ManifestRecord.Identifier.Type.GROUPV2 -> {
                            val group = storageRecord.groupV2 ?: continue
                            if (group.blocked || group.masterKey.size == 0) continue
                            val masterKey = group.masterKey.toByteArray()
                            val remoteId = "gv2:" + Base64.encodeToString(masterKey, Base64.NO_WRAP)
                            groupHelper.refreshFromNetwork(session, masterKey)
                            val title = groupHelper.cachedTitle(masterKey) ?: "Groupe Signal"
                            val conversationId = signalConversationId(accountId, remoteId)
                            val existing = repository.getConversation(conversationId)
                            if (existing == null) {
                                repository.upsertConversation(
                                    Conversation(
                                        id = conversationId,
                                        protocol = ProtocolId.SIGNAL,
                                        accountId = accountId,
                                        remoteId = remoteId,
                                        title = title,
                                        lastMessageAt = 0L,
                                        unreadCount = 0,
                                    ),
                                )
                            } else if (existing.title != title && title != "Groupe Signal") {
                                repository.upsertConversation(existing.copy(title = title))
                            }
                            groupsApplied++
                        }
                        else -> Unit
                    }
                }
            }

            if (mergedContacts.isNotEmpty()) {
                repository.replaceContacts(accountId, mergedContacts.values.toList())
            }
            credentialStore.put(accountId, SignalCredentialKeys.STORAGE_MANIFEST_VERSION, version.toString())
            Timber.i(
                "Storage sync done version=%d contacts=%d groups=%d profileKeys=%d",
                version,
                contactsApplied,
                groupsApplied,
                profileKeys,
            )
            SyncStats(contactsApplied, groupsApplied, profileKeys)
        } catch (e: Exception) {
            Timber.w(e, "Storage sync failed")
            SyncStats()
        }
    }

    companion object {
        private const val READ_BATCH = 100
    }
}
