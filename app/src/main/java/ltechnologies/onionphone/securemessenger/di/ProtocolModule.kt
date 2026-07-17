package ltechnologies.onionphone.securemessenger.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.protocol.api.MessengerProtocol
import ltechnologies.onionphone.securemessenger.protocol.api.ProtocolRegistry
import ltechnologies.onionphone.securemessenger.protocol.matrix.MatrixProtocol
import ltechnologies.onionphone.securemessenger.protocol.signal.SignalProtocol
import ltechnologies.onionphone.securemessenger.protocol.telegram.TelegramProtocol
import ltechnologies.onionphone.securemessenger.protocol.xmpp.XmppProtocol

@Singleton
class ProtocolRegistryImpl(
    private val byId: Map<ProtocolId, MessengerProtocol>,
) : ProtocolRegistry {
    override fun get(id: ProtocolId): MessengerProtocol? = byId[id]
    override fun all(): List<MessengerProtocol> = byId.values.toList()
}

@Module
@InstallIn(SingletonComponent::class)
object ProtocolModule {
    @Provides
    @Singleton
    fun provideProtocolRegistry(
        xmpp: XmppProtocol,
        matrix: MatrixProtocol,
        telegram: TelegramProtocol,
        signal: SignalProtocol,
    ): ProtocolRegistry = ProtocolRegistryImpl(
        mapOf(
            ProtocolId.XMPP to xmpp,
            ProtocolId.MATRIX to matrix,
            ProtocolId.TELEGRAM to telegram,
            ProtocolId.SIGNAL to signal,
        ),
    )
}
