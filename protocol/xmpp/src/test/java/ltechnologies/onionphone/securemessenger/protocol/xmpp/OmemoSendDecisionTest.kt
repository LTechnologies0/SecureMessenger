package ltechnologies.onionphone.securemessenger.protocol.xmpp

import org.junit.Assert.assertEquals
import org.junit.Test

class OmemoSendDecisionTest {

    @Test
    fun discoTimeoutIsNotPlaintext() {
        assertEquals(
            OmemoSendDecision.BLOCK,
            OmemoHelper.decideSend(helperPresent = true, helperReady = true, peerSupports = null),
        )
    }

    @Test
    fun helperMissingAllowsPlaintext() {
        assertEquals(
            OmemoSendDecision.PLAINTEXT,
            OmemoHelper.decideSend(helperPresent = false, helperReady = false, peerSupports = false),
        )
    }

    @Test
    fun notReadyBlocksCleartext() {
        assertEquals(
            OmemoSendDecision.BLOCK,
            OmemoHelper.decideSend(helperPresent = true, helperReady = false, peerSupports = true),
        )
    }

    @Test
    fun knownOmemoPeerEncrypts() {
        assertEquals(
            OmemoSendDecision.ENCRYPT,
            OmemoHelper.decideSend(helperPresent = true, helperReady = true, peerSupports = true),
        )
    }

    @Test
    fun knownCleartextPeerMaySendPlain() {
        assertEquals(
            OmemoSendDecision.PLAINTEXT,
            OmemoHelper.decideSend(helperPresent = true, helperReady = true, peerSupports = false),
        )
    }
}
