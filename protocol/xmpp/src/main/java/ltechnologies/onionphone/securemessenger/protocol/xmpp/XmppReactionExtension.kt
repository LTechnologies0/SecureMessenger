package ltechnologies.onionphone.securemessenger.protocol.xmpp

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.xml.namespace.QName
import org.jivesoftware.smack.packet.ExtensionElement
import org.jivesoftware.smack.packet.Message as SmackMessage
import org.jivesoftware.smack.packet.XmlEnvironment
import org.jivesoftware.smack.provider.ExtensionElementProvider
import org.jivesoftware.smack.provider.ProviderManager
import org.jivesoftware.smack.util.XmlStringBuilder
import org.jivesoftware.smack.xml.XmlPullParser
import org.jivesoftware.smack.xml.XmlPullParserException
import org.jxmpp.JxmppContext

/**
 * XEP-0444 Message Reactions — Smack 4.5 has no built-in manager, so we ship a minimal
 * extension element compatible with Conversations / Dino / Gajim.
 *
 * ```xml
 * <reactions id='origin-or-stanza-id' xmlns='urn:xmpp:reactions:0'>
 *   <reaction>👍</reaction>
 * </reactions>
 * ```
 */
data class XmppReactionExtension(
    val targetId: String,
    val reactions: List<String>,
) : ExtensionElement {

    override fun getElementName(): String = ELEMENT

    override fun getNamespace(): String = NAMESPACE

    override fun toXML(xmlEnvironment: XmlEnvironment?): CharSequence {
        val xml = XmlStringBuilder(this)
        xml.attribute("id", targetId)
        xml.rightAngleBracket()
        for (emoji in reactions) {
            if (emoji.isBlank()) continue
            xml.openElement(REACTION_ELEMENT).escape(emoji).closeElement(REACTION_ELEMENT)
        }
        xml.closeElement(ELEMENT)
        return xml
    }

    companion object {
        const val NAMESPACE = "urn:xmpp:reactions:0"
        const val ELEMENT = "reactions"
        const val REACTION_ELEMENT = "reaction"
        val QNAME: QName = QName(NAMESPACE, ELEMENT)

        private val providerRegistered = AtomicBoolean(false)

        fun ensureProviderRegistered() {
            if (providerRegistered.compareAndSet(false, true)) {
                ProviderManager.addExtensionProvider(ELEMENT, NAMESPACE, Provider())
            }
        }

        fun from(message: SmackMessage): XmppReactionExtension? =
            message.getExtension(QNAME) as? XmppReactionExtension
    }

    class Provider : ExtensionElementProvider<XmppReactionExtension>() {
        @Throws(XmlPullParserException::class, IOException::class)
        override fun parse(
            parser: XmlPullParser,
            initialDepth: Int,
            xmlEnvironment: XmlEnvironment?,
            jxmppContext: JxmppContext?,
        ): XmppReactionExtension {
            val targetId = parser.getAttributeValue(null, "id").orEmpty()
            val emojis = mutableListOf<String>()
            var depth = initialDepth
            while (true) {
                when (parser.next()) {
                    XmlPullParser.Event.START_ELEMENT -> {
                        depth++
                        if (parser.name == REACTION_ELEMENT) {
                            // Collect TEXT_CHARACTERS until END_ELEMENT of <reaction/>.
                            val buf = StringBuilder()
                            while (true) {
                                when (parser.next()) {
                                    XmlPullParser.Event.TEXT_CHARACTERS,
                                    XmlPullParser.Event.ENTITY_REFERENCE,
                                    -> buf.append(parser.text.orEmpty())
                                    XmlPullParser.Event.END_ELEMENT -> {
                                        depth--
                                        break
                                    }
                                    XmlPullParser.Event.START_ELEMENT -> depth++
                                    else -> Unit
                                }
                            }
                            buf.toString().trim().takeIf { it.isNotEmpty() }?.let { emojis += it }
                        }
                    }
                    XmlPullParser.Event.END_ELEMENT -> {
                        depth--
                        if (depth < initialDepth) break
                    }
                    else -> Unit
                }
            }
            return XmppReactionExtension(targetId, emojis)
        }
    }
}
