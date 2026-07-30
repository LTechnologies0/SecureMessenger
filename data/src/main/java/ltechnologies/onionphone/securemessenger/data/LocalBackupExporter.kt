package ltechnologies.onionphone.securemessenger.data

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import ltechnologies.onionphone.securemessenger.core.model.BackupExportResult
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import org.json.JSONArray
import org.json.JSONObject

/** Writes a local JSON backup of conversations + messages for one account. */
@Singleton
class LocalBackupExporter @Inject constructor(
    private val repository: MessengerRepository,
) {
    suspend fun export(accountId: String, protocol: ProtocolId, destinationPath: String): BackupExportResult {
        return try {
            val (convs, messages) = repository.exportSnapshot(accountId)
            val root = JSONObject()
                .put("protocol", protocol.name)
                .put("accountId", accountId)
                .put("exportedAt", System.currentTimeMillis())
            val convArr = JSONArray()
            convs.forEach { c ->
                convArr.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("title", c.title)
                        .put("remoteId", c.remoteId)
                        .put("lastMessageAt", c.lastMessageAt),
                )
            }
            val msgArr = JSONArray()
            messages.forEach { m ->
                msgArr.put(
                    JSONObject()
                        .put("id", m.id)
                        .put("conversationId", m.conversationId)
                        .put("body", m.body)
                        .put("timestamp", m.timestamp)
                        .put("direction", m.direction.name)
                        .put("kind", m.kind.name)
                        .put("payloadJson", m.payloadJson),
                )
            }
            root.put("conversations", convArr)
            root.put("messages", msgArr)
            File(destinationPath).writeText(root.toString(2))
            BackupExportResult.Success(
                uriOrPath = destinationPath,
                messageCount = messages.size,
                conversationCount = convs.size,
            )
        } catch (e: Exception) {
            BackupExportResult.Failure(e.message ?: "Export échoué")
        }
    }
}
