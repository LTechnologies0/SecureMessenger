package ltechnologies.onionphone.securemessenger.protocol.discord

import javax.inject.Inject
import javax.inject.Singleton
import ltechnologies.onionphone.securemessenger.core.model.FeatureFlags
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.protocol.stub.DisabledMessengerProtocol

@Singleton
class DiscordProtocol @Inject constructor() : DisabledMessengerProtocol(ProtocolId.DISCORD) {
    val isEnabled: Boolean get() = ProtocolId.DISCORD in FeatureFlags.enabled
}
