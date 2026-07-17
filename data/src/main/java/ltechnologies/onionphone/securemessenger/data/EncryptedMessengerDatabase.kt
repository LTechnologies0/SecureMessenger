package ltechnologies.onionphone.securemessenger.data

import android.content.Context
import androidx.room.Room
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import ltechnologies.onionphone.securemessenger.core.security.AppLockManager
import ltechnologies.onionphone.securemessenger.data.db.MessengerDatabase
import ltechnologies.onionphone.securemessenger.data.db.MIGRATION_2_3
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Lazily opens the SQLCipher-backed Room database only after [AppLockManager] is unlocked.
 */
@Singleton
class EncryptedMessengerDatabase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val passphraseStore: DatabasePassphraseStore,
    private val appLockManager: AppLockManager,
) {
    @Volatile
    private var instance: MessengerDatabase? = null

    fun get(): MessengerDatabase {
        appLockManager.assertUnlocked()
        return instance ?: synchronized(this) {
            instance ?: buildDatabase().also { instance = it }
        }
    }

    private fun buildDatabase(): MessengerDatabase {
        val factory = SupportOpenHelperFactory(passphraseStore.getOrCreatePassphrase())
        return Room.databaseBuilder(context, MessengerDatabase::class.java, DB_NAME)
            .openHelperFactory(factory)
            .addMigrations(MIGRATION_2_3)
            .fallbackToDestructiveMigrationFrom(1)
            .build()
    }

    companion object {
        private const val DB_NAME = "secure_messenger_encrypted.db"
    }
}
