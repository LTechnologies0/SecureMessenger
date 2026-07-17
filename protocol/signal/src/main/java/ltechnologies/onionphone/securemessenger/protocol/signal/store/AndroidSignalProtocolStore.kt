package ltechnologies.onionphone.securemessenger.protocol.signal.store

import android.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.signal.core.models.ServiceId
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.whispersystems.signalservice.api.SignalServiceAccountDataStore
import org.whispersystems.signalservice.api.SignalServiceDataStore
import org.whispersystems.signalservice.api.push.DistributionId
import ltechnologies.onionphone.securemessenger.core.security.EncryptedCredentialStore
import ltechnologies.onionphone.securemessenger.protocol.signal.SignalCredentialKeys
import ltechnologies.onionphone.securemessenger.protocol.signal.loadIdentityKeyPair
import ltechnologies.onionphone.securemessenger.protocol.signal.loadKyberPreKeyRecord
import ltechnologies.onionphone.securemessenger.protocol.signal.loadSignedPreKeyRecord

/**
 * Per-account Signal protocol store backed by encrypted credentials for long-lived keys,
 * sessions, and sender keys.
 */
internal class SignalAccountProtocolStore(
    private val identityKeyPair: IdentityKeyPair,
    private val registrationId: Int,
    signedPreKeyRecord: SignedPreKeyRecord,
    kyberPreKeyRecord: KyberPreKeyRecord,
    private val credentialStore: EncryptedCredentialStore? = null,
    private val accountId: String? = null,
    private val storePrefix: String = "aci",
) : SignalServiceAccountDataStore {

    private val sessions = ConcurrentHashMap<SignalProtocolAddress, SessionRecord>()
    private val preKeys = ConcurrentHashMap<Int, PreKeyRecord>()
    private val signedPreKeys = ConcurrentHashMap<Int, SignedPreKeyRecord>()
    private val kyberPreKeys = ConcurrentHashMap<Int, KyberPreKeyRecord>()
    private val lastResortKyberPreKeys = ConcurrentHashMap<Int, KyberPreKeyRecord>()
    private val identities = ConcurrentHashMap<String, IdentityKey>()
    private val senderKeys = ConcurrentHashMap<SenderKeyId, SenderKeyRecord>()
    private val senderKeyShared = ConcurrentHashMap<DistributionId, MutableSet<SignalProtocolAddress>>()

    @Volatile
    private var multiDevice: Boolean = false

    init {
        signedPreKeys[signedPreKeyRecord.id] = signedPreKeyRecord
        kyberPreKeys[kyberPreKeyRecord.id] = kyberPreKeyRecord
        lastResortKyberPreKeys[kyberPreKeyRecord.id] = kyberPreKeyRecord
        restorePersistedState()
    }

    private fun restorePersistedState() {
        val store = credentialStore ?: return
        val acc = accountId ?: return
        val all = store.getAllForAccount(acc)
        val sessionPrefix = "$storePrefix:session:"
        val senderPrefix = "$storePrefix:senderkey:"
        val sharedPrefix = "$storePrefix:senderkeyshared:"
        val identityPrefix = "$storePrefix:identity:"
        for ((key, value) in all) {
            when {
                key.startsWith(sessionPrefix) -> {
                    val parts = key.removePrefix(sessionPrefix).split(':', limit = 2)
                    if (parts.size == 2) {
                        val deviceId = parts[1].toIntOrNull() ?: continue
                        runCatching {
                            sessions[SignalProtocolAddress(parts[0], deviceId)] =
                                SessionRecord(Base64.decode(value, Base64.NO_WRAP))
                        }
                    }
                }
                key.startsWith(senderPrefix) -> {
                    val parts = key.removePrefix(senderPrefix).split(':', limit = 3)
                    if (parts.size == 3) {
                        val deviceId = parts[1].toIntOrNull() ?: continue
                        val dist = runCatching { UUID.fromString(parts[2]) }.getOrNull() ?: continue
                        runCatching {
                            senderKeys[SenderKeyId(SignalProtocolAddress(parts[0], deviceId), dist)] =
                                SenderKeyRecord(Base64.decode(value, Base64.NO_WRAP))
                        }
                    }
                }
                key.startsWith(sharedPrefix) -> {
                    val dist = runCatching {
                        DistributionId.from(UUID.fromString(key.removePrefix(sharedPrefix)))
                    }.getOrNull() ?: continue
                    val addresses = value.split(',').mapNotNull { token ->
                        val p = token.split(':', limit = 2)
                        if (p.size != 2) return@mapNotNull null
                        val deviceId = p[1].toIntOrNull() ?: return@mapNotNull null
                        SignalProtocolAddress(p[0], deviceId)
                    }
                    if (addresses.isNotEmpty()) {
                        senderKeyShared[dist] = addresses.toMutableSet()
                    }
                }
                key.startsWith(identityPrefix) -> {
                    val name = key.removePrefix(identityPrefix)
                    runCatching {
                        identities[name] = IdentityKey(Base64.decode(value, Base64.NO_WRAP), 0)
                    }
                }
            }
        }
    }

    private fun persist(key: String, value: String) {
        val store = credentialStore ?: return
        val acc = accountId ?: return
        store.put(acc, key, value)
    }

    override fun isMultiDevice(): Boolean = multiDevice

    override fun setMultiDevice(isMultiDevice: Boolean) {
        multiDevice = isMultiDevice
    }

    override fun getIdentityKeyPair(): IdentityKeyPair = identityKeyPair

    override fun getLocalRegistrationId(): Int = registrationId

    override fun saveIdentity(
        address: SignalProtocolAddress,
        identity: IdentityKey,
    ): IdentityKeyStore.IdentityChange {
        val existing = identities.put(address.name, identity)
        persist(
            "$storePrefix:identity:${address.name}",
            Base64.encodeToString(identity.serialize(), Base64.NO_WRAP),
        )
        return when {
            existing == null -> IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
            existing == identity -> IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
            else -> IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        }
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identity: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean {
        val existing = identities[address.name] ?: return true
        return existing == identity
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? = identities[address.name]

    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        preKeys[preKeyId] ?: throw InvalidKeyIdException("Unknown prekey $preKeyId")

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        preKeys[preKeyId] = record
    }

    override fun containsPreKey(preKeyId: Int): Boolean = preKeys.containsKey(preKeyId)

    override fun removePreKey(preKeyId: Int) {
        preKeys.remove(preKeyId)
    }

    override fun markAllOneTimeEcPreKeysStaleIfNecessary(staleTime: Long) = Unit

    override fun deleteAllStaleOneTimeEcPreKeys(threshold: Long, minCount: Int) = Unit

    override fun loadSession(address: SignalProtocolAddress): SessionRecord =
        sessions[address] ?: SessionRecord()

    override fun loadExistingSessions(addresses: MutableList<SignalProtocolAddress>): MutableList<SessionRecord> {
        val loaded = ArrayList<SessionRecord>(addresses.size)
        for (address in addresses) {
            if (!sessions.containsKey(address)) {
                throw NoSessionException(address, "No session for ${address.name}")
            }
            loaded.add(sessions.getValue(address))
        }
        return loaded
    }

    override fun getSubDeviceSessions(name: String): MutableList<Int> =
        sessions.keys
            .filter { it.name == name }
            .map { it.deviceId }
            .toMutableList()

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        sessions[address] = record
        persist(
            "$storePrefix:session:${address.name}:${address.deviceId}",
            Base64.encodeToString(record.serialize(), Base64.NO_WRAP),
        )
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean = sessions.containsKey(address)

    override fun deleteSession(address: SignalProtocolAddress) {
        sessions.remove(address)
    }

    override fun deleteAllSessions(name: String) {
        sessions.keys.removeIf { it.name == name }
    }

    override fun archiveSession(address: SignalProtocolAddress) {
        sessions.remove(address)
    }

    override fun getAllAddressesWithActiveSessions(addressNames: MutableList<String>): MutableMap<SignalProtocolAddress, SessionRecord> {
        val result = LinkedHashMap<SignalProtocolAddress, SessionRecord>()
        for (address in sessions.keys) {
            if (addressNames.contains(address.name)) {
                result[address] = sessions.getValue(address)
            }
        }
        return result
    }

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        signedPreKeys[signedPreKeyId] ?: throw InvalidKeyIdException("Unknown signed prekey $signedPreKeyId")

    override fun loadSignedPreKeys(): MutableList<SignedPreKeyRecord> = signedPreKeys.values.toMutableList()

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        signedPreKeys[signedPreKeyId] = record
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = signedPreKeys.containsKey(signedPreKeyId)

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        signedPreKeys.remove(signedPreKeyId)
    }

    override fun storeSenderKey(address: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) {
        senderKeys[SenderKeyId(address, distributionId)] = record
        persist(
            "$storePrefix:senderkey:${address.name}:${address.deviceId}:$distributionId",
            Base64.encodeToString(record.serialize(), Base64.NO_WRAP),
        )
    }

    override fun loadSenderKey(address: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord =
        senderKeys[SenderKeyId(address, distributionId)] ?: SenderKeyRecord(0L)

    override fun getSenderKeySharedWith(distributionId: DistributionId): MutableSet<SignalProtocolAddress> =
        senderKeyShared.getOrPut(distributionId) { ConcurrentHashMap.newKeySet() }

    override fun markSenderKeySharedWith(
        distributionId: DistributionId,
        addresses: MutableCollection<SignalProtocolAddress>,
    ) {
        getSenderKeySharedWith(distributionId).addAll(addresses)
        val encoded = getSenderKeySharedWith(distributionId).joinToString(",") {
            "${it.name}:${it.deviceId}"
        }
        persist("$storePrefix:senderkeyshared:${distributionId.asUuid()}", encoded)
    }

    override fun clearSenderKeySharedWith(addresses: MutableCollection<SignalProtocolAddress>) {
        for (entry in senderKeyShared.entries) {
            entry.value.removeAll(addresses.toSet())
        }
    }

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        kyberPreKeys[kyberPreKeyId] ?: throw InvalidKeyIdException("Unknown kyber prekey $kyberPreKeyId")

    override fun loadKyberPreKeys(): MutableList<KyberPreKeyRecord> = kyberPreKeys.values.toMutableList()

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        kyberPreKeys[kyberPreKeyId] = record
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = kyberPreKeys.containsKey(kyberPreKeyId)

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, signedPreKey: ECPublicKey) {
        if (!kyberPreKeys.containsKey(kyberPreKeyId)) {
            throw ReusedBaseKeyException()
        }
        kyberPreKeys.remove(kyberPreKeyId)
    }

    override fun storeLastResortKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        lastResortKyberPreKeys[kyberPreKeyId] = record
        storeKyberPreKey(kyberPreKeyId, record)
    }

    override fun loadLastResortKyberPreKeys(): MutableList<KyberPreKeyRecord> =
        lastResortKyberPreKeys.values.toMutableList()

    override fun removeKyberPreKey(kyberPreKeyId: Int) {
        kyberPreKeys.remove(kyberPreKeyId)
        lastResortKyberPreKeys.remove(kyberPreKeyId)
    }

    override fun markAllOneTimeKyberPreKeysStaleIfNecessary(staleTime: Long) = Unit

    override fun deleteAllStaleOneTimeKyberPreKeys(threshold: Long, minCount: Int) = Unit

    private data class SenderKeyId(val address: SignalProtocolAddress, val distributionId: UUID)
}

