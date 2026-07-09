package ltechnologies.onionphone.securemessenger.core.proxy

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Reads Orbot STATUS broadcasts and builds SOCKS endpoint config.
 * Port is dynamic — never assume 9050 without querying Orbot.
 */
object OrbotHelper {

    data class OrbotStatus(
        val torRunning: Boolean,
        val status: String,
        val socksHost: String,
        val socksPort: Int,
    )

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(OrbotConstants.PACKAGE_NAME, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun requestStatusBroadcast(context: Context) {
        if (!isInstalled(context)) return
        context.sendBroadcast(
            Intent(OrbotConstants.ACTION_START)
                .setPackage(OrbotConstants.PACKAGE_NAME)
                .putExtra(OrbotConstants.EXTRA_PACKAGE_NAME, context.packageName),
        )
    }

    fun parseStatusIntent(intent: Intent): OrbotStatus? {
        if (intent.action != OrbotConstants.ACTION_STATUS) return null
        val status = intent.getStringExtra(OrbotConstants.EXTRA_STATUS) ?: return null
        val host = intent.getStringExtra(OrbotConstants.EXTRA_SOCKS_PROXY_HOST)
            ?: OrbotConstants.SOCKS_HOST
        val port = resolveSocksPort(intent)
        return OrbotStatus(
            torRunning = status == OrbotConstants.STATUS_ON,
            status = status,
            socksHost = host,
            socksPort = port,
        )
    }

    private fun resolveSocksPort(intent: Intent): Int =
        resolveSocksPort(
            socksPortExtra = intent.getIntExtra(OrbotConstants.EXTRA_SOCKS_PROXY_PORT, -1),
            legacyPortExtra = intent.getIntExtra(OrbotConstants.EXTRA_PROXY_PORT_SOCKS, -1),
            socksUrl = intent.getStringExtra(OrbotConstants.EXTRA_SOCKS_PROXY),
        )

    fun resolveSocksPort(socksPortExtra: Int, legacyPortExtra: Int, socksUrl: String?): Int {
        if (socksPortExtra > 0) return socksPortExtra
        if (legacyPortExtra > 0) return legacyPortExtra
        if (!socksUrl.isNullOrBlank()) {
            Regex(":(\\d+)\\s*$").find(socksUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
                if (it > 0) return it
            }
        }
        return OrbotConstants.SOCKS_PORT
    }
}
