package ltechnologies.onionphone.securemessenger.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.protocol.api.MessengerProtocol
import ltechnologies.onionphone.securemessenger.protocol.api.ProtocolRegistry
import ltechnologies.onionphone.securemessenger.protocol.email.EmailProtocol
import ltechnologies.onionphone.securemessenger.protocol.irc.IrcProtocol
import ltechnologies.onionphone.securemessenger.protocol.matrix.MatrixProtocol
import ltechnologies.onionphone.securemessenger.protocol.signal.SignalProtocol
import ltechnologies.onionphone.securemessenger.protocol.telegram.TelegramProtocol
import ltechnologies.onionphone.securemessenger.protocol.xmpp.XmppProtocol

@Module
@InstallIn(SingletonComponent::class)
object ProtocolModule {
    /**
     * Protocols are [dagger.Lazy] so constructing [ConnectionManager] / FGS does not force
     * every protocol graph (MessengerRepository → SQLCipher) until a protocol is first used.
     */
    @Provides
    @Singleton
    fun provideProtocolRegistry(
        xmpp: dagger.Lazy<XmppProtocol>,
        matrix: dagger.Lazy<MatrixProtocol>,
        telegram: dagger.Lazy<TelegramProtocol>,
        signal: dagger.Lazy<SignalProtocol>,
        email: dagger.Lazy<EmailProtocol>,
        irc: dagger.Lazy<IrcProtocol>,
    ): ProtocolRegistry = object : ProtocolRegistry {
        override fun get(id: ProtocolId): MessengerProtocol? = when (id) {
            ProtocolId.XMPP -> xmpp.get()
            ProtocolId.MATRIX -> matrix.get()
            ProtocolId.TELEGRAM -> telegram.get()
            ProtocolId.SIGNAL -> signal.get()
            ProtocolId.EMAIL -> email.get()
            ProtocolId.IRC -> irc.get()
        }

        override fun all(): List<MessengerProtocol> = listOf(
            xmpp.get(),
            matrix.get(),
            telegram.get(),
            signal.get(),
            email.get(),
            irc.get(),
        )
    }
}
