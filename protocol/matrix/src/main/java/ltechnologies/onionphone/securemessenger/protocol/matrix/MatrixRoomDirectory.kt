package ltechnologies.onionphone.securemessenger.protocol.matrix

data class MatrixPublicRoom(
    val roomId: String,
    val name: String?,
    val topic: String?,
    val alias: String?,
    val joinedMembersCount: Long,
    val isSpace: Boolean,
)

data class MatrixHierarchyRoom(
    val roomId: String,
    val name: String?,
    val topic: String?,
    val roomType: String?,
    val childrenStateCount: Int,
)
