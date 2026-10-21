package ltechnologies.onionphone.securemessenger.protocol.xmpp

import android.content.Context
import java.io.File
import timber.log.Timber

/**
 * Signal OMEMO service registration without pulling libsignal types into Kotlin signatures.
 *
 * [org.jivesoftware.smackx.omemo.OmemoService] is process-wide and
 * [org.jivesoftware.smackx.omemo.OmemoService.setOmemoStoreBackend] throws if a backend is
 * already installed. [org.jivesoftware.smackx.omemo.FileBasedOmemoStore] already namespaces
 * identity keys, device lists, and sessions by local bare JID under one base directory, so
 * a second XMPP account must not replace the backend.
 */
internal object XmppOmemoBootstrap {
    @Volatile
    private var backendInstalled = false

    private val storeLock = Any()

    /**
     * @param accountId retained for call-site stability. It is not a store key: Smack files
     * keys under the local bare JID inside [context]'s `omemo` directory.
     */
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
                if (!backendInstalled) {
                    val storeDir = File(context.filesDir, "omemo").apply { mkdirs() }
                    val fileStoreClass =
                        Class.forName("org.jivesoftware.smackx.omemo.signal.SignalFileBasedOmemoStore")
                    val fileStore = fileStoreClass.getConstructor(File::class.java).newInstance(storeDir)
                    val cachingClass =
                        Class.forName("org.jivesoftware.smackx.omemo.signal.SignalCachingOmemoStore")
                    val store = cachingClass.getConstructor(fileStoreClass).newInstance(fileStore)
                    val storeInterface = Class.forName("org.jivesoftware.smackx.omemo.OmemoStore")
                    try {
                        service.javaClass.getMethod("setOmemoStoreBackend", storeInterface)
                            .invoke(service, store)
                    } catch (e: java.lang.reflect.InvocationTargetException) {
                        val cause = e.cause
                        if (cause !is IllegalStateException) throw e
                        Timber.w(
                            cause,
                            "OmemoService backend already set; keeping it (accountId=%s)",
                            accountId,
                        )
                    }
                    backendInstalled = true
                }
            }
            true
        } catch (e: Exception) {
            Timber.w(e, "Signal OMEMO service setup failed")
            false
        }
    }
}
