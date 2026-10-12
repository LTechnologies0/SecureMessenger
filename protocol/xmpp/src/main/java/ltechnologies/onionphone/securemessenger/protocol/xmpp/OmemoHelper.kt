package ltechnologies.onionphone.securemessenger.protocol.xmpp

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jivesoftware.smack.XMPPConnection
import org.jivesoftware.smack.packet.Message as SmackMessage
import org.jivesoftware.smackx.carbons.packet.CarbonExtension
import org.jivesoftware.smackx.forward.packet.Forwarded
import org.jivesoftware.smackx.omemo.OmemoManager
import org.jivesoftware.smackx.omemo.element.OmemoElement
import org.jivesoftware.smackx.omemo.exceptions.UndecidedOmemoIdentityException
import org.jivesoftware.smackx.omemo.internal.OmemoDevice
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint
import org.jivesoftware.smackx.omemo.trust.OmemoTrustCallback
import org.jivesoftware.smackx.omemo.trust.TrustState
import org.jxmpp.jid.impl.JidCreate
import timber.log.Timber

enum class OmemoInitState {
    UNINITIALIZED,
    READY,
    FAILED,
}

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

    private val trustCallbackInstalled = AtomicBoolean(false)

    @Volatile
    var state: OmemoInitState = OmemoInitState.UNINITIALIZED
        private set

    val ready: Boolean
        get() = state == OmemoInitState.READY

    private fun ensureTrustCallbackOnce() {
        if (trustCallbackInstalled.compareAndSet(false, true)) {
            omemoManager.setTrustCallback(trustCallback)
        }
    }

    fun initializeAsync(onReady: () -> Unit = {}) {
        ensureTrustCallbackOnce()
        omemoManager.initializeAsync(object : OmemoManager.InitializationFinishedCallback {
            override fun initializationFinished(manager: OmemoManager?) {
                state = OmemoInitState.READY
                onReady()
            }

            override fun initializationFailed(exception: Exception?) {
                state = OmemoInitState.FAILED
                Timber.w(exception, "OMEMO init failed")
            }
        })
    }

    /** Block until OMEMO device list is published before encrypting. */
    suspend fun initialize(): Boolean = suspendCancellableCoroutine { cont ->
        if (state == OmemoInitState.READY) {
            cont.resume(true)
            return@suspendCancellableCoroutine
        }
        ensureTrustCallbackOnce()
        omemoManager.initializeAsync(object : OmemoManager.InitializationFinishedCallback {
            override fun initializationFinished(manager: OmemoManager?) {
                state = OmemoInitState.READY
                if (cont.isActive) cont.resume(true)
            }

            override fun initializationFailed(exception: Exception?) {
                state = OmemoInitState.FAILED
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

    fun multiUserChatSupportsOmemo(muc: org.jivesoftware.smackx.muc.MultiUserChat): Boolean? = try {
        omemoManager.multiUserChatSupportsOmemo(muc)
    } catch (_: Exception) {
        null
    }

    /**
     * Disco/PEP timeout is **unknown**, not "no OMEMO". Callers must not send cleartext
     * when this returns null (same class as Signal treating HTTP 404 as "no backup").
     */
    fun contactSupportsOmemo(remoteJid: String): Boolean? = try {
        omemoManager.contactSupportsOmemo(JidCreate.bareFrom(remoteJid))
    } catch (_: Exception) {
        null
    }

    companion object {
        /**
         * Honest E2EE: refuse send without OMEMO. No plaintext fallback.
         */
        internal fun decideSend(
            helperPresent: Boolean,
            helperReady: Boolean,
            peerSupports: Boolean?,
        ): OmemoSendDecision {
            if (!helperPresent || !helperReady) return OmemoSendDecision.BLOCK
            return when (peerSupports) {
                true -> OmemoSendDecision.ENCRYPT
                false, null -> OmemoSendDecision.BLOCK
            }
        }

        /**
         * Shared unwrap for live stanzas and MAM. OMEMO ciphertext is only decrypted when
         * [decryptOmemo] is true (helper READY); otherwise returns null for OMEMO payloads.
         */
        fun unwrapChatBody(
            helper: OmemoHelper?,
            remoteJid: String,
            smackMsg: SmackMessage,
            decryptOmemo: Boolean,
        ): String? {
            if (helper?.hasOmemoPayload(smackMsg) == true) {
                if (!decryptOmemo) return null
                return helper.tryDecrypt(remoteJid, smackMsg)
            }
            val carbonDir = CarbonExtension.from(smackMsg)?.direction
            if (carbonDir != null) {
                val forwarded = smackMsg.getExtension(Forwarded::class.java)
                val wrapped = forwarded?.forwardedStanza as? SmackMessage
                if (wrapped != null) {
                    if (helper?.hasOmemoPayload(wrapped) == true) {
                        if (!decryptOmemo) return null
                        return helper.tryDecrypt(remoteJid, wrapped)
                    }
                    wrapped.body?.let { return it }
                }
            }
            return smackMsg.body
        }
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

internal enum class OmemoSendDecision {
    ENCRYPT,
    PLAINTEXT,
    BLOCK,
}
