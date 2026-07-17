package ltechnologies.onionphone.securemessenger.protocol.signal

import java.util.Locale
import org.signal.core.models.ServiceId.ACI
import org.signal.core.models.ServiceId.PNI
import org.signal.network.NetworkResult
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.api.registration.RegistrationApi
import org.whispersystems.signalservice.internal.push.PushServiceSocket
import org.whispersystems.signalservice.internal.push.RegistrationSessionMetadataResponse
import org.whispersystems.signalservice.internal.util.StaticCredentialsProvider

internal sealed class SignalRegistrationStep {
    data object RequestSms : SignalRegistrationStep()
    data object CaptchaRequired : SignalRegistrationStep()
    data object SmsCodeRequired : SignalRegistrationStep()
    data object PinRequired : SignalRegistrationStep()
    data object Complete : SignalRegistrationStep()
}

internal data class SignalRegistrationOutcome(
    val step: SignalRegistrationStep,
    val sessionId: String? = null,
    val message: String? = null,
    val credentials: Map<String, String>? = null,
    val displayName: String? = null,
)

internal class SignalRegistrationFlow(
    private val trustStore: SignalAndroidTrustStore,
) {
    private val configuration by lazy { SignalServiceEnvironment.configuration(trustStore) }

    private fun registrationApi(e164: String, password: String): RegistrationApi {
        val credentials = StaticCredentialsProvider(
            null,
            null,
            e164,
            SignalServiceAddress.DEFAULT_DEVICE_ID,
            password,
        )
        val socket = PushServiceSocket(
            configuration,
            credentials,
            SignalServiceEnvironment.SIGNAL_AGENT,
            false,
        )
        return RegistrationApi(socket)
    }

    fun startSession(
        e164: String,
        password: String,
    ): SignalRegistrationOutcome {
        val api = registrationApi(e164, password)
        return when (val result = api.createRegistrationSession(null, null, null)) {
            is NetworkResult.Success -> mapSession(e164, password, result.result, preKeys = null)
            is NetworkResult.StatusCodeError -> failure(result.exception.message ?: "Session creation failed (${result.code})")
            is NetworkResult.NetworkError -> failure(result.exception.message ?: "Network error")
            is NetworkResult.ApplicationError -> failure(result.throwable.message ?: "Registration error")
        }
    }

    fun submitCaptcha(
        e164: String,
        password: String,
        sessionId: String,
        captchaToken: String,
    ): SignalRegistrationOutcome {
        val api = registrationApi(e164, password)
        val token = captchaToken.removePrefix("signalcaptcha://")
        return when (val result = api.submitCaptchaToken(sessionId, token)) {
            is NetworkResult.Success -> mapSession(e164, password, result.result, preKeys = null)
            is NetworkResult.StatusCodeError -> failure(result.exception.message ?: "Captcha rejected (${result.code})")
            is NetworkResult.NetworkError -> failure(result.exception.message ?: "Network error")
            is NetworkResult.ApplicationError -> failure(result.throwable.message ?: "Captcha error")
        }
    }

    fun requestSms(
        e164: String,
        password: String,
        sessionId: String,
    ): SignalRegistrationOutcome {
        val api = registrationApi(e164, password)
        return when (
            val result = api.requestSmsVerificationCode(
                sessionId,
                Locale.getDefault(),
                false,
                PushServiceSocket.VerificationCodeTransport.SMS,
            )
        ) {
            is NetworkResult.Success -> mapSession(e164, password, result.result, preKeys = null)
            is NetworkResult.StatusCodeError -> failure(result.exception.message ?: "SMS request failed (${result.code})")
            is NetworkResult.NetworkError -> failure(result.exception.message ?: "Network error")
            is NetworkResult.ApplicationError -> failure(result.throwable.message ?: "SMS request error")
        }
    }

    fun verifySmsCode(
        e164: String,
        password: String,
        sessionId: String,
        code: String,
        preKeys: SignalPreKeyMaterial,
        pin: String?,
    ): SignalRegistrationOutcome {
        val api = registrationApi(e164, password)
        return when (val verify = api.verifyAccount(sessionId, code.trim())) {
            is NetworkResult.Success -> {
                if (!verify.result.metadata.verified) {
                    return failure("Code incorrect or expired")
                }
                registerAccount(api, e164, password, sessionId, preKeys, pin)
            }
            is NetworkResult.StatusCodeError -> failure(verify.exception.message ?: "Verification failed (${verify.code})")
            is NetworkResult.NetworkError -> failure(verify.exception.message ?: "Network error")
            is NetworkResult.ApplicationError -> failure(verify.throwable.message ?: "Verification error")
        }
    }

    fun registerWithVerifiedSession(
        e164: String,
        password: String,
        sessionId: String,
        preKeys: SignalPreKeyMaterial,
        pin: String?,
    ): SignalRegistrationOutcome {
        val api = registrationApi(e164, password)
        return registerAccount(api, e164, password, sessionId, preKeys, pin)
    }

    private fun registerAccount(
        api: RegistrationApi,
        e164: String,
        password: String,
        sessionId: String,
        preKeys: SignalPreKeyMaterial,
        pin: String?,
    ): SignalRegistrationOutcome {
        val attributes = preKeys.buildAccountAttributes(password, pin, fetchesMessages = true)
        return when (
            val result = api.registerAccount(
                sessionId,
                null,
                attributes,
                preKeys.aciPreKeys,
                preKeys.pniPreKeys,
                null,
                true,
            )
        ) {
            is NetworkResult.Success -> {
                val aci = ACI.parseOrThrow(result.result.uuid)
                val pni = PNI.parseOrThrow(result.result.pni)
                val secrets = buildMap {
                    put(SignalCredentialKeys.E164, e164)
                    put(SignalCredentialKeys.ACI, aci.toString())
                    put(SignalCredentialKeys.PNI, pni.toString())
                    putAll(preKeys.toSecrets(password, pin))
                }
                SignalRegistrationOutcome(
                    step = SignalRegistrationStep.Complete,
                    sessionId = sessionId,
                    message = "Compte Signal enregistré",
                    credentials = secrets,
                    displayName = e164,
                )
            }
            is NetworkResult.StatusCodeError -> failure(result.exception.message ?: "Registration failed (${result.code})")
            is NetworkResult.NetworkError -> failure(result.exception.message ?: "Network error")
            is NetworkResult.ApplicationError -> failure(result.throwable.message ?: "Registration error")
        }
    }

    private fun mapSession(
        e164: String,
        password: String,
        response: RegistrationSessionMetadataResponse,
        preKeys: SignalPreKeyMaterial?,
    ): SignalRegistrationOutcome {
        val metadata = response.metadata
        val sessionId = metadata.id
        return when {
            metadata.captchaRequired() -> SignalRegistrationOutcome(
                step = SignalRegistrationStep.CaptchaRequired,
                sessionId = sessionId,
                message = "Résolvez le captcha Signal (via Tor), puis collez le token.",
            )
            metadata.verified && preKeys != null -> registerAccount(
                registrationApi(e164, password),
                e164,
                password,
                sessionId,
                preKeys,
                null,
            )
            metadata.verified -> SignalRegistrationOutcome(
                step = SignalRegistrationStep.PinRequired,
                sessionId = sessionId,
                message = "Numéro vérifié. Définissez un PIN (optionnel) ou continuez.",
            )
            metadata.allowedToRequestCode -> SignalRegistrationOutcome(
                step = SignalRegistrationStep.RequestSms,
                sessionId = sessionId,
                message = "Demandez un code SMS (reçu sur votre numéro / service SMS en ligne).",
            )
            else -> SignalRegistrationOutcome(
                step = SignalRegistrationStep.SmsCodeRequired,
                sessionId = sessionId,
                message = "Entrez le code SMS reçu sur votre numéro.",
            )
        }
    }

    private fun failure(reason: String) = SignalRegistrationOutcome(
        step = SignalRegistrationStep.SmsCodeRequired,
        message = reason,
    )
}
