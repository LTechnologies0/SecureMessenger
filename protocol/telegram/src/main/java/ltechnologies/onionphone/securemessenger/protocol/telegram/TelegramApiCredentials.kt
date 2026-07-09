package ltechnologies.onionphone.securemessenger.protocol.telegram

interface TelegramApiCredentials {
    val apiId: Int
    val apiHash: String

    fun isConfigured(): Boolean = apiId != 0 && apiHash.isNotBlank()
}
