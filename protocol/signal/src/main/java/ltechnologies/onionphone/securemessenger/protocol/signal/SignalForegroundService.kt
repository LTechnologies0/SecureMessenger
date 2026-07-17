package ltechnologies.onionphone.securemessenger.protocol.signal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class SignalForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val accountId = intent?.getStringExtra(EXTRA_ACCOUNT_ID) ?: "signal"
        ensureChannel()
        val notification = buildNotification(accountId)
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Signal (Tor)",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(accountId: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Signal via Tor")
            .setContentText("Session active — $accountId")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "signal_tor_sync"
        private const val NOTIFICATION_ID = 4401
        private const val EXTRA_ACCOUNT_ID = "accountId"
        private const val ACTION_STOP = "stop"

        fun start(context: Context, accountId: String) {
            val intent = Intent(context, SignalForegroundService::class.java)
                .putExtra(EXTRA_ACCOUNT_ID, accountId)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SignalForegroundService::class.java)
                .setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
