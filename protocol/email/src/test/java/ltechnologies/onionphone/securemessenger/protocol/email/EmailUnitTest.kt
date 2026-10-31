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

    @Test
    fun sentFolderCandidatesNonEmpty() {
        assertTrue(ImapFolderNames.SENT_CANDIDATES.isNotEmpty())
        assertTrue(ImapFolderNames.SENT_CANDIDATES.contains("Sent"))
    }

    @Test
    fun specialFolderCandidatesCoverK9Roles() {
        assertTrue(ImapFolderNames.DRAFTS_CANDIDATES.contains("Drafts"))
        assertTrue(ImapFolderNames.TRASH_CANDIDATES.contains("Trash"))
        assertTrue(ImapFolderNames.ARCHIVE_CANDIDATES.contains("Archive"))
        assertTrue(ImapFolderNames.JUNK_CANDIDATES.contains("Junk"))
        assertEquals(ImapFolderNames.Role.SENT, ImapFolderNames.roleFromName("[Gmail]/Sent Mail"))
        assertEquals(ImapFolderNames.Role.DRAFTS, ImapFolderNames.roleFromName("Drafts"))
        assertEquals(ImapFolderNames.Role.TRASH, ImapFolderNames.roleFromName("INBOX.Trash"))
    }

    @Test
    fun accountConfigPersistsIdentityAndSpecialFolders() {
        val cfg = EmailAccountConfig(
            email = "a@example.com",
            password = "x",
            storeKind = EmailStoreKind.IMAP,
            fromDisplayName = "Alice",
            draftsFolder = "Drafts",
            trashFolder = "Trash",
            imapHost = "imap.example.com",
            smtpHost = "smtp.example.com",
        )
        val secrets = cfg.toSecrets()
        val roundTrip = EmailAccountConfig.fromSecrets(secrets)!!
        assertEquals("Alice", roundTrip.fromDisplayName)
        assertEquals("Drafts", roundTrip.draftsFolder)
        assertEquals("Trash", roundTrip.trashFolder)
    }

    @Test
    fun stripHtmlRemovesTags() {
        assertEquals("Hello world", MimeMapper.stripHtml("<b>Hello</b> <i>world</i>"))
    }

    @Test
    fun parseListSplitsCc() {
        assertEquals(
            listOf("a@example.com", "b@example.org"),
            EmailAddress.parseList("a@example.com, b@example.org"),
        )
        assertEquals(emptyList<String>(), EmailAddress.parseList("not-valid"))
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
    fun appliesSocksAlways() {
        val props = Properties()
        EmailSocksProperties.applySocks(
            props,
            listOf("imap", "smtp"),
            ProxyConfig(host = "127.0.0.1", port = 9050, torRequired = true),
        )
        assertEquals("127.0.0.1", props["mail.imap.socks.host"])
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

    @Test
    fun keepsFirstPreferredServer() {
        val xml = """
            <?xml version="1.0"?>
            <clientConfig version="1.1">
              <emailProvider id="example.com">
                <incomingServer type="imap">
                  <hostname>imap-ssl.example.com</hostname>
                  <port>993</port>
                  <socketType>SSL</socketType>
                </incomingServer>
                <incomingServer type="imap">
                  <hostname>imap-starttls.example.com</hostname>
                  <port>143</port>
                  <socketType>STARTTLS</socketType>
                </incomingServer>
                <outgoingServer type="smtp">
                  <hostname>smtp-ssl.example.com</hostname>
                  <port>465</port>
                  <socketType>SSL</socketType>
                </outgoingServer>
                <outgoingServer type="smtp">
                  <hostname>smtp-starttls.example.com</hostname>
                  <port>587</port>
                  <socketType>STARTTLS</socketType>
                </outgoingServer>
              </emailProvider>
            </clientConfig>
        """.trimIndent()
        val result = IspdbXmlParser.parse(xml, "fixture")
        assertEquals("imap-ssl.example.com", result!!.imapHost)
        assertEquals(993, result.imapPort)
        assertEquals(MailSecurity.SSL, result.imapSecurity)
        assertEquals("smtp-ssl.example.com", result.smtpHost)
        assertEquals(465, result.smtpPort)
        assertEquals(MailSecurity.SSL, result.smtpSecurity)
    }
}
