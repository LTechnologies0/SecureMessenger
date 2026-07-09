package ltechnologies.onionphone.securemessenger.data

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import ltechnologies.onionphone.securemessenger.data.db.AccountDao
import ltechnologies.onionphone.securemessenger.data.db.ConversationDao
import ltechnologies.onionphone.securemessenger.data.db.MessageDao
import ltechnologies.onionphone.securemessenger.data.db.MessengerDatabase
import ltechnologies.onionphone.securemessenger.data.db.ProxySettingsDao

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MessengerDatabase =
        Room.databaseBuilder(context, MessengerDatabase::class.java, "secure_messenger.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideAccountDao(db: MessengerDatabase): AccountDao = db.accountDao()
    @Provides fun provideConversationDao(db: MessengerDatabase): ConversationDao = db.conversationDao()
    @Provides fun provideMessageDao(db: MessengerDatabase): MessageDao = db.messageDao()
    @Provides fun provideProxySettingsDao(db: MessengerDatabase): ProxySettingsDao = db.proxySettingsDao()
}
