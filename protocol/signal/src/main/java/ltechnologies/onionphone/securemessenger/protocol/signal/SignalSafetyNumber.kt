package ltechnologies.onionphone.securemessenger.protocol.signal

import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.whispersystems.signalservice.api.push.SignalServiceAddress

internal object SignalSafetyNumber {
    private const val FINGERPRINT_VERSION = 2
    private const val FINGERPRINT_ITERATIONS = 5200

    fun format(session: SignalSessionContext, remoteId: String): Result<String> {
        return runCatching {
            val remoteAddress = resolveSignalAddress(remoteId)
            val remoteProtocolAddress = SignalProtocolAddress(
                remoteAddress.identifier,
                SignalServiceAddress.DEFAULT_DEVICE_ID,
            )
            val aciStore = session.protocolStore.aciAccountStore()
            val remoteIdentity = aciStore.getIdentity(remoteProtocolAddress)
                ?: error("Pas encore de session chiffrée avec ce contact")
            val localIdentity = aciStore.getIdentityKeyPair().publicKey
            val localIdentifier = identifierBytes(session.localAddress)
            val remoteIdentifier = identifierBytes(remoteAddress)
            val generator = NumericFingerprintGenerator(FINGERPRINT_ITERATIONS)
            val fingerprint = generator.createFor(
                FINGERPRINT_VERSION,
                localIdentifier,
                localIdentity,
                remoteIdentifier,
                remoteIdentity,
            )
            fingerprint.displayableFingerprint.displayText
        }
    }

    private fun identifierBytes(address: SignalServiceAddress): ByteArray {
        val phone = address.number.orElse(null)
        val stable = when {
            phone != null && phone.isNotBlank() -> phone.filter { it.isDigit() }
            else -> address.identifier
        }
        return stable.toByteArray(Charsets.UTF_8)
    }
}
