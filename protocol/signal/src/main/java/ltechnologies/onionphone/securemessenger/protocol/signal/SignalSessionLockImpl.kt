package ltechnologies.onionphone.securemessenger.protocol.signal

import java.util.concurrent.locks.ReentrantLock
import org.whispersystems.signalservice.api.SignalSessionLock

internal object SignalSessionLockImpl : SignalSessionLock {
    private val lock = ReentrantLock()

    override fun acquire(): SignalSessionLock.Lock = LockImpl()

    private class LockImpl : SignalSessionLock.Lock {
        init {
            lock.lock()
        }

        override fun close() {
            lock.unlock()
        }
    }
}
