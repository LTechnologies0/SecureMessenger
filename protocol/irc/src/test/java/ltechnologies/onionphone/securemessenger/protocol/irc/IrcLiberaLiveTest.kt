package ltechnologies.onionphone.securemessenger.protocol.irc

import net.engio.mbassy.listener.Handler
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.kitteh.irc.client.library.Client
import org.kitteh.irc.client.library.event.client.ClientNegotiationCompleteEvent
import org.kitteh.irc.client.library.event.connection.ClientConnectionClosedEvent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Live TLS handshake against Libera. Skipped unless `-Dirc.live=true`.
 *
 * Run: `./gradlew :protocol:irc:testDebugUnitTest --tests '*.IrcLiberaLiveTest' -Dirc.live=true`
 */
class IrcLiberaLiveTest {
    @Test
    fun connectLiberaTlsWithSystemTrust() {
        assumeTrue(
            "Set -Dirc.live=true or IRC_LIVE=1 to run",
            System.getProperty("irc.live") == "true" ||
                System.getenv("IRC_LIVE") in setOf("1", "true", "yes"),
        )

        val ready = CompletableFuture<String>()
        val failed = CompletableFuture<Throwable>()
        val nick = "smtest${(1000..9999).random()}"

        val client = Client.builder()
            .nick(nick)
            .user("sm")
            .realName("SecureMessenger TLS test")
            .server()
            .host("irc.libera.chat")
            .port(6697, Client.Builder.Server.SecurityType.SECURE)
            .secureTrustManagerFactory(IrcTls.systemTrustManagerFactory())
            .then()
            .build()

        val listener = object {
            @Handler
            fun onReady(event: ClientNegotiationCompleteEvent) {
                ready.complete(event.client.nick)
            }

            @Handler
            fun onClosed(event: ClientConnectionClosedEvent) {
                if (!ready.isDone) {
                    failed.complete(
                        RuntimeException(
                            "closed before ready: ${event.lastMessage.orElse("no message")}",
                        ),
                    )
                }
            }
        }
        client.eventManager.registerEventListener(listener)

        try {
            client.connect()
            try {
                CompletableFuture.anyOf(ready, failed).get(45, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                throw AssertionError("Libera TLS connect timed out (likely SSL or network)", e)
            }
            if (failed.isDone) {
                throw AssertionError("Libera TLS failed", failed.get())
            }
            val nickUsed = ready.get()
            assert(nickUsed.isNotBlank()) { "empty nick after negotiation" }
        } finally {
            runCatching { client.shutdown("test done") }
        }
    }
}
