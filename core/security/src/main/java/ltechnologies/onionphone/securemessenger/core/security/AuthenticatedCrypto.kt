package ltechnologies.onionphone.securemessenger.core.security

import android.content.Context
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Keystore-backed AES master key for EncryptedSharedPreferences / SQLCipher passphrase wrap.
 *
 * User-auth binding (`setUserAuthenticationRequired`) is intentionally **not** used: opening
 * EncryptedSharedPreferences during Hilt injection / FGS start throws
 * [android.security.keystore.UserNotAuthenticatedException] before [AppLockManager] can prompt.
 * Access control is enforced by [AppLockManager.assertUnlocked] at every read/write.
 *
 * Alias is versioned (`_v2`) so installs that previously created an auth-bound key under the
 * old alias do not keep crashing on MasterKey open.
 */
object AuthenticatedCrypto {
    const val MASTER_KEY_ALIAS = "securemessenger_master_key_v2"

    fun createAuthenticatedMasterKey(context: Context): MasterKey =
        MasterKey.Builder(context, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

    fun generatePassphrase(): ByteArray {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes
    }
}
