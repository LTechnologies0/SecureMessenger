package ltechnologies.onionphone.securemessenger.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MessageSanitizerTest {

    @Test
    fun stripsHtmlTags() {
        val result = MessageSanitizer.sanitize("<b>hello</b> world")
        assertEquals("hello world", result.value)
    }

    @Test
    fun rejectsDangerousLinks() {
        assertFalse(MessageSanitizer.isSafeLink("javascript:alert(1)"))
        assertFalse(MessageSanitizer.isSafeLink("data:text/html,evil"))
    }
}
