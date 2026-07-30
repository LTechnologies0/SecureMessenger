package ltechnologies.onionphone.securemessenger.protocol.signal

import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.signal.core.models.ServiceId.ACI
import org.signal.core.models.ServiceId.PNI
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.network.NetworkResult
import org.whispersystems.signalservice.api.provisioning.ProvisioningSocket
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.api.registration.RegistrationApi
import org.whispersystems.signalservice.internal.crypto.SecondaryProvisioningCipher
import org.whispersystems.signalservice.internal.push.ProvisionMessage
import org.whispersystems.signalservice.internal.push.PushServiceSocket
import org.whispersystems.signalservice.internal.util.StaticCredentialsProvider
import timber.log.Timber

/**
 * Secondary-device link flow: open provisioning WebSocket → QR URL → wait for primary scan →
 * [RegistrationApi.registerAsSecondaryDevice].
 */
internal class SignalLinkFlow(
    private val trustStore: SignalAndroidTrustStore,
) {
    private val configuration by lazy { SignalServiceEnvironment.configuration(trustStore) }

    /**
     * Starts the provisioning socket. Invokes [onProvisioningUrl] when the QR payload is ready,
     * then [onOutcome] when linking finishes or fails.
     * @return closeable that cancels the socket.
     */
    fun start(
        deviceName: String,
        onProvisioningUrl: (String) -> Unit,
        onOutcome: (SignalRegistrationOutcome) -> Unit,
    ): Closeable {
        val ephemeralIdentity = IdentityKeyPair.generate()
        val finished = AtomicReference(false)

        fun finish(outcome: SignalRegistrationOutcome) {
            if (finished.compareAndSet(false, true)) {
                onOutcome(outcome)
            }
        }

        return ProvisioningSocket.start<ProvisionMessage>(
            mode = ProvisioningSocket.Mode.Link(linkAndSyncCapable = false),
            identityKeyPair = ephemeralIdentity,
            configuration = configuration,
            handler = { id, throwable ->
                Timber.w(throwable, "ProvisioningSocket $id failed")
                finish(
                    SignalRegistrationOutcome(
                        step = SignalRegistrationStep.Failed(
                            throwable.message ?: "Lien appareil interrompu",
                        ),
                        message = throwable.message,
                    ),
                )
            },
        ) { socket ->
            val url = socket.getProvisioningUrl()
            onProvisioningUrl(url)
            when (val decrypted = socket.getProvisioningMessageDecryptResult()) {
                is SecondaryProvisioningCipher.ProvisioningDecryptResult.Success -> {
                    finish(completeLink(decrypted.message, deviceName))
                }
                is SecondaryProvisioningCipher.ProvisioningDecryptResult.Error -> {
                    finish(
                        SignalRegistrationOutcome(
                            step = SignalRegistrationStep.Failed("Message de provisionnement invalide"),
                            message = "Message de provisionnement invalide",
                        ),
                    )
                }
            }
        }
    }

    /** Blocking helper for tests: run link until URL is available (caller closes). */
    fun awaitProvisioningUrl(deviceName: String = "SecureMessenger"): Pair<Closeable, String> {
        val deferred = CompletableDeferred<String>()
        val closeable = start(
            deviceName = deviceName,
            onProvisioningUrl = { deferred.complete(it) },
            onOutcome = { /* ignore until closed */ },
        )
        val url = runBlocking { deferred.await() }
        return closeable to url
    }

    private fun completeLink(message: ProvisionMessage, deviceName: String): SignalRegistrationOutcome {
        val provisioningCode = message.provisioningCode?.takeIf { it.isNotBlank() }
            ?: return failure("Code de provisionnement manquant")
        val e164 = message.number?.takeIf { it.isNotBlank() }
            ?: return failure("Numéro manquant dans le message de lien")
        val aciIdentity = identityFromProvision(
            message.aciIdentityKeyPublic?.toByteArray(),
            message.aciIdentityKeyPrivate?.toByteArray(),
        ) ?: return failure("Clés d'identité ACI manquantes")
        val pniIdentity = identityFromProvision(
            message.pniIdentityKeyPublic?.toByteArray(),
            message.pniIdentityKeyPrivate?.toByteArray(),
        ) ?: return failure("Clés d'identité PNI manquantes")

        val password = generateSignalPassword()
        val preKeys = SignalPreKeyMaterial.fromIdentities(aciIdentity, pniIdentity)
        val api = registrationApi(e164, password)

        return when (
            val result = api.registerAsSecondaryDevice(
                verificationCode = provisioningCode,
                attributes = preKeys.buildDeviceAttributes(deviceName),
                aciPreKeys = preKeys.aciPreKeys,
                pniPreKeys = preKeys.pniPreKeys,
                fcmToken = null,
            )
        ) {
            is NetworkResult.Success -> {
                val deviceId = result.result.deviceId?.takeIf { it.isNotBlank() }
                    ?: return failure("deviceId manquant dans la réponse")
                val aci = result.result.uuid?.let { ACI.from(it) }
                    ?: message.aci?.let { runCatching { ACI.parseOrThrow(it) }.getOrNull() }
                    ?: return failure("ACI manquant")
                val pni = result.result.pni?.let { PNI.from(it) }
                    ?: message.pni?.let { runCatching { PNI.parseOrThrow(it) }.getOrNull() }
                    ?: return failure("PNI manquant")
                val secrets = buildMap {
                    put(SignalCredentialKeys.E164, e164)
                    put(SignalCredentialKeys.ACI, aci.toString())
                    put(SignalCredentialKeys.PNI, pni.toString())
                    putAll(preKeys.toSecrets(password, pin = null, deviceId = deviceId))
                }
                SignalRegistrationOutcome(
                    step = SignalRegistrationStep.Complete,
                    message = "Appareil lié ($deviceName)",
                    credentials = secrets,
                    displayName = e164,
                )
            }
            is NetworkResult.StatusCodeError ->
                failure(result.exception.message ?: "Lien refusé (${result.code})")
            is NetworkResult.NetworkError ->
                failure(result.exception.message ?: "Erreur réseau pendant le lien")
            is NetworkResult.ApplicationError ->
                failure(result.throwable.message ?: "Erreur de lien")
        }
    }

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

    private fun identityFromProvision(publicKey: ByteArray?, privateKey: ByteArray?): IdentityKeyPair? {
        if (publicKey == null || privateKey == null) return null
        return runCatching {
            IdentityKeyPair(IdentityKey(publicKey), ECPrivateKey(privateKey))
        }.getOrNull()
    }

    private fun failure(reason: String) = SignalRegistrationOutcome(
        step = SignalRegistrationStep.Failed(reason),
        message = reason,
    )
}
