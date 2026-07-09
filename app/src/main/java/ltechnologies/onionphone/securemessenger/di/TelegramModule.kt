package ltechnologies.onionphone.securemessenger.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import ltechnologies.onionphone.securemessenger.BuildConfig
import ltechnologies.onionphone.securemessenger.protocol.telegram.TelegramApiCredentials

@Module
@InstallIn(SingletonComponent::class)
object TelegramModule {
    @Provides
    @Singleton
    fun provideTelegramApiCredentials(): TelegramApiCredentials = object : TelegramApiCredentials {
        override val apiId: Int = BuildConfig.TELEGRAM_API_ID
        override val apiHash: String = BuildConfig.TELEGRAM_API_HASH
    }
}
