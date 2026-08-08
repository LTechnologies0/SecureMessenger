package ltechnologies.onionphone.securemessenger.protocol.signal

import android.util.Log as AndroidLog
import java.util.concurrent.atomic.AtomicBoolean
import org.signal.core.util.logging.Log

/** Bridges Signal's NOOP [Log] into Android logcat so websocket connect failures are visible. */
object SignalLogging {
    private val installed = AtomicBoolean(false)

    fun install() {
        if (!installed.compareAndSet(false, true)) return
        Log.initialize(object : Log.Logger() {
            override fun v(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) {
                AndroidLog.v(tag, message ?: "", t)
            }

            override fun d(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) {
                AndroidLog.d(tag, message ?: "", t)
            }

            override fun i(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) {
                AndroidLog.i(tag, message ?: "", t)
            }

            override fun w(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) {
                AndroidLog.w(tag, message ?: "", t)
            }

            override fun e(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) {
                AndroidLog.e(tag, message ?: "", t)
            }

            override fun flush() = Unit
        })
    }
}
