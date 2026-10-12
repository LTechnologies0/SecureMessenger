package ltechnologies.onionphone.securemessenger

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import ltechnologies.onionphone.securemessenger.core.security.AppLockManager
import ltechnologies.onionphone.securemessenger.core.security.LogRedactor
import ltechnologies.onionphone.securemessenger.protocol.signal.SignalLogging
import ltechnologies.onionphone.securemessenger.service.MessengerForegroundService
import timber.log.Timber

@HiltAndroidApp
class SecureMessengerApplication : Application() {
    @Inject lateinit var appLockManager: AppLockManager

    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("sqlcipher")
        createNotificationChannel()
        SignalLogging.install()
        if (BuildConfig.DEBUG) {
            Timber.plant(
                object : Timber.DebugTree() {
                    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                        super.log(priority, tag, LogRedactor.redact(message), t)
                    }
                },
            )
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    appLockManager.scheduleLockAfterInactivity()
                }

                override fun onStart(owner: LifecycleOwner) {
                    appLockManager.cancelScheduledLock()
                }
            },
        )
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
