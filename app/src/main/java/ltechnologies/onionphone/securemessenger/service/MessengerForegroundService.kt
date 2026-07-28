package ltechnologies.onionphone.securemessenger.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ltechnologies.onionphone.securemessenger.R
import ltechnologies.onionphone.securemessenger.core.proxy.ProxyManager
import ltechnologies.onionphone.securemessenger.core.security.AppLockManager
import ltechnologies.onionphone.securemessenger.ui.MainActivity

/**
 * Keeps Tor/proxy-aware connections alive while the app is unlocked.
 *
 * Heavy deps ([ConnectionManager], [ProxyManager]) are [Lazy] so Hilt field injection
 * during [onCreate] does not open Keystore / SQLCipher before [AppLockManager] unlock.
 */
@AndroidEntryPoint
class MessengerForegroundService : LifecycleService() {

    @Inject lateinit var connectionManager: Lazy<ConnectionManager>
    @Inject lateinit var proxyManager: Lazy<ProxyManager>
    @Inject lateinit var appLockManager: AppLockManager

    override fun onCreate() {
        super.onCreate()
        if (!appLockManager.isUnlocked) {
            stopSelf()
            return
        }
        startForegroundWithNotification()
        val connections = connectionManager.get()
        lifecycleScope.launch {
            proxyManager.get().status
                .map { status -> status.proxyHealthy to status.config }
                .distinctUntilChanged()
                .collect { (healthy, config) ->
                    if (!appLockManager.isUnlocked) return@collect
                    connections.onProxyStateChanged(healthy, config)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!appLockManager.isUnlocked) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundWithNotification()
        // Do not restart while the process is dead / app locked — Keystore & DB need unlock.
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "messenger_connections"
        const val NOTIFICATION_ID = 42
    }
}
