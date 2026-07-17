package ltechnologies.onionphone.securemessenger.protocol.signal

import org.signal.core.util.Base64
import org.signal.libsignal.metadata.certificate.CertificateValidator
import org.signal.libsignal.protocol.ecc.ECPublicKey

internal object SignalCertificateUtil {
    private val PRODUCTION_TRUST_ROOTS = arrayOf(
        "BXu6QIKVz5MA8gstzfOgRQGqyLqOwNKHL6INkv3IHWMF",
        "BUkY0I+9+oPgDCn4+Ac6Iu813yvqkDr/ga8DzLxFxuk6",
    )

    val validator: CertificateValidator by lazy {
        val roots = PRODUCTION_TRUST_ROOTS.map { encoded ->
            try {
                ECPublicKey(Base64.decode(encoded))
            } catch (e: org.signal.libsignal.protocol.InvalidKeyException) {
                throw IllegalStateException("Invalid Signal trust root", e)
            }
        }
        CertificateValidator(roots)
    }
}
