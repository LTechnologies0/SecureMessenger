package ltechnologies.onionphone.securemessenger.core.proxy

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Detects OnionVPN install and launches the app (or GitHub releases if missing).
 * SOCKS endpoint discovery lives in [OnionVpnPacClient].
 */
@Singleton
class OnionVpnHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun isInstalled(): Boolean = findInstalledPackage() != null

    fun findInstalledPackage(): String? =
        runCatching {
            context.packageManager.getPackageInfo(OnionVpnConstants.PACKAGE_NAME, 0)
            OnionVpnConstants.PACKAGE_NAME
        }.getOrNull()

    /** Opens OnionVPN if installed; otherwise opens the releases page. */
    fun openAppOrReleases(): Boolean {
        if (openApp()) return true
        openStoreListing()
        return false
    }

    fun openApp(): Boolean {
        val pkg = findInstalledPackage() ?: return false
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: Intent().setClassName(pkg, OnionVpnConstants.MAIN_ACTIVITY)
        return runCatching {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    fun openStoreListing() {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://github.com/LTechnologies0/OnionVPN/releases"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Timber.w(it, "Cannot open OnionVPN releases") }
    }
}
