package ltechnologies.onionphone.securemessenger.protocol.signal

import android.util.Base64
import org.signal.core.util.Base64 as SignalBase64
import org.whispersystems.signalservice.internal.configuration.SignalCdnUrl
import org.whispersystems.signalservice.internal.configuration.SignalCdsiUrl
import org.whispersystems.signalservice.internal.configuration.SignalServiceConfiguration
import org.whispersystems.signalservice.internal.configuration.SignalServiceUrl
import org.whispersystems.signalservice.internal.configuration.SignalStorageUrl
import org.whispersystems.signalservice.internal.configuration.SignalSvr2Url
import java.util.Optional

/** Production Signal service endpoints (mirrors Signal-Android uncensored config). */
object SignalServiceEnvironment {
    private const val SIGNAL_URL = "https://chat.signal.org"
    private const val STORAGE_URL = "https://storage.signal.org"
    private const val CDN_URL = "https://cdn.signal.org"
    private const val CDN2_URL = "https://cdn2.signal.org"
    private const val CDN3_URL = "https://cdn3.signal.org"
    private const val CDSI_URL = "https://cdsi.signal.org"
    private const val SVR2_URL = "https://svr2.signal.org"

    private const val ZK_GROUP_B64 =
        "AMhf5ywVwITZMsff/eCyudZx9JDmkkkbV6PInzG4p8x3VqVJSFiMvnvlEKWuRob/1eaIetR31IYeAbm0NdOuHH8Qi+Rexi1wLlpzIo1gstHWBfZzy1+qHRV5A4TqPp15YzBPm0WSggW6PbSn+F4lf57VCnHF7p8SvzAA2ZZJPYJURt8X7bbg+H3i+PEjH9DXItNEqs2sNcug37xZQDLm7X36nOoGPs54XsEGzPdEV+itQNGUFEjY6X9Uv+Acuks7NpyGvCoKxGwgKgE5XyJ+nNKlyHHOLb6N1NuHyBrZrgtY/JYJHRooo5CEqYKBqdFnmbTVGEkCvJKxLnjwKWf+fEPoWeQFj5ObDjcKMZf2Jm2Ae69x+ikU5gBXsRmoF94GXTLfN0/vLt98KDPnxwAQL9j5V1jGOY8jQl6MLxEs56cwXN0dqCnImzVH3TZT1cJ8SW1BRX6qIVxEzjsSGx3yxF3suAilPMqGRp4ffyopjMD1JXiKR2RwLKzizUe5e8XyGOy9fplzhw3jVzTRyUZTRSZKkMLWcQ/gv0E4aONNqs4P+NameAZYOD12qRkxosQQP5uux6B2nRyZ7sAV54DgFyLiRcq1FvwKw2EPQdk4HDoePrO/RNUbyNddnM/mMgj4FW65xCoT1LmjrIjsv/Ggdlx46ueczhMgtBunx1/w8k8V+l8LVZ8gAT6wkU5J+DPQalQguMg12Jzug3q4TbdHiGCmD9EunCwOmsLuLJkz6EcSYXtrlDEnAM+hicw7iergYLLlMXpfTdGxJCWJmP4zqUFeTTmsmhsjGBt7NiEB/9pFFEB3pSbf4iiUukw63Eo8Aqnf4iwob6X1QviCWuc8t0LUlT9vALgh/f2DPVOOmR0RW6bgRvc7DSF20V/omg+YBw=="
    private const val GENERIC_SERVER_B64 =
        "AByD873dTilmOSG0TjKrvpeaKEsUmIO8Vx9BeMmftwUs9v7ikPwM8P3OHyT0+X3EUMZrSe9VUp26Wai51Q9I8mdk0hX/yo7CeFGJyzoOqn8e/i4Ygbn5HoAyXJx5eXfIbqpc0bIxzju4H/HOQeOpt6h742qii5u/cbwOhFZCsMIbElZTaeU+BWMBQiZHIGHT5IE0qCordQKZ5iPZom0HeFa8Yq0ShuEyAl0WINBiY6xE3H/9WnvzXBbMuuk//eRxXgzO8ieCeK8FwQNxbfXqZm6Ro1cMhCOF3u7xoX83QhpN"
    private const val BACKUP_SERVER_B64 =
        "AJwNSU55fsFCbgaxGRD11wO1juAs8Yr5GF8FPlGzzvdJJIKH5/4CC7ZJSOe3yL2vturVaRU2Cx0n751Vt8wkj1bozK3CBV1UokxV09GWf+hdVImLGjXGYLLhnI1J2TWEe7iWHyb553EEnRb5oxr9n3lUbNAJuRmFM7hrr0Al0F0wrDD4S8lo2mGaXe0MJCOM166F8oYRQqpFeEHfiLnxA1O8ZLh7vMdv4g9jI5phpRBTsJ5IjiJrWeP0zdIGHEssUeprDZ9OUJ14m0v61eYJMKsf59Bn+mAT2a7YfB+Don9O"

    const val SIGNAL_AGENT = "OWA"
    const val CAPTCHA_URL = "https://signalcaptchas.org/registration/generate.html"
    const val MAX_GROUP_SIZE = 1001

    fun configuration(trustStore: SignalAndroidTrustStore): SignalServiceConfiguration {
        return SignalServiceConfiguration(
            signalServiceUrls = arrayOf(SignalServiceUrl(SIGNAL_URL, trustStore)),
            signalCdnUrlMap = mapOf(
                0 to arrayOf(SignalCdnUrl(CDN_URL, trustStore)),
                2 to arrayOf(SignalCdnUrl(CDN2_URL, trustStore)),
                3 to arrayOf(SignalCdnUrl(CDN3_URL, trustStore)),
            ),
            signalStorageUrls = arrayOf(SignalStorageUrl(STORAGE_URL, trustStore)),
            signalCdsiUrls = arrayOf(SignalCdsiUrl(CDSI_URL, trustStore)),
            signalSvr2Urls = arrayOf(SignalSvr2Url(SVR2_URL, trustStore)),
            networkInterceptors = emptyList(),
            dns = Optional.empty(),
            signalProxy = Optional.empty(),
            systemHttpProxy = Optional.empty(),
            zkGroupServerPublicParams = decodeParams(ZK_GROUP_B64),
            genericServerPublicParams = decodeParams(GENERIC_SERVER_B64),
            backupServerPublicParams = decodeParams(BACKUP_SERVER_B64),
            censored = false,
        )
    }

    private fun decodeParams(encoded: String): ByteArray =
        SignalBase64.decode(encoded)
}

internal object SignalCredentialKeys {
    const val E164 = "e164"
    const val ACI = "aci"
    const val PNI = "pni"
    const val PASSWORD = "password"
    const val DEVICE_ID = "deviceId"
    const val ACI_IDENTITY = "aciIdentity"
    const val PNI_IDENTITY = "pniIdentity"
    const val ACI_REGISTRATION_ID = "aciRegistrationId"
    const val PNI_REGISTRATION_ID = "pniRegistrationId"
    const val ACI_SIGNED_PREKEY = "aciSignedPreKey"
    const val PNI_SIGNED_PREKEY = "pniSignedPreKey"
    const val ACI_KYBER_PREKEY = "aciKyberPreKey"
    const val PNI_KYBER_PREKEY = "pniKyberPreKey"
    const val SESSION_READY = "sessionReady"
    const val REGISTRATION_SESSION_ID = "registrationSessionId"
    const val REGISTRATION_PIN = "registrationPin"
}

internal fun generateSignalPassword(): String {
    val bytes = ByteArray(18)
    java.security.SecureRandom().nextBytes(bytes)
    return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
}
