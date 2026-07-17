package ltechnologies.onionphone.securemessenger

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dagger.hilt.android.HiltAndroidApp
import ltechnologies.onionphone.securemessenger.core.security.LogRedactor
import ltechnologies.onionphone.securemessenger.service.MessengerForegroundService
import timber.log.Timber

@HiltAndroidApp
class SecureMessengerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("sqlcipher")
        createNotificationChannel()
        if (BuildConfig.DEBUG) {
            Timber.plant(
                object : Timber.DebugTree() {
                    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                        super.log(priority, tag, LogRedactor.redact(message), t)
                    }
                },
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                MessengerForegroundService.CHANNEL_ID,
                "Messenger connections",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}
