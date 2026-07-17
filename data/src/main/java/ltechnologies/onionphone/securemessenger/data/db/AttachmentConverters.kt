package ltechnologies.onionphone.securemessenger.data.db

import androidx.room.TypeConverter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ltechnologies.onionphone.securemessenger.core.model.Attachment
import ltechnologies.onionphone.securemessenger.core.model.AttachmentState

object AttachmentConverters {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class AttachmentDto(
        val id: String,
        val mimeType: String,
        val fileName: String? = null,
        val localPath: String? = null,
        val remoteRef: String? = null,
        val sizeBytes: Long = 0L,
        val state: String = AttachmentState.PENDING.name,
    )

    @TypeConverter
    @JvmStatic
    fun fromAttachments(attachments: List<Attachment>?): String {
        if (attachments.isNullOrEmpty()) return "[]"
        val dtos = attachments.map { it.toDto() }
        return json.encodeToString(dtos)
    }

    @TypeConverter
    @JvmStatic
    fun toAttachments(jsonString: String?): List<Attachment> {
        if (jsonString.isNullOrBlank() || jsonString == "[]") return emptyList()
        return runCatching {
            json.decodeFromString<List<AttachmentDto>>(jsonString).map { it.toDomain() }
        }.getOrElse { emptyList() }
    }

    private fun Attachment.toDto() = AttachmentDto(
        id = id,
        mimeType = mimeType,
        fileName = fileName,
        localPath = localPath,
        remoteRef = remoteRef,
        sizeBytes = sizeBytes,
        state = state.name,
    )

    private fun AttachmentDto.toDomain() = Attachment(
        id = id,
        mimeType = mimeType,
        fileName = fileName,
        localPath = localPath,
        remoteRef = remoteRef,
        sizeBytes = sizeBytes,
        state = runCatching { AttachmentState.valueOf(state) }.getOrDefault(AttachmentState.PENDING),
    )
}
