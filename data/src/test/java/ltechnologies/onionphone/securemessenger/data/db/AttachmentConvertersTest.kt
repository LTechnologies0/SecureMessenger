package ltechnologies.onionphone.securemessenger.data.db

import ltechnologies.onionphone.securemessenger.core.model.Attachment
import ltechnologies.onionphone.securemessenger.core.model.AttachmentState
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentConvertersTest {
    @Test
    fun roundTripAttachments() {
        val attachments = listOf(
            Attachment(
                id = "a1",
                mimeType = "image/jpeg",
                fileName = "photo.jpg",
                localPath = "/data/cache/photo.jpg",
                remoteRef = "remote-1",
                sizeBytes = 42,
                state = AttachmentState.READY,
            ),
        )
        val json = AttachmentConverters.fromAttachments(attachments)
        val restored = AttachmentConverters.toAttachments(json)
        assertEquals(attachments, restored)
    }
}
