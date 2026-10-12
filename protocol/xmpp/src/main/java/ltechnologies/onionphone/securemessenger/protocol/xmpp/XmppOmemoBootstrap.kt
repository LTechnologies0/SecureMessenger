package ltechnologies.onionphone.securemessenger.protocol.xmpp

import android.content.Context
import java.io.File
import timber.log.Timber

/**
 * Signal OMEMO service registration without pulling libsignal types into Kotlin signatures.
 */
internal object XmppOmemoBootstrap {
    @Volatile
    private var storeAccountId: String? = null

    private val storeLock = Any()

    fun ensureSignalStore(context: Context, accountId: String): Boolean {
        return try {
            val signalServiceClass =
                Class.forName("org.jivesoftware.smackx.omemo.signal.SignalOmemoService")
            signalServiceClass.getMethod("acknowledgeLicense").invoke(null)
            signalServiceClass.getMethod("setup").invoke(null)

            val omemoServiceClass = Class.forName("org.jivesoftware.smackx.omemo.OmemoService")
            val service = omemoServiceClass.getMethod("getInstance").invoke(null)
                ?: error("OmemoService.getInstance() returned null")

            synchronized(storeLock) {
                if (storeAccountId != accountId) {
                    val storeDir = File(context.filesDir, "omemo_$accountId").apply { mkdirs() }
                    val fileStoreClass =
                        Class.forName("org.jivesoftware.smackx.omemo.signal.SignalFileBasedOmemoStore")
                    val fileStore = fileStoreClass.getConstructor(File::class.java).newInstance(storeDir)
                    val cachingClass =
                        Class.forName("org.jivesoftware.smackx.omemo.signal.SignalCachingOmemoStore")
                    val store = cachingClass.getConstructor(fileStoreClass).newInstance(fileStore)
                    val storeInterface = Class.forName("org.jivesoftware.smackx.omemo.OmemoStore")
                    service.javaClass.getMethod("setOmemoStoreBackend", storeInterface).invoke(service, store)
                    storeAccountId = accountId
                }
            }
            true
        } catch (e: Exception) {
            Timber.w(e, "Signal OMEMO service setup failed")
            false
        }
    }
}
