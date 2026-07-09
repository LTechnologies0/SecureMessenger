package ltechnologies.onionphone.securemessenger.protocol.signal

import javax.inject.Inject
import javax.inject.Singleton
import ltechnologies.onionphone.securemessenger.core.model.FeatureFlags
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.protocol.stub.DisabledMessengerProtocol

/** Placeholder for future Molly/Signal AGPL integration. */
@Singleton
class SignalProtocol @Inject constructor() : DisabledMessengerProtocol(ProtocolId.SIGNAL) {
    val isEnabled: Boolean get() = ProtocolId.SIGNAL in FeatureFlags.enabled
}
