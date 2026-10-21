package ltechnologies.onionphone.securemessenger.protocol.signal

import android.util.Base64
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper
import org.whispersystems.signalservice.api.account.AccountAttributes
import org.whispersystems.signalservice.api.account.PreKeyCollection
import org.whispersystems.signalservice.api.account.PreKeyUpload
import org.whispersystems.signalservice.api.keys.KeysApi
import org.whispersystems.signalservice.api.push.ServiceIdType
import ltechnologies.onionphone.securemessenger.core.security.EncryptedCredentialStore
import ltechnologies.onionphone.securemessenger.protocol.signal.store.SignalAccountProtocolStore
import org.signal.network.NetworkResult
import java.util.concurrent.TimeUnit
import ltechnologies.onionphone.securemessenger.protocol.signal.store.AndroidSignalProtocolStore

internal data class SignalPreKeyMaterial(
    val aciIdentity: IdentityKeyPair,
    val pniIdentity: IdentityKeyPair,
    val aciRegistrationId: Int,
    val pniRegistrationId: Int,
    val aciPreKeys: PreKeyCollection,
    val pniPreKeys: PreKeyCollection,
    val aciOneTimeEcPreKeys: List<PreKeyRecord>,
    val pniOneTimeEcPreKeys: List<PreKeyRecord>,
    val aciOneTimeKyberPreKeys: List<KyberPreKeyRecord>,
    val pniOneTimeKyberPreKeys: List<KyberPreKeyRecord>,
) {
    fun toSecrets(
        password: String,
        pin: String?,
        deviceId: String = "1",
        sessionReady: Boolean = false,
    ): Map<String, String> = buildMap {
        put(SignalCredentialKeys.ACI_IDENTITY, Base64.encodeToString(aciIdentity.serialize(), Base64.NO_WRAP))
        put(SignalCredentialKeys.PNI_IDENTITY, Base64.encodeToString(pniIdentity.serialize(), Base64.NO_WRAP))
        put(SignalCredentialKeys.ACI_REGISTRATION_ID, aciRegistrationId.toString())
        put(SignalCredentialKeys.PNI_REGISTRATION_ID, pniRegistrationId.toString())
        put(SignalCredentialKeys.ACI_SIGNED_PREKEY, Base64.encodeToString(aciPreKeys.signedPreKey.serialize(), Base64.NO_WRAP))
        put(SignalCredentialKeys.PNI_SIGNED_PREKEY, Base64.encodeToString(pniPreKeys.signedPreKey.serialize(), Base64.NO_WRAP))
        put(SignalCredentialKeys.ACI_KYBER_PREKEY, Base64.encodeToString(aciPreKeys.lastResortKyberPreKey.serialize(), Base64.NO_WRAP))
        put(SignalCredentialKeys.PNI_KYBER_PREKEY, Base64.encodeToString(pniPreKeys.lastResortKyberPreKey.serialize(), Base64.NO_WRAP))
        put(SignalCredentialKeys.PASSWORD, password)
        put(SignalCredentialKeys.DEVICE_ID, deviceId)
        if (sessionReady) {
            put(SignalCredentialKeys.SESSION_READY, "true")
        }
        put("aci:oneTimeEc", SignalAccountProtocolStore.encodePreKeyBlob(aciOneTimeEcPreKeys))
        put("pni:oneTimeEc", SignalAccountProtocolStore.encodePreKeyBlob(pniOneTimeEcPreKeys))
        put("aci:oneTimeKyber", SignalAccountProtocolStore.encodeKyberBlob(aciOneTimeKyberPreKeys))
        put("pni:oneTimeKyber", SignalAccountProtocolStore.encodeKyberBlob(pniOneTimeKyberPreKeys))
        if (pin != null) {
            put(SignalCredentialKeys.REGISTRATION_PIN, pin)
        }
    }

    fun buildAccountAttributes(
        password: String,
        @Suppress("UNUSED_PARAMETER") pin: String?,
        fetchesMessages: Boolean = true,
    ): AccountAttributes {
        // SVR2 PinHash needs an enclave, username, and backup socket (SecureValueRecoveryV2).
        // AccountManager.getSecureValueRecoveryV2 cannot run during registration, so do not
        // send a local SHA-256 stand-in. The PIN stays in REGISTRATION_PIN.
        return AccountAttributes(
            signalingKey = null,
            registrationId = aciRegistrationId,
            fetchesMessages = fetchesMessages,
            registrationLock = null,
            unidentifiedAccessKey = null,
            unrestrictedUnidentifiedAccess = false,
            // Match Signal-Android AppCapabilities.getCapabilities(storageCapable=true).
            capabilities = AccountAttributes.Capabilities(
                storage = true,
                versionedExpirationTimer = true,
                attachmentBackfill = true,
                spqr = true,
                usernameChangeSyncMessage = true,
            ),
            discoverableByPhoneNumber = true,
            name = null,
            pniRegistrationId = pniRegistrationId,
            recoveryPassword = null,
        )
    }

    fun buildDeviceAttributes(
        deviceName: String,
        aciIdentityForName: IdentityKeyPair = aciIdentity,
    ): org.whispersystems.signalservice.api.account.DeviceAttributes {
        // Server expects Base64(DeviceName protobuf), not plaintext — see Signal DeviceNameCipher.
        val encryptedName = org.signal.core.util.crypto.DeviceNameCipher.encryptDeviceName(
            deviceName.toByteArray(Charsets.UTF_8),
            aciIdentityForName,
        )
        val nameB64 = org.signal.core.util.Base64.encodeWithPadding(encryptedName)
        return org.whispersystems.signalservice.api.account.DeviceAttributes(
            fetchesMessages = true,
            registrationId = aciRegistrationId,
            pniRegistrationId = pniRegistrationId,
            name = nameB64,
            // Same flags as AppCapabilities — primary gates attachment backfill on this bit.
            capabilities = AccountAttributes.Capabilities(
                storage = true,
                versionedExpirationTimer = true,
                attachmentBackfill = true,
                spqr = true,
                usernameChangeSyncMessage = true,
            ),
        )
    }

    companion object {
        fun generate(): SignalPreKeyMaterial {
            val aciIdentity = IdentityKeyPair.generate()
            val pniIdentity = IdentityKeyPair.generate()
            return fromIdentities(aciIdentity, pniIdentity)
        }

        /**
         * Signal-Android PreKeyUtil batch size for one-time EC and one-time Kyber prekeys.
         * Vendor PreKeyCollection only carries the signed prekey and the last-resort Kyber key.
         */
        const val ONE_TIME_PREKEY_BATCH = 100

        /** Last-resort Kyber id, kept out of the one-time id range. */
        const val LAST_RESORT_KYBER_PREKEY_ID = 0x00FFFFFF

        /** Matches Signal-Android PreKeysSyncJob.ONE_TIME_PREKEY_MINIMUM. */
        const val ONE_TIME_PREKEY_MINIMUM = 10

        /** Matches Signal-Android PreKeysSyncJob.REFRESH_INTERVAL (signed prekey rotation). */
        val SIGNED_PREKEY_REFRESH_MS = TimeUnit.DAYS.toMillis(2)

        private val STALE_PREKEY_ARCHIVE_MS = TimeUnit.DAYS.toMillis(90)
        private const val STALE_PREKEY_MIN_COUNT = 200

        fun fromIdentities(aciIdentity: IdentityKeyPair, pniIdentity: IdentityKeyPair): SignalPreKeyMaterial {
            val aciRegistrationId = KeyHelper.generateRegistrationId(false)
            val pniRegistrationId = KeyHelper.generateRegistrationId(false)
            val aciSigned = generateSignedPreKey(aciIdentity, 1)
            val pniSigned = generateSignedPreKey(pniIdentity, 1)
            val aciKyber = generateKyberPreKey(aciIdentity, LAST_RESORT_KYBER_PREKEY_ID)
            val pniKyber = generateKyberPreKey(pniIdentity, LAST_RESORT_KYBER_PREKEY_ID)
            val aciOneTimeEc = generateOneTimeEcPreKeys(1)
            val pniOneTimeEc = generateOneTimeEcPreKeys(1)
            val aciOneTimeKyber = generateOneTimeKyberPreKeys(aciIdentity, 1)
            val pniOneTimeKyber = generateOneTimeKyberPreKeys(pniIdentity, 1)
            return SignalPreKeyMaterial(
                aciIdentity = aciIdentity,
                pniIdentity = pniIdentity,
                aciRegistrationId = aciRegistrationId,
                pniRegistrationId = pniRegistrationId,
                aciPreKeys = PreKeyCollection(aciIdentity.publicKey, aciSigned, aciKyber),
                pniPreKeys = PreKeyCollection(pniIdentity.publicKey, pniSigned, pniKyber),
                aciOneTimeEcPreKeys = aciOneTimeEc,
                pniOneTimeEcPreKeys = pniOneTimeEc,
                aciOneTimeKyberPreKeys = aciOneTimeKyber,
                pniOneTimeKyberPreKeys = pniOneTimeKyber,
            )
        }

        fun persistBeforeNetwork(
            store: EncryptedCredentialStore,
            accountId: String,
            material: SignalPreKeyMaterial,
            password: String,
            pin: String?,
            deviceId: String = "1",
        ) {
            material.toSecrets(password, pin, deviceId, sessionReady = false).forEach { (key, value) ->
                store.put(accountId, key, value)
            }
        }

        /** Drop in-flight registration/link secrets when [SESSION_READY] was never set. */
        fun wipeProvisionalCredentialsIfNeeded(store: EncryptedCredentialStore, accountId: String) {
            if (store.get(accountId, SignalCredentialKeys.SESSION_READY) == "true") return
            store.removeAccount(accountId)
        }

        /**
         * Replenish one-time prekeys and rotate signed prekeys when counts or age require it
         * (Signal-Android PreKeysSyncJob parity).
         */
        fun syncPreKeysIfNeeded(
            keysApi: KeysApi,
            credentialStore: EncryptedCredentialStore,
            accountId: String,
            protocolStore: AndroidSignalProtocolStore,
        ) {
            syncIdentityPreKeys(
                keysApi,
                credentialStore,
                accountId,
                ServiceIdType.ACI,
                protocolStore.aciAccountStore(),
            )
            syncIdentityPreKeys(
                keysApi,
                credentialStore,
                accountId,
                ServiceIdType.PNI,
                protocolStore.pniAccountStore(),
            )
        }

        private fun syncIdentityPreKeys(
            keysApi: KeysApi,
            credentialStore: EncryptedCredentialStore,
            accountId: String,
            type: ServiceIdType,
            store: SignalAccountProtocolStore,
        ) {
            val metadata = SignalPreKeyMetadata(credentialStore, accountId, store.storePrefixLabel())
            val counts = when (val result = keysApi.getAvailablePreKeyCountsSync(type)) {
                is NetworkResult.Success -> result.result
                else -> return
            }
            val signedToUpload = signedPreKeyUploadIfNeeded(store, metadata)
            val ecToUpload = if (counts.ecCount < ONE_TIME_PREKEY_MINIMUM) {
                generateAndStoreOneTimeEcPreKeys(store, metadata)
            } else {
                null
            }
            val kyberToUpload = if (counts.kyberCount < ONE_TIME_PREKEY_MINIMUM) {
                generateAndStoreOneTimeKyberPreKeys(store, metadata)
            } else {
                null
            }
            if (signedToUpload == null && ecToUpload == null && kyberToUpload == null) {
                cleanOneTimePreKeys(store)
                return
            }
            val result = keysApi.setPreKeysSync(
                PreKeyUpload(
                    serviceIdType = type,
                    signedPreKey = signedToUpload,
                    oneTimeEcPreKeys = ecToUpload,
                    lastResortKyberPreKey = null,
                    oneTimeKyberPreKeys = kyberToUpload,
                ),
            )
            if (result !is NetworkResult.Success) return
            if (signedToUpload != null) {
                metadata.activeSignedPreKeyId = signedToUpload.id
                metadata.lastSignedPreKeyRotationMs = System.currentTimeMillis()
            }
            cleanOneTimePreKeys(store)
        }

        private fun signedPreKeyUploadIfNeeded(
            store: SignalAccountProtocolStore,
            metadata: SignalPreKeyMetadata,
        ): SignedPreKeyRecord? {
            val activeId = metadata.activeSignedPreKeyId
            val registered = activeId >= 0 && store.containsSignedPreKey(activeId)
            val elapsed = System.currentTimeMillis() - metadata.lastSignedPreKeyRotationMs
            if (registered && elapsed in 0 until SIGNED_PREKEY_REFRESH_MS) return null
            val nextId = if (activeId < 0) {
                store.loadSignedPreKeys().maxOfOrNull { it.id } ?: 0
            } else {
                activeId
            }
            val newId = (nextId + 1) % Int.MAX_VALUE
            val record = generateSignedPreKey(store.getIdentityKeyPair(), newId)
            store.storeSignedPreKey(newId, record)
            return record
        }

        private fun generateAndStoreOneTimeEcPreKeys(
            store: SignalAccountProtocolStore,
            metadata: SignalPreKeyMetadata,
        ): List<PreKeyRecord> {
            val startId = metadata.nextEcOneTimePreKeyId
            store.markAllOneTimeEcPreKeysStaleIfNecessary(System.currentTimeMillis())
            val records = generateOneTimeEcPreKeys(startId)
            for (record in records) {
                store.storePreKey(record.id, record)
            }
            metadata.nextEcOneTimePreKeyId = (records.last().id + 1) % Int.MAX_VALUE
            return records
        }

        private fun generateAndStoreOneTimeKyberPreKeys(
            store: SignalAccountProtocolStore,
            metadata: SignalPreKeyMetadata,
        ): List<KyberPreKeyRecord> {
            val startId = metadata.nextKyberOneTimePreKeyId
            store.markAllOneTimeKyberPreKeysStaleIfNecessary(System.currentTimeMillis())
            val identity = store.getIdentityKeyPair()
            val records = generateOneTimeKyberPreKeys(identity, startId)
            for (record in records) {
                store.storeKyberPreKey(record.id, record)
            }
            metadata.nextKyberOneTimePreKeyId = (records.last().id + 1) % Int.MAX_VALUE
            return records
        }

        private fun cleanOneTimePreKeys(store: SignalAccountProtocolStore) {
            val threshold = System.currentTimeMillis() - STALE_PREKEY_ARCHIVE_MS
            store.deleteAllStaleOneTimeEcPreKeys(threshold, STALE_PREKEY_MIN_COUNT)
            store.deleteAllStaleOneTimeKyberPreKeys(threshold, STALE_PREKEY_MIN_COUNT)
        }

        fun seedMetadataAfterRegistration(
            store: EncryptedCredentialStore,
            accountId: String,
            material: SignalPreKeyMaterial,
        ) {
            val now = System.currentTimeMillis()
            seedIdentityMetadata(store, accountId, "aci", material.aciPreKeys.signedPreKey.id, material.aciOneTimeEcPreKeys, material.aciOneTimeKyberPreKeys, now)
            seedIdentityMetadata(store, accountId, "pni", material.pniPreKeys.signedPreKey.id, material.pniOneTimeEcPreKeys, material.pniOneTimeKyberPreKeys, now)
        }

        private fun seedIdentityMetadata(
            store: EncryptedCredentialStore,
            accountId: String,
            prefix: String,
            signedPreKeyId: Int,
            ecKeys: List<PreKeyRecord>,
            kyberKeys: List<KyberPreKeyRecord>,
            rotationMs: Long,
        ) {
            val meta = SignalPreKeyMetadata(store, accountId, prefix)
            meta.activeSignedPreKeyId = signedPreKeyId
            meta.lastSignedPreKeyRotationMs = rotationMs
            meta.nextEcOneTimePreKeyId = (ecKeys.maxOfOrNull { it.id } ?: 0) + 1
            meta.nextKyberOneTimePreKeyId = (kyberKeys.maxOfOrNull { it.id } ?: 0) + 1
        }

        /**
         * AccountManager has no prekey upload method. KeysApi.setPreKeysSync is the
         * vendored PUT /v2/keys used after the authenticated websocket exists.
         * Signed and last-resort keys are omitted so the server leaves them in place.
         */
        fun uploadOneTimePreKeys(keysApi: KeysApi, aci: SignalAccountProtocolStore, pni: SignalAccountProtocolStore): Boolean {
            val aciOk = uploadIdentity(keysApi, ServiceIdType.ACI, aci)
            val pniOk = uploadIdentity(keysApi, ServiceIdType.PNI, pni)
            return aciOk && pniOk
        }

        private fun uploadIdentity(keysApi: KeysApi, type: ServiceIdType, store: SignalAccountProtocolStore): Boolean {
            val ec = store.oneTimeEcPreKeys()
            val kyber = store.oneTimeKyberPreKeys()
            if (ec.isEmpty() && kyber.isEmpty()) return true
            val result = keysApi.setPreKeysSync(
                PreKeyUpload(
                    serviceIdType = type,
                    signedPreKey = null,
                    oneTimeEcPreKeys = ec,
                    lastResortKyberPreKey = null,
                    oneTimeKyberPreKeys = kyber,
                ),
            )
            return result is NetworkResult.Success
        }

        private fun generateSignedPreKey(identity: IdentityKeyPair, id: Int): SignedPreKeyRecord {
            val keyPair = ECKeyPair.generate()
            val signature = identity.privateKey.calculateSignature(keyPair.publicKey.serialize())
            return SignedPreKeyRecord(id, System.currentTimeMillis(), keyPair, signature)
        }

        private fun generateKyberPreKey(identity: IdentityKeyPair, id: Int): KyberPreKeyRecord {
            val keyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            val signature = identity.privateKey.calculateSignature(keyPair.publicKey.serialize())
            return KyberPreKeyRecord(id, System.currentTimeMillis(), keyPair, signature)
        }

        private fun generateOneTimeEcPreKeys(startId: Int): List<PreKeyRecord> =
            List(ONE_TIME_PREKEY_BATCH) { index ->
                PreKeyRecord(startId + index, ECKeyPair.generate())
            }

        private fun generateOneTimeKyberPreKeys(identity: IdentityKeyPair, startId: Int): List<KyberPreKeyRecord> =
            List(ONE_TIME_PREKEY_BATCH) { index ->
                generateKyberPreKey(identity, startId + index)
            }
    }
}