internal class AndroidSignalProtocolStore private constructor(
    private val aciStore: SignalAccountProtocolStore,
    private val pniStore: SignalAccountProtocolStore,
) : SignalServiceDataStore {

    override fun get(accountIdentifier: ServiceId): SignalServiceAccountDataStore =
        when (accountIdentifier) {
            is ServiceId.ACI -> aciStore
            is ServiceId.PNI -> pniStore
            else -> aciStore
        }

    override fun aci(): SignalServiceAccountDataStore = aciStore

    override fun pni(): SignalServiceAccountDataStore = pniStore

    override fun isMultiDevice(): Boolean = aciStore.isMultiDevice()

    companion object {
        fun fromSecrets(
            credentialStore: EncryptedCredentialStore,
            accountId: String,
            secrets: Map<String, String> = credentialStore.getAllForAccount(accountId),
        ): AndroidSignalProtocolStore {
            val aciIdentity = secrets[SignalCredentialKeys.ACI_IDENTITY]
                ?.let(::loadIdentityKeyPair)
                ?: error("ACI identity key missing")
            val pniIdentity = secrets[SignalCredentialKeys.PNI_IDENTITY]
                ?.let(::loadIdentityKeyPair)
                ?: error("PNI identity key missing")
            val aciRegistrationId = secrets[SignalCredentialKeys.ACI_REGISTRATION_ID]?.toIntOrNull()
                ?: error("ACI registration id missing")
            val pniRegistrationId = secrets[SignalCredentialKeys.PNI_REGISTRATION_ID]?.toIntOrNull()
                ?: error("PNI registration id missing")
            val aciSigned = secrets[SignalCredentialKeys.ACI_SIGNED_PREKEY]
                ?.let(::loadSignedPreKeyRecord)
                ?: error("ACI signed prekey missing")
            val pniSigned = secrets[SignalCredentialKeys.PNI_SIGNED_PREKEY]
                ?.let(::loadSignedPreKeyRecord)
                ?: error("PNI signed prekey missing")
            val aciKyber = secrets[SignalCredentialKeys.ACI_KYBER_PREKEY]
                ?.let(::loadKyberPreKeyRecord)
                ?: error("ACI kyber prekey missing")
            val pniKyber = secrets[SignalCredentialKeys.PNI_KYBER_PREKEY]
                ?.let(::loadKyberPreKeyRecord)
                ?: error("PNI kyber prekey missing")

            return AndroidSignalProtocolStore(
                aciStore = SignalAccountProtocolStore(
                    aciIdentity,
                    aciRegistrationId,
                    aciSigned,
                    aciKyber,
                    credentialStore,
                    accountId,
                    "aci",
                ),
                pniStore = SignalAccountProtocolStore(
                    pniIdentity,
                    pniRegistrationId,
                    pniSigned,
                    pniKyber,
                    credentialStore,
                    accountId,
                    "pni",
                ),
            )
        }

        fun persistSession(
            credentialStore: EncryptedCredentialStore,
            accountId: String,
            address: SignalProtocolAddress,
            record: SessionRecord,
        ) {
            val encoded = Base64.encodeToString(record.serialize(), Base64.NO_WRAP)
            credentialStore.put(accountId, sessionKey(address), encoded)
        }

        private fun sessionKey(address: SignalProtocolAddress): String =
            "session:${address.name}:${address.deviceId}"
    }
}
