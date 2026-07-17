package ltechnologies.onionphone.securemessenger.protocol.xmpp

import org.jivesoftware.smack.XMPPConnection
import org.jivesoftware.smack.packet.Message as SmackMessage
import org.jivesoftware.smackx.omemo.OmemoManager
import org.jivesoftware.smackx.omemo.element.OmemoElement
import org.jivesoftware.smackx.omemo.exceptions.UndecidedOmemoIdentityException
import org.jivesoftware.smackx.omemo.internal.OmemoDevice
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint
import org.jivesoftware.smackx.omemo.trust.OmemoTrustCallback
import org.jivesoftware.smackx.omemo.trust.TrustState
import org.jxmpp.jid.impl.JidCreate
import timber.log.Timber
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * TOFU trust for OMEMO — auto-trust on first sight.
 */
class OmemoHelper(
    private val omemoManager: OmemoManager,
    private val connection: XMPPConnection,
) {

    private val trustCallback = object : OmemoTrustCallback {
        override fun getTrust(device: OmemoDevice, fingerprint: OmemoFingerprint): TrustState =
            TrustState.trusted

        override fun setTrust(
            device: OmemoDevice,
            fingerprint: OmemoFingerprint,
            state: TrustState,
        ) = Unit
    }

    @Volatile
    var ready: Boolean = false
        private set

    fun initializeAsync(onReady: () -> Unit = {}) {
        omemoManager.setTrustCallback(trustCallback)
        omemoManager.initializeAsync(object : OmemoManager.InitializationFinishedCallback {
            override fun initializationFinished(manager: OmemoManager?) {
                ready = true
                onReady()
            }

            override fun initializationFailed(exception: Exception?) {
                Timber.w(exception, "OMEMO init failed")
            }
        })
    }

    /** RFC 7627 — block until OMEMO device list is published before encrypting. */
    suspend fun initialize(): Boolean = suspendCancellableCoroutine { cont ->
        if (ready) {
            cont.resume(true)
            return@suspendCancellableCoroutine
        }
        omemoManager.setTrustCallback(trustCallback)
        omemoManager.initializeAsync(object : OmemoManager.InitializationFinishedCallback {
            override fun initializationFinished(manager: OmemoManager?) {
                ready = true
                if (cont.isActive) cont.resume(true)
            }

            override fun initializationFailed(exception: Exception?) {
                Timber.w(exception, "OMEMO init failed")
                if (cont.isActive) cont.resume(false)
            }
        })
    }

    fun sendEncrypted(remoteJid: String, body: String): SmackMessage {
        val jid = JidCreate.bareFrom(remoteJid)
        val sent = try {
            omemoManager.encrypt(jid, body)
        } catch (e: UndecidedOmemoIdentityException) {
            e.undecidedDevices.forEach { device ->
                val fp = omemoManager.getFingerprint(device)
                omemoManager.trustOmemoIdentity(device, fp)
            }
            omemoManager.encrypt(jid, body)
        }
        val builder = connection.stanzaFactory.buildMessageStanza()
        return sent.buildMessage(builder, jid)
    }

    fun encryptMuc(muc: org.jivesoftware.smackx.muc.MultiUserChat, body: String): SmackMessage {
        val sent = try {
            omemoManager.encrypt(muc, body)
        } catch (e: UndecidedOmemoIdentityException) {
            e.undecidedDevices.forEach { device ->
                val fp = omemoManager.getFingerprint(device)
                omemoManager.trustOmemoIdentity(device, fp)
            }
            omemoManager.encrypt(muc, body)
        }
        val builder = connection.stanzaFactory.buildMessageStanza()
        return sent.buildMessage(builder, muc.room)
    }

    fun multiUserChatSupportsOmemo(muc: org.jivesoftware.smackx.muc.MultiUserChat): Boolean = try {
        omemoManager.multiUserChatSupportsOmemo(muc)
    } catch (_: Exception) {
        false
    }

    fun tryDecrypt(remoteJid: String, stanza: SmackMessage): String? {
        if (stanza.getExtension(OmemoElement::class.java) == null) return null
        val element = stanza.getExtension(OmemoElement::class.java) ?: return null
        return try {
            val jid = JidCreate.bareFrom(remoteJid)
            omemoManager.decrypt(jid, element).body
        } catch (e: Exception) {
            Timber.w(e, "OMEMO decrypt failed")
            null
        }
    }

    fun contactSupportsOmemo(remoteJid: String): Boolean = try {
        omemoManager.contactSupportsOmemo(JidCreate.bareFrom(remoteJid))
    } catch (_: Exception) {
        false
    }
}
