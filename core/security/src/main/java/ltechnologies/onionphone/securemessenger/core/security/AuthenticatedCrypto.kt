package ltechnologies.onionphone.securemessenger.core.security

import android.content.Context
import android.os.Build
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/** Crypto material bound to the Android device lock (Keystore user-auth). */
object AuthenticatedCrypto {
    const val AUTH_VALIDITY_SECONDS = 300

    fun createAuthenticatedMasterKey(context: Context): MasterKey {
        val builder = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            builder.setUserAuthenticationRequired(true, AUTH_VALIDITY_SECONDS)
        }
        return builder.build()
    }

    fun generatePassphrase(): ByteArray {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes
    }
}