internal fun loadSignedPreKeyRecord(encoded: String): SignedPreKeyRecord =
    SignedPreKeyRecord(Base64.decode(encoded, Base64.NO_WRAP))

internal fun loadKyberPreKeyRecord(encoded: String): KyberPreKeyRecord =
    KyberPreKeyRecord(Base64.decode(encoded, Base64.NO_WRAP))

internal fun loadIdentityKeyPair(encoded: String): IdentityKeyPair =
    IdentityKeyPair(Base64.decode(encoded, Base64.NO_WRAP))

internal class SignalPreKeyMetadata(
    private val credentialStore: EncryptedCredentialStore,
    private val accountId: String,
    private val prefix: String,
) {
    var nextEcOneTimePreKeyId: Int
        get() = credentialStore.get(accountId, "$prefix:nextEcPreKeyId")?.toIntOrNull() ?: 1
        set(value) {
            credentialStore.put(accountId, "$prefix:nextEcPreKeyId", value.toString())
        }

    var nextKyberOneTimePreKeyId: Int
        get() = credentialStore.get(accountId, "$prefix:nextKyberPreKeyId")?.toIntOrNull() ?: 1
        set(value) {
            credentialStore.put(accountId, "$prefix:nextKyberPreKeyId", value.toString())
        }

    var activeSignedPreKeyId: Int
        get() = credentialStore.get(accountId, "$prefix:activeSignedPreKeyId")?.toIntOrNull() ?: -1
        set(value) {
            credentialStore.put(accountId, "$prefix:activeSignedPreKeyId", value.toString())
        }

    var lastSignedPreKeyRotationMs: Long
        get() = credentialStore.get(accountId, "$prefix:lastSignedPreKeyRotationMs")?.toLongOrNull() ?: 0L
        set(value) {
            credentialStore.put(accountId, "$prefix:lastSignedPreKeyRotationMs", value.toString())
        }
}
