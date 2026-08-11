package ltechnologies.onionphone.securemessenger.protocol.email

import java.util.Properties
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailAddressTest {
    @Test
    fun acceptsValidAddresses() {
        assertTrue(EmailAddress.isValid("user@example.com"))
        assertTrue(EmailAddress.isValid("first.last+tag@mail.example.org"))
    }

    @Test
    fun rejectsInvalidAddresses() {
        assertFalse(EmailAddress.isValid(""))
        assertFalse(EmailAddress.isValid("not-an-email"))
        assertFalse(EmailAddress.isValid("@example.com"))
        assertFalse(EmailAddress.isValid("user@"))
        assertFalse(EmailAddress.isValid("user@.com"))
    }

    @Test
    fun extractsAngleAddress() {
        assertEquals("ami@example.com", EmailAddress.extract("Ami <ami@example.com>"))
    }
}

class EmailThreadingTest {
    @Test
    fun prefersReferencesRoot() {
        val root = EmailThreading.rootMessageId(
            messageId = "c@x",
            inReplyTo = "b@x",
            references = "<a@x> <b@x>",
        )
        assertEquals("a@x", root)
    }

    @Test
    fun conversationIdIsStable() {
        val a = EmailThreading.conversationId("acc", "root@id")
        val b = EmailThreading.conversationId("acc", "ROOT@ID")
        assertEquals(a, b)
        assertTrue(a.startsWith("acc:thread:"))
    }
}

class EmailSocksPropertiesTest {
    @Test
    fun skipsSocksWhenTorNotRequired() {
        val props = Properties()
        EmailSocksProperties.applySocks(
            props,
            listOf("imap", "smtp"),
            ProxyConfig(host = "127.0.0.1", port = 9050, torRequired = false),
        )
        assertFalse(props.containsKey("mail.imap.socks.host"))
    }

    @Test
    fun appliesSslStoreSecurity() {
        val props = Properties()
        EmailSocksProperties.applyStoreSecurity(
            props,
            "imaps",
            MailSecurity.SSL,
            "imap.example.com",
            993,
        )
        assertEquals("imap.example.com", props["mail.imaps.host"])
        assertEquals("993", props["mail.imaps.port"])
        assertEquals("true", props["mail.imaps.ssl.enable"])
        assertEquals("true", props["mail.imaps.ssl.checkserveridentity"])
    }

    @Test
    fun appliesSslFactoryWhenConnectHostIsIp() {
        val props = Properties()
        EmailSocksProperties.applyStoreSecurity(
            props,
            "imaps",
            MailSecurity.SSL,
            serverName = "imap.example.com",
            port = 993,
            connectHost = "1.2.3.4",
        )
        assertEquals("1.2.3.4", props["mail.imaps.host"])
        assertNotNull(props["mail.imaps.ssl.socketFactory"])
        assertNotNull(props["mail.imaps.ssl.hostnameverifier"])
    }
}

class MailAutoconfigXmlTest {
    @Test
    fun parsesIspdbIncomingOutgoing() {
        val xml = """
            <?xml version="1.0"?>
            <clientConfig version="1.1">
              <emailProvider id="example.com">
                <incomingServer type="imap">
                  <hostname>imap.example.com</hostname>
                  <port>993</port>
                  <socketType>SSL</socketType>
                </incomingServer>
                <outgoingServer type="smtp">
                  <hostname>smtp.example.com</hostname>
                  <port>465</port>
                  <socketType>SSL</socketType>
                </outgoingServer>
              </emailProvider>
            </clientConfig>
        """.trimIndent()
        val result = IspdbXmlParser.parse(xml, "fixture")
        assertNotNull(result)
        assertEquals("imap.example.com", result!!.imapHost)
        assertEquals(993, result.imapPort)
        assertEquals(MailSecurity.SSL, result.imapSecurity)
        assertEquals("smtp.example.com", result.smtpHost)
        assertEquals(465, result.smtpPort)
    }
}
