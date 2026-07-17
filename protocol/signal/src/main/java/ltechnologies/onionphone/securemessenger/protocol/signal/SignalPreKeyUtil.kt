package ltechnologies.onionphone.securemessenger.protocol.signal

import android.util.Base64
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper
import org.whispersystems.signalservice.api.account.AccountAttributes
import org.whispersystems.signalservice.api.account.PreKeyCollection

internal data class SignalPreKeyMaterial(
    val aciIdentity: IdentityKeyPair,
    val pniIdentity: IdentityKeyPair,
    val aciRegistrationId: Int,
    val pniRegistrationId: Int,
    val aciPreKeys: PreKeyCollection,
    val pniPreKeys: PreKeyCollection,
) {
    fun toSecrets(password: String, pin: String?): Map<String, String> = mapOf(
        SignalCredentialKeys.ACI_IDENTITY to Base64.encodeToString(aciIdentity.serialize(), Base64.NO_WRAP),
        SignalCredentialKeys.PNI_IDENTITY to Base64.encodeToString(pniIdentity.serialize(), Base64.NO_WRAP),
        SignalCredentialKeys.ACI_REGISTRATION_ID to aciRegistrationId.toString(),
        SignalCredentialKeys.PNI_REGISTRATION_ID to pniRegistrationId.toString(),
        SignalCredentialKeys.ACI_SIGNED_PREKEY to Base64.encodeToString(aciPreKeys.signedPreKey.serialize(), Base64.NO_WRAP),
        SignalCredentialKeys.PNI_SIGNED_PREKEY to Base64.encodeToString(pniPreKeys.signedPreKey.serialize(), Base64.NO_WRAP),
        SignalCredentialKeys.ACI_KYBER_PREKEY to Base64.encodeToString(aciPreKeys.lastResortKyberPreKey.serialize(), Base64.NO_WRAP),
        SignalCredentialKeys.PNI_KYBER_PREKEY to Base64.encodeToString(pniPreKeys.lastResortKyberPreKey.serialize(), Base64.NO_WRAP),
        SignalCredentialKeys.PASSWORD to password,
        SignalCredentialKeys.DEVICE_ID to "1",
        SignalCredentialKeys.SESSION_READY to "true",
    ) + if (pin != null) mapOf(SignalCredentialKeys.REGISTRATION_PIN to pin) else emptyMap()

    fun buildAccountAttributes(
        password: String,
        pin: String?,
        fetchesMessages: Boolean = true,
    ): AccountAttributes {
        val registrationLock = pin?.let { hashRegistrationPin(it) }
        return AccountAttributes(
            signalingKey = null,
            registrationId = aciRegistrationId,
            fetchesMessages = fetchesMessages,
            registrationLock = registrationLock,
            unidentifiedAccessKey = null,
            unrestrictedUnidentifiedAccess = false,
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

    companion object {
        fun generate(): SignalPreKeyMaterial {
            val aciIdentity = IdentityKeyPair.generate()
            val pniIdentity = IdentityKeyPair.generate()
            val aciRegistrationId = KeyHelper.generateRegistrationId(false)
            val pniRegistrationId = KeyHelper.generateRegistrationId(false)
            val aciSigned = generateSignedPreKey(aciIdentity, 1)
            val pniSigned = generateSignedPreKey(pniIdentity, 1)
            val aciKyber = generateKyberPreKey(aciIdentity, 1)
            val pniKyber = generateKyberPreKey(pniIdentity, 1)
            return SignalPreKeyMaterial(
                aciIdentity = aciIdentity,
                pniIdentity = pniIdentity,
                aciRegistrationId = aciRegistrationId,
                pniRegistrationId = pniRegistrationId,
                aciPreKeys = PreKeyCollection(aciIdentity.publicKey, aciSigned, aciKyber),
                pniPreKeys = PreKeyCollection(pniIdentity.publicKey, pniSigned, pniKyber),
            )
        }

        fun hashRegistrationPin(pin: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hashed = digest.digest(pin.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(hashed, Base64.NO_WRAP)
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
    }
}

internal fun loadSignedPreKeyRecord(encoded: String): SignedPreKeyRecord =
    SignedPreKeyRecord(Base64.decode(encoded, Base64.NO_WRAP))

internal fun loadKyberPreKeyRecord(encoded: String): KyberPreKeyRecord =
    KyberPreKeyRecord(Base64.decode(encoded, Base64.NO_WRAP))

internal fun loadIdentityKeyPair(encoded: String): IdentityKeyPair =
    IdentityKeyPair(Base64.decode(encoded, Base64.NO_WRAP))
