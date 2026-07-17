package ltechnologies.onionphone.securemessenger.protocol.signal

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import org.whispersystems.signalservice.api.push.TrustStore

@Singleton
class SignalAndroidTrustStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : TrustStore {
    override fun getKeyStoreInputStream(): InputStream =
        context.resources.openRawResource(R.raw.whisper)

    override fun getKeyStorePassword(): String = "whisper"
}
