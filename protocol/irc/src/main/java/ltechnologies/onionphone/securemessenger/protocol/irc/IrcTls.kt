package ltechnologies.onionphone.securemessenger.protocol.irc

import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory

/**
 * Kitteh's default TLS trust manager rejects many public IRC CA chains (incl. Let's Encrypt
 * on Libera). Android/JVM system roots accept them once we init the platform TMF with a null
 * KeyStore (default trust store).
 *
 * @see <a href="https://kitteh.dev/kicl/advanced/tls/">Kitteh TLS docs</a>
 */
object IrcTls {
    fun systemTrustManagerFactory(): TrustManagerFactory {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf
    }
}
