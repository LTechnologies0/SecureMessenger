package ltechnologies.onionphone.securemessenger.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationIdsTest {
    @Test
    fun encodeDecodeRoundTrip() {
        val acc = "550e8400-e29b-41d4-a716-446655440000"
        val remote = "!abc:matrix.org"
        val id = ConversationIds.encode(acc, remote)
        assertEquals(acc, ConversationIds.accountId(id))
        assertEquals(remote, ConversationIds.remoteId(id))
        assertEquals(remote, ConversationIds.remoteId(id, acc))
    }

    @Test
    fun remoteWithUnderscoreNeedsAccountHint() {
        val acc = "550e8400-e29b-41d4-a716-446655440000"
        val remote = "gv2:abc_def_ghi"
        val id = ConversationIds.encode(acc, remote)
        assertEquals(acc, ConversationIds.accountId(id))
        assertEquals(remote, ConversationIds.remoteId(id))
        assertEquals(remote, ConversationIds.remoteId(id, acc))
    }

    @Test
    fun emailScheme() {
        val id = "acc-1:mailbox:user@example.com"
        assertTrue(ConversationIds.isEmailScheme(id))
        assertEquals("acc-1", ConversationIds.accountId(id))
        assertEquals("acc-1", ConversationIds.emailAccountId(id))
        assertNull(ConversationIds.remoteId(id))
        assertFalse(ConversationIds.isEmailScheme("acc_!room:server"))
    }
}
