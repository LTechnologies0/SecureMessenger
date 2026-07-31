package ltechnologies.onionphone.securemessenger.data

import java.io.BufferedWriter
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ltechnologies.onionphone.securemessenger.core.model.BackupExportResult
import ltechnologies.onionphone.securemessenger.core.model.Message
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.data.db.MessageDao
import org.json.JSONObject

/** Writes a local JSON backup of conversations + messages for one account (streamed, off Main). */
@Singleton
class LocalBackupExporter @Inject constructor(
    private val repository: MessengerRepository,
) {
    suspend fun export(
        accountId: String,
        protocol: ProtocolId,
        destinationPath: String,
    ): BackupExportResult = withContext(Dispatchers.IO) {
        try {
            val convs = repository.listConversationsForAccount(accountId)
            val out = File(destinationPath)
            out.parentFile?.mkdirs()
            var messageCount = 0
            out.bufferedWriter().use { writer ->
                writer.append('{')
                writeJsonField(writer, "protocol", protocol.name, first = true)
                writeJsonField(writer, "accountId", accountId)
                writer.append(",\"exportedAt\":").append(System.currentTimeMillis().toString())
                writer.append(",\"conversations\":[")
                convs.forEachIndexed { index, c ->
                    if (index > 0) writer.append(',')
                    writer.append(
                        JSONObject()
                            .put("id", c.id)
                            .put("title", c.title)
                            .put("remoteId", c.remoteId)
                            .put("lastMessageAt", c.lastMessageAt)
                            .toString(),
                    )
                }
                writer.append("],\"messages\":[")
                var firstMessage = true
                for (conv in convs) {
                    var offset = 0
                    while (true) {
                        val page = repository.listMessagesPage(
                            conv.id,
                            MessageDao.EXPORT_PAGE_SIZE,
                            offset,
                        )
                        if (page.isEmpty()) break
                        for (m in page) {
                            if (!firstMessage) writer.append(',')
                            firstMessage = false
                            writer.append(messageJson(m))
                            messageCount++
                        }
                        offset += page.size
                        if (page.size < MessageDao.EXPORT_PAGE_SIZE) break
                    }
                }
                writer.append("]}")
            }
            BackupExportResult.Success(
                uriOrPath = destinationPath,
                messageCount = messageCount,
                conversationCount = convs.size,
            )
        } catch (e: Exception) {
            BackupExportResult.Failure(e.message ?: "Export échoué")
        }
    }

    private fun messageJson(m: Message): String =
        JSONObject()
            .put("id", m.id)
            .put("conversationId", m.conversationId)
            .put("body", m.body)
            .put("timestamp", m.timestamp)
            .put("direction", m.direction.name)
            .put("kind", m.kind.name)
            .put("payloadJson", m.payloadJson)
            .toString()

    private fun writeJsonField(
        writer: BufferedWriter,
        key: String,
        value: String,
        first: Boolean = false,
    ) {
        if (!first) writer.append(',')
        writer.append('"').append(key).append("\":")
        writer.append(JSONObject.quote(value))
    }
}
