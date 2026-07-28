package ltechnologies.onionphone.securemessenger.data

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import ltechnologies.onionphone.securemessenger.core.security.AppLockManager
import ltechnologies.onionphone.securemessenger.core.security.AuthenticatedCrypto

/**
 * SQLCipher passphrase in EncryptedSharedPreferences.
 * Readable only after [AppLockManager] unlock (software gate + lazy prefs open).
 */
@Singleton
class DatabasePassphraseStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLockManager: AppLockManager,
) {
    private val prefs by lazy {
        appLockManager.assertUnlocked()
        val masterKey = AuthenticatedCrypto.createAuthenticatedMasterKey(context)
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun getOrCreatePassphrase(): ByteArray {
        appLockManager.assertUnlocked()
        val existing = prefs.getString(KEY_PASSPHRASE, null)
        if (existing != null) {
            return Base64.decode(existing, Base64.NO_WRAP)
        }
        val generated = AuthenticatedCrypto.generatePassphrase()
        prefs.edit()
            .putString(KEY_PASSPHRASE, Base64.encodeToString(generated, Base64.NO_WRAP))
            .apply()
        return generated
    }

    companion object {
        private const val PREFS_NAME = "secure_messenger_db_key_v2"
        private const val KEY_PASSPHRASE = "sqlcipher_passphrase"
    }
}
