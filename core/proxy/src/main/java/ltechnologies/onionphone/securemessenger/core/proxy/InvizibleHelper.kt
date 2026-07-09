package ltechnologies.onionphone.securemessenger.core.proxy

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Helper to integrate with [InviZible Pro](https://github.com/Gedsh/InviZible) Tor SOCKS proxy.
 *
 * InviZible does not expose a public Orbot-style STATUS broadcast or an exported service to start
 * Tor from third-party apps. The supported pattern is:
 * 1. User enables Tor in InviZible (and optionally "Allow apps to use Internet via Tor").
 * 2. This app routes traffic through `127.0.0.1:<SOCKSPort>` (default 9050).
 * 3. [requestTorUi] opens InviZible so the user can start Tor if it is not running.
 */
@Singleton
class InvizibleHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun findInstalledPackage(): String? =
        InvizibleConstants.KNOWN_PACKAGES.firstOrNull { isPackageInstalled(it) }

    fun isInstalled(): Boolean = findInstalledPackage() != null

    fun resolveSocksPort(): Int {
        val pkg = findInstalledPackage() ?: return InvizibleConstants.DEFAULT_TOR_SOCKS_PORT
        return readConfiguredSocksPort(pkg)
    }

    /**
     * Reads InviZible default SharedPreferences when accessible (same signing / debug builds may differ).
     */
    fun readConfiguredSocksPort(packageName: String): Int {
        return try {
            val prefs = context.createPackageContext(packageName, 0)
                .getSharedPreferences(InvizibleConstants.PREF_FILE, Context.MODE_PRIVATE)
            prefs.getString(InvizibleConstants.PREF_SOCKS_PORT, null)
                ?.toIntOrNull()
                ?: InvizibleConstants.DEFAULT_TOR_SOCKS_PORT
        } catch (e: Exception) {
            Timber.d(e, "Could not read InviZible SOCKS port; using default")
            InvizibleConstants.DEFAULT_TOR_SOCKS_PORT
        }
    }

    /**
     * Opens InviZible — user must start Tor from the app UI or Quick Settings tile.
     */
    fun requestTorUi(): Boolean {
        val pkg = findInstalledPackage() ?: return false
        return try {
            val launch = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: Intent().setClassName(pkg, InvizibleConstants.MAIN_ACTIVITY)
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            true
        } catch (e: Exception) {
            Timber.w(e, "Failed to open InviZible")
            false
        }
    }

    fun openStoreListing(): Boolean {
        val pkg = findInstalledPackage() ?: InvizibleConstants.PACKAGE_STABLE
        return try {
            val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
            market.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(market)
            true
        } catch (_: Exception) {
            try {
                val web = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://f-droid.org/packages/${InvizibleConstants.PACKAGE_STABLE}/"),
                )
                web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(web)
                true
            } catch (e: Exception) {
                Timber.w(e, "Failed to open InviZible store page")
                false
            }
        }
    }

    suspend fun checkTorSocksHealthy(
        host: String = InvizibleConstants.LOOPBACK,
        port: Int = resolveSocksPort(),
        username: String? = null,
        password: String? = null,
        remoteDns: Boolean = true,
    ): SocksCheckResult {
        return if (remoteDns) {
            SocksConnectivityChecker.checkSocksWithRemoteDns(host, port, username, password)
        } else {
            val ok = SocksConnectivityChecker.checkTcpOnly(host, port)
            if (ok) SocksCheckResult.Success(0) else SocksCheckResult.Failure("TCP unreachable at $host:$port")
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
