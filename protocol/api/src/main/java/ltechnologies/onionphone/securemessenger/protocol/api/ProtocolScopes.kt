package ltechnologies.onionphone.securemessenger.protocol.api

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** IO scope with a handler so uncaught protocol errors do not kill the process. */
fun protocolIoScope(name: String): CoroutineScope =
    CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Log.e("SecureMessenger", "Uncaught in $name", e)
        },
    )
