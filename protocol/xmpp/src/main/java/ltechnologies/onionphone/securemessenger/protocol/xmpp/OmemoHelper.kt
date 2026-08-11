package ltechnologies.onionphone.securemessenger.protocol.xmpp

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
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

/**
 * TOFU trust for OMEMO — first-seen fingerprint trusted; later fingerprint changes stay undecided.
 */
class OmemoHelper(
    private val omemoManager: OmemoManager,
    private val connection: XMPPConnection,
) {

    private val trustedFingerprints = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val trustCallback = object : OmemoTrustCallback {
        override fun getTrust(device: OmemoDevice, fingerprint: OmemoFingerprint): TrustState {
            val key = deviceKey(device)
            val fp = fingerprint.toString()
            val known = trustedFingerprints[key]
            return when {
                known == null -> {
                    trustedFingerprints[key] = fp
                    TrustState.trusted
                }
                known == fp -> TrustState.trusted
                else -> TrustState.undecided
            }
        }

        override fun setTrust(
            device: OmemoDevice,
            fingerprint: OmemoFingerprint,
            state: TrustState,
        ) {
            val key = deviceKey(device)
            when (state) {
                TrustState.trusted -> trustedFingerprints[key] = fingerprint.toString()
                TrustState.untrusted -> trustedFingerprints.remove(key)
                else -> Unit
            }
        }
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

    /** Block until OMEMO device list is published before encrypting. */
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
            resolveUndecidedOrThrow(e)
            omemoManager.encrypt(jid, body)
        }
        val builder = connection.stanzaFactory.buildMessageStanza()
        return sent.buildMessage(builder, jid)
    }

    fun encryptMuc(muc: org.jivesoftware.smackx.muc.MultiUserChat, body: String): SmackMessage {
        val sent = try {
            omemoManager.encrypt(muc, body)
        } catch (e: UndecidedOmemoIdentityException) {
            resolveUndecidedOrThrow(e)
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

    /** True when stanza carries an OMEMO element (caller must fail-closed if decrypt returns null). */
    fun hasOmemoPayload(stanza: SmackMessage): Boolean =
        stanza.getExtension(OmemoElement::class.java) != null

    fun contactSupportsOmemo(remoteJid: String): Boolean = try {
        omemoManager.contactSupportsOmemo(JidCreate.bareFrom(remoteJid))
    } catch (_: Exception) {
        false
    }

    private fun resolveUndecidedOrThrow(e: UndecidedOmemoIdentityException) {
        e.undecidedDevices.forEach { device ->
            val fp = omemoManager.getFingerprint(device)
            if (trustCallback.getTrust(device, fp) != TrustState.trusted) {
                throw SecurityException("OMEMO identity undecided/changed for ${device.jid}/${device.deviceId}")
            }
            omemoManager.trustOmemoIdentity(device, fp)
        }
    }

    private fun deviceKey(device: OmemoDevice): String =
        "${device.jid}|${device.deviceId}"
}
