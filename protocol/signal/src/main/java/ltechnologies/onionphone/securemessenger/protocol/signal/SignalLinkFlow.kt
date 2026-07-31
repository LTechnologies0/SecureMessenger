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
     * [onProgress] for post-scan status, then [onOutcome] when linking finishes or fails.
     */
    fun start(
        deviceName: String,
        onProvisioningUrl: (String) -> Unit,
        onProgress: (String) -> Unit = {},
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
            mode = ProvisioningSocket.Mode.Link(linkAndSyncCapable = true),
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
            Timber.i("Signal link: provisioning URL ready")
            onProvisioningUrl(url)
            when (val decrypted = socket.getProvisioningMessageDecryptResult()) {
                is SecondaryProvisioningCipher.ProvisioningDecryptResult.Success -> {
                    Timber.i("Signal link: provision message decrypted — registering secondary device")
                    onProgress("Provision reçu — finalisation sur cet appareil…")
                    finish(completeLink(decrypted.message, deviceName))
                }
                is SecondaryProvisioningCipher.ProvisioningDecryptResult.Error -> {
                    Timber.w("Signal link: provision decrypt failed")
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
        val attributes = preKeys.buildDeviceAttributes(deviceName, aciIdentity)

        Timber.i("Signal link: PUT /v1/devices/link")
        return when (
            val result = api.registerAsSecondaryDevice(
                verificationCode = provisioningCode,
                attributes = attributes,
                aciPreKeys = preKeys.aciPreKeys,
                pniPreKeys = preKeys.pniPreKeys,
                fcmToken = null,
            )
        ) {
            is NetworkResult.Success -> {
                val deviceId = result.result.deviceId?.takeIf { it.isNotBlank() }
                    ?: return failure("deviceId manquant dans la réponse")
                val aci = resolveAci(message, result.result.uuid)
                    ?: return failure("ACI manquant")
                val pni = resolvePni(message, result.result.pni)
                    ?: return failure("PNI manquant")
                val secrets = buildMap {
                    put(SignalCredentialKeys.E164, e164)
                    put(SignalCredentialKeys.ACI, aci.toString())
                    put(SignalCredentialKeys.PNI, pni.toString())
                    putAll(preKeys.toSecrets(password, pin = null, deviceId = deviceId))
                    message.profileKey?.takeIf { it.size > 0 }?.let { pk ->
                        put(
                            SignalCredentialKeys.PROFILE_KEY,
                            android.util.Base64.encodeToString(pk.toByteArray(), android.util.Base64.NO_WRAP),
                        )
                    }
                    message.accountEntropyPool?.takeIf { it.isNotBlank() }?.let {
                        put(SignalCredentialKeys.ACCOUNT_ENTROPY_POOL, it)
                    }
                    message.mediaRootBackupKey?.takeIf { it.size > 0 }?.let { key ->
                        put(
                            SignalCredentialKeys.MEDIA_ROOT_BACKUP_KEY,
                            android.util.Base64.encodeToString(key.toByteArray(), android.util.Base64.NO_WRAP),
                        )
                    }
                    message.ephemeralBackupKey?.takeIf { it.size > 0 }?.let { key ->
                        put(
                            SignalCredentialKeys.EPHEMERAL_BACKUP_KEY,
                            android.util.Base64.encodeToString(key.toByteArray(), android.util.Base64.NO_WRAP),
                        )
                    }
                }
                Timber.i("Signal link: success deviceId=%s", deviceId)
                SignalRegistrationOutcome(
                    step = SignalRegistrationStep.Complete,
                    message = "Appareil lié ($deviceName)",
                    credentials = secrets,
                    displayName = e164,
                )
            }
            is NetworkResult.StatusCodeError -> {
                Timber.e(result.exception, "Signal link refused HTTP %s", result.code)
                failure(result.exception.message ?: "Lien refusé (${result.code})")
            }
            is NetworkResult.NetworkError -> {
                Timber.e(result.exception, "Signal link network error")
                failure(result.exception.message ?: "Erreur réseau pendant le lien")
            }
            is NetworkResult.ApplicationError -> {
                Timber.e(result.throwable, "Signal link application error")
                failure(result.throwable.message ?: "Erreur de lien")
            }
        }
    }

    private fun resolveAci(message: ProvisionMessage, responseUuid: java.util.UUID?): ACI? {
        responseUuid?.let { return ACI.from(it) }
        message.aciBinary?.takeIf { it.size > 0 }?.let { bytes ->
            runCatching { ACI.parseOrThrow(bytes.toByteArray()) }.getOrNull()?.let { return it }
        }
        return message.aci?.let { runCatching { ACI.parseOrThrow(it) }.getOrNull() }
    }

    private fun resolvePni(message: ProvisionMessage, responseUuid: java.util.UUID?): PNI? {
        responseUuid?.let { return PNI.from(it) }
        message.pniBinary?.takeIf { it.size > 0 }?.let { bytes ->
            runCatching { PNI.parseOrThrow(bytes.toByteArray()) }.getOrNull()?.let { return it }
        }
        return message.pni?.let { runCatching { PNI.parseOrThrow(it) }.getOrNull() }
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
        }.onFailure { Timber.w(it, "Failed to parse provision identity keys") }
            .getOrNull()
    }

    private fun failure(reason: String) = SignalRegistrationOutcome(
        step = SignalRegistrationStep.Failed(reason),
        message = reason,
    )
}
