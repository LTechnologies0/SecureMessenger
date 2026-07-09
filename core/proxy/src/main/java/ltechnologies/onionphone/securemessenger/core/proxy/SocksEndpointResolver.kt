package ltechnologies.onionphone.securemessenger.core.proxy

import java.io.File
import java.net.InetAddress

/**
 * Resolves the SOCKS endpoint reachable from this Android runtime.
 *
 * On emulators and Waydroid, `127.0.0.1` points at the guest — not the host where
 * system Tor may listen. When the configured host is loopback, we probe the default
 * gateway (and the classic emulator alias) so host-side Tor can be reached.
 */
object SocksEndpointResolver {
    private const val EMULATOR_HOST_ALIAS = "10.0.2.2"
    /** Default Waydroid L2 bridge address on the host. */
    private const val WAYDROID_BRIDGE = "192.168.240.1"
    private const val DEFAULT_SOCKS_PORT = 9050

    /**
     * Returns a SOCKS host that is actually reachable from this process. If [configuredHost]
     * normalizes to loopback, probes the emulator alias, Waydroid bridge, and default gateway
     * (in that order) and returns the first one accepting a TCP connection on [port];
     * falls back to the normalized loopback address if none respond.
     */
    fun resolveReachableHost(configuredHost: String, port: Int = DEFAULT_SOCKS_PORT): String {
        val normalized = ProxyConfigNormalizer.normalizeHost(configuredHost)
        if (normalized != InvizibleConstants.LOOPBACK) return normalized

        val candidates = buildList {
            add(normalized)
            add(EMULATOR_HOST_ALIAS)
            add(WAYDROID_BRIDGE)
            readDefaultGateway()?.let { add(it) }
        }.distinct()

        return candidates.firstOrNull { canConnect(it, port) } ?: normalized
    }

    internal fun readDefaultGateway(): String? = try {
        File("/proc/net/route").useLines { lines ->
            lines.drop(1).firstNotNullOfOrNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 3) return@firstNotNullOfOrNull null
                when (parts[1]) {
                    // Default route
                    "00000000" -> parseHexIpv4(parts[2])
                    // Waydroid / container L2: connected subnet without 0.0.0.0 route
                    else -> readHostBridgeFromConnectedRoute(parts)
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    /** Host-side Tor bridges (emulator alias, Waydroid gateway, loopback). */
    fun isHostBridgeEndpoint(host: String): Boolean {
        val normalized = ProxyConfigNormalizer.normalizeHost(host)
        if (normalized == InvizibleConstants.LOOPBACK) return true
        if (normalized == EMULATOR_HOST_ALIAS) return true
        if (normalized == WAYDROID_BRIDGE) return true
        readDefaultGateway()?.let { if (normalized == it) return true }
        return false
    }

    private fun readHostBridgeFromConnectedRoute(parts: List<String>): String? {
        if (parts.size < 8 || parts[2] != "00000000") return null
        val destination = parts[1].toLongOrNull(16) ?: return null
        val mask = parts[7].toLongOrNull(16) ?: return null
        if (mask != 0x00FFFFFFL) return null
        val network = destination and mask
        // Typical L2 bridge is .1 on the container subnet (e.g. 192.168.240.1).
        val bridge = network or 0x01
        return parseHexIpv4(String.format("%08X", bridge))
    }

    private fun parseHexIpv4(hex: String): String? = try {
        val value = hex.toLong(16)
        InetAddress.getByAddress(
            byteArrayOf(
                (value and 0xFF).toByte(),
                ((value shr 8) and 0xFF).toByte(),
                ((value shr 16) and 0xFF).toByte(),
                ((value shr 24) and 0xFF).toByte(),
            ),
        ).hostAddress
    } catch (_: Exception) {
        null
    }

    private fun canConnect(host: String, port: Int): Boolean = try {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(host, port), 1_500)
        }
        true
    } catch (_: Exception) {
        false
    }
}
