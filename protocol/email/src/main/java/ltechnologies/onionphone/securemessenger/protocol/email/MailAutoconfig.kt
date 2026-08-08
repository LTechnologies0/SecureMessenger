package ltechnologies.onionphone.securemessenger.protocol.email

import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ltechnologies.onionphone.securemessenger.core.network.ProxiedHttpClientFactory
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import timber.log.Timber

data class MailAutoconfigResult(
    val imapHost: String? = null,
    val imapPort: Int? = null,
    val imapSecurity: MailSecurity? = null,
    val pop3Host: String? = null,
    val pop3Port: Int? = null,
    val pop3Security: MailSecurity? = null,
    val smtpHost: String? = null,
    val smtpPort: Int? = null,
    val smtpSecurity: MailSecurity? = null,
    val jmapSessionUrl: String? = null,
    val source: String,
)

@Singleton
class MailAutoconfig @Inject constructor(
    private val httpFactory: ProxiedHttpClientFactory,
) {
    suspend fun detect(email: String): MailAutoconfigResult? = withContext(Dispatchers.IO) {
        val normalized = EmailAddress.requireValid(email)
        val domain = normalized.substringAfter('@')
        detectIspdb(domain)?.let { return@withContext it }
        detectSrv(domain)
    }

    private fun detectIspdb(domain: String): MailAutoconfigResult? {
        return try {
            val client = httpFactory.okhttpClient()
            val url =
                "https://autoconfig.thunderbird.net/v1.1/${URLEncoder.encode(domain, Charsets.UTF_8.name())}"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                parseIspdbXml(response.body.string(), source = "ispdb:$domain")
            }
        } catch (e: Exception) {
            Timber.i(e, "ISPDB autoconfig failed for %s", domain)
            null
        }
    }

    private fun detectSrv(domain: String): MailAutoconfigResult? {
        return try {
            val client = httpFactory.okhttpClient()
            val imaps = lookupSrv(client, "_imaps._tcp.$domain")
            val submission = lookupSrv(client, "_submission._tcp.$domain")
            val pop3s = lookupSrv(client, "_pop3s._tcp.$domain")
            if (imaps == null && submission == null && pop3s == null) return null
            MailAutoconfigResult(
                imapHost = imaps?.target,
                imapPort = imaps?.port ?: 993,
                imapSecurity = MailSecurity.SSL,
                pop3Host = pop3s?.target,
                pop3Port = pop3s?.port ?: 995,
                pop3Security = MailSecurity.SSL,
                smtpHost = submission?.target,
                smtpPort = submission?.port ?: 587,
                smtpSecurity = if ((submission?.port ?: 587) == 465) {
                    MailSecurity.SSL
                } else {
                    MailSecurity.STARTTLS
                },
                source = "srv:$domain",
            )
        } catch (e: Exception) {
            Timber.i(e, "SRV autoconfig failed for %s", domain)
            null
        }
    }

    private data class SrvRecord(val target: String, val port: Int)

    private fun lookupSrv(client: okhttp3.OkHttpClient, name: String): SrvRecord? {
        val url =
            "https://cloudflare-dns.com/dns-query?name=${URLEncoder.encode(name, Charsets.UTF_8.name())}&type=SRV"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body.string()
            val dataRegex = Regex("\"data\"\\s*:\\s*\"([^\"]+)\"")
            val match = dataRegex.find(body) ?: return null
            val parts = match.groupValues[1].trim().split(Regex("\\s+"))
            if (parts.size < 4) return null
            val port = parts[2].toIntOrNull() ?: return null
            val target = parts[3].trimEnd('.').lowercase()
            if (target.isBlank()) return null
            return SrvRecord(target = target, port = port)
        }
    }

    internal fun parseIspdbXml(xml: String, source: String): MailAutoconfigResult? =
        IspdbXmlParser.parse(xml, source)

    private fun socketTypeToSecurity(raw: String?): MailSecurity = IspdbXmlParser.socketTypeToSecurity(raw)
}

internal object IspdbXmlParser {
    fun parse(xml: String, source: String): MailAutoconfigResult? {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xml.reader())

        var incomingType: String? = null
        var inOutgoing = false
        var currentTag: String? = null
        var hostname: String? = null
        var port: Int? = null
        var socketType: String? = null

        var imapHost: String? = null
        var imapPort: Int? = null
        var imapSecurity: MailSecurity? = null
        var pop3Host: String? = null
        var pop3Port: Int? = null
        var pop3Security: MailSecurity? = null
        var smtpHost: String? = null
        var smtpPort: Int? = null
        var smtpSecurity: MailSecurity? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    when (parser.name) {
                        "incomingServer" -> {
                            incomingType = parser.getAttributeValue(null, "type")
                            hostname = null
                            port = null
                            socketType = null
                        }
                        "outgoingServer" -> {
                            inOutgoing = true
                            hostname = null
                            port = null
                            socketType = null
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        when (currentTag) {
                            "hostname" -> hostname = text
                            "port" -> port = text.toIntOrNull()
                            "socketType" -> socketType = text
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "incomingServer" -> {
                            val security = socketTypeToSecurity(socketType)
                            when (incomingType?.lowercase()) {
                                "imap" -> {
                                    imapHost = hostname
                                    imapPort = port
                                    imapSecurity = security
                                }
                                "pop3" -> {
                                    pop3Host = hostname
                                    pop3Port = port
                                    pop3Security = security
                                }
                            }
                        }
                        "outgoingServer" -> {
                            if (inOutgoing) {
                                smtpHost = hostname
                                smtpPort = port
                                smtpSecurity = socketTypeToSecurity(socketType)
                            }
                            inOutgoing = false
                        }
                    }
                    currentTag = null
                }
            }
            event = parser.next()
        }

        if (imapHost == null && pop3Host == null && smtpHost == null) return null
        return MailAutoconfigResult(
            imapHost = imapHost,
            imapPort = imapPort,
            imapSecurity = imapSecurity,
            pop3Host = pop3Host,
            pop3Port = pop3Port,
            pop3Security = pop3Security,
            smtpHost = smtpHost,
            smtpPort = smtpPort,
            smtpSecurity = smtpSecurity,
            source = source,
        )
    }

    fun socketTypeToSecurity(raw: String?): MailSecurity = when (raw?.uppercase()) {
        "SSL", "SSL/TLS" -> MailSecurity.SSL
        "STARTTLS" -> MailSecurity.STARTTLS
        "PLAIN", "NONE" -> MailSecurity.NONE
        else -> MailSecurity.SSL
    }
}
