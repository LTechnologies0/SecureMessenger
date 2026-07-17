package ltechnologies.onionphone.securemessenger.protocol.signal

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.Optional
import ltechnologies.onionphone.securemessenger.core.model.AccountCredentials
import ltechnologies.onionphone.securemessenger.core.model.Attachment
import ltechnologies.onionphone.securemessenger.core.model.AttachmentState
import ltechnologies.onionphone.securemessenger.core.model.AuthStep
import ltechnologies.onionphone.securemessenger.core.model.AuthStepKind
import ltechnologies.onionphone.securemessenger.core.model.ConnectionResult
import ltechnologies.onionphone.securemessenger.core.model.ConnectionState
import ltechnologies.onionphone.securemessenger.core.model.Conversation
import ltechnologies.onionphone.securemessenger.core.model.FeatureFlags
import ltechnologies.onionphone.securemessenger.core.model.Message
import ltechnologies.onionphone.securemessenger.core.model.ProtocolCapabilities
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.model.SanitizedText
import ltechnologies.onionphone.securemessenger.core.model.SendResult
import ltechnologies.onionphone.securemessenger.core.network.NetworkGuard
import ltechnologies.onionphone.securemessenger.core.security.EncryptedCredentialStore
import ltechnologies.onionphone.securemessenger.data.MessengerRepository
import ltechnologies.onionphone.securemessenger.protocol.api.MessengerProtocol
import ltechnologies.onionphone.securemessenger.protocol.api.ProtocolNotEnabledException
import org.signal.core.models.ServiceId.ACI
import org.signal.core.models.ServiceId.PNI
import org.whispersystems.signalservice.api.crypto.ContentHint
import org.whispersystems.signalservice.api.crypto.SealedSenderAccess
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.api.SignalServiceMessageSender
import timber.log.Timber

@Singleton
class SignalProtocol @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkGuard: NetworkGuard,
    private val repository: MessengerRepository,
    private val credentialStore: EncryptedCredentialStore,
    private val trustStore: SignalAndroidTrustStore,
) : MessengerProtocol {

    override val id: ProtocolId = ProtocolId.SIGNAL

    override val capabilities = ProtocolCapabilities(
        directMessages = true,
        groupChats = true,
        mediaSend = true,
        mediaReceive = true,
        typingIndicators = false,
        readReceipts = true,
        endToEndEncryption = true,
        requiresPhoneAuth = true,
    )

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signalDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _pendingAuthStep = MutableStateFlow<AuthStep?>(null)
    fun observePendingAuthStep(): StateFlow<AuthStep?> = _pendingAuthStep.asStateFlow()

    private var accountId: String? = null
    private var proxyConfig: ProxyConfig? = null
    private var registrationFlow: SignalRegistrationFlow? = null
    private var pendingE164: String? = null
    private var pendingPassword: String? = null
    private var pendingSessionId: String? = null
    private var pendingPreKeys: SignalPreKeyMaterial? = null
    private var session: SignalSessionContext? = null
    private var syncEngine: SignalSyncEngine? = null
    private var groupHelper: SignalGroupHelper? = null

    val isEnabled: Boolean get() = ProtocolId.SIGNAL in FeatureFlags.enabled

    override suspend fun connect(account: AccountCredentials, proxy: ProxyConfig): ConnectionResult {
        if (!isEnabled) {
            return ConnectionResult.Failure(
                ProtocolNotEnabledException(id).message ?: "Signal not enabled",
            )
        }
        return withContext(signalDispatcher) {
            try {
                networkGuard.assertNetworkAllowed()
                _connectionState.value = ConnectionState.CONNECTING
                accountId = account.accountId
                proxyConfig = proxy
                registrationFlow = SignalRegistrationFlow(trustStore)

                val secrets = account.secrets
                if (secrets[SignalCredentialKeys.SESSION_READY] == "true") {
                    return@withContext restoreSession(account, secrets, proxy)
                }

                val e164 = secrets[SignalCredentialKeys.E164]
                    ?: secrets["phone"]
                    ?: return@withContext ConnectionResult.Failure("Numéro manquant")
                val password = secrets[SignalCredentialKeys.PASSWORD] ?: generateSignalPassword()
                pendingE164 = e164
                pendingPassword = password
                pendingPreKeys = SignalPreKeyMaterial.generate()

                val outcome = SignalTor.withSocks(proxy, signalDispatcher) {
                    registrationFlow!!.startSession(e164, password)
                }
                applyRegistrationOutcome(outcome)
                ConnectionResult.Success
            } catch (e: Exception) {
                Timber.e(e, "Signal connect failed")
                _connectionState.value = ConnectionState.ERROR
                ConnectionResult.Failure(e.message ?: "Signal connect failed")
            }
        }
    }

    private suspend fun restoreSession(
        account: AccountCredentials,
        secrets: Map<String, String>,
        proxy: ProxyConfig,
    ): ConnectionResult {
        if (secrets[SignalCredentialKeys.E164] == null) return ConnectionResult.Failure("E164 manquant")
        if (secrets[SignalCredentialKeys.ACI] == null) return ConnectionResult.Failure("ACI manquant")
        if (secrets[SignalCredentialKeys.PNI] == null) return ConnectionResult.Failure("PNI manquant")
        if (secrets[SignalCredentialKeys.PASSWORD] == null) return ConnectionResult.Failure("Mot de passe manquant")

        return try {
            SignalTor.withSocks(proxy, signalDispatcher) {
                session = SignalRuntimeFactory.open(trustStore, credentialStore, account.accountId, secrets)
                SignalRuntimeFactory.applyTorProxy(session!!.network, proxy)
            }
            startSync(account.accountId, proxy)
            _pendingAuthStep.value = null
            _connectionState.value = ConnectionState.CONNECTED
            ConnectionResult.Success
        } catch (e: Exception) {
            Timber.e(e, "Signal session restore failed")
            _connectionState.value = ConnectionState.ERROR
            ConnectionResult.Failure(e.message ?: "Session restore failed")
        }
    }

    override suspend fun pendingAuthStep(): AuthStep? = _pendingAuthStep.value

    override suspend fun continueAuthentication(fields: Map<String, String>): ConnectionResult =
        withContext(signalDispatcher) {
            val proxy = proxyConfig ?: return@withContext ConnectionResult.Failure("Proxy non configuré")
            val e164 = pendingE164 ?: return@withContext ConnectionResult.Failure("Session expirée")
            val password = pendingPassword ?: return@withContext ConnectionResult.Failure("Session expirée")
            val flow = registrationFlow ?: return@withContext ConnectionResult.Failure("Flux d'inscription indisponible")
            val sessionId = pendingSessionId ?: return@withContext ConnectionResult.Failure("Session ID manquant")
            val preKeys = pendingPreKeys ?: return@withContext ConnectionResult.Failure("Clés non générées")

            try {
                val outcome = when (_pendingAuthStep.value?.kind) {
                    AuthStepKind.SIGNAL_CAPTCHA -> {
                        val token = fields["captcha"]?.trim().orEmpty()
                        if (token.isBlank()) return@withContext ConnectionResult.Failure("Token captcha requis")
                        SignalTor.withSocks(proxy, signalDispatcher) {
                            flow.submitCaptcha(e164, password, sessionId, token)
                        }
                    }
                    AuthStepKind.SIGNAL_SMS_CODE -> {
                        val code = fields["code"]?.trim().orEmpty()
                        if (code.isBlank()) return@withContext ConnectionResult.Failure("Code SMS requis")
                        SignalTor.withSocks(proxy, signalDispatcher) {
                            flow.verifySmsCode(e164, password, sessionId, code, preKeys, fields["pin"])
                        }
                    }
                    AuthStepKind.SIGNAL_PIN -> {
                        val pin = fields["pin"]?.trim()
                        SignalTor.withSocks(proxy, signalDispatcher) {
                            flow.registerWithVerifiedSession(e164, password, sessionId, preKeys, pin)
                        }
                    }
                    else -> return@withContext ConnectionResult.Failure("Étape d'authentification inconnue")
                }
                applyRegistrationOutcome(outcome)
                if (outcome.step == SignalRegistrationStep.Complete) {
                    ConnectionResult.Success
                } else {
                    ConnectionResult.Success
                }
            } catch (e: Exception) {
                ConnectionResult.Failure(e.message ?: "Authentification échouée")
            }
        }

    fun resendSmsCode(): ConnectionResult {
        val proxy = proxyConfig ?: return ConnectionResult.Failure("Proxy non configuré")
        val e164 = pendingE164 ?: return ConnectionResult.Failure("Session expirée")
        val password = pendingPassword ?: return ConnectionResult.Failure("Session expirée")
        val sessionId = pendingSessionId ?: return ConnectionResult.Failure("Session ID manquant")
        val flow = registrationFlow ?: return ConnectionResult.Failure("Flux indisponible")
        return try {
            val outcome = kotlinx.coroutines.runBlocking(signalDispatcher) {
                SignalTor.withSocks(proxy, signalDispatcher) {
                    flow.requestSms(e164, password, sessionId)
                }
            }
            kotlinx.coroutines.runBlocking { applyRegistrationOutcome(outcome) }
            ConnectionResult.Success
        } catch (e: Exception) {
            ConnectionResult.Failure(e.message ?: "Renvoi SMS échoué")
        }
    }

    fun requestSmsAfterCaptcha(): ConnectionResult {
        val proxy = proxyConfig ?: return ConnectionResult.Failure("Proxy non configuré")
        val e164 = pendingE164 ?: return ConnectionResult.Failure("Session expirée")
        val password = pendingPassword ?: return ConnectionResult.Failure("Session expirée")
        val sessionId = pendingSessionId ?: return ConnectionResult.Failure("Session ID manquant")
        val flow = registrationFlow ?: return ConnectionResult.Failure("Flux indisponible")
        return try {
            val outcome = kotlinx.coroutines.runBlocking(signalDispatcher) {
                SignalTor.withSocks(proxy, signalDispatcher) {
                    flow.requestSms(e164, password, sessionId)
                }
            }
            kotlinx.coroutines.runBlocking { applyRegistrationOutcome(outcome) }
            ConnectionResult.Success
        } catch (e: Exception) {
            ConnectionResult.Failure(e.message ?: "Demande SMS échouée")
        }
    }

    private suspend fun applyRegistrationOutcome(outcome: SignalRegistrationOutcome) {
        pendingSessionId = outcome.sessionId
        when (outcome.step) {
            SignalRegistrationStep.CaptchaRequired -> {
                _pendingAuthStep.value = AuthStep(
                    kind = AuthStepKind.SIGNAL_CAPTCHA,
                    prompt = outcome.message ?: "Captcha requis",
                    fields = listOf("captcha"),
                )
                _connectionState.value = ConnectionState.CONNECTING
            }
            SignalRegistrationStep.RequestSms -> {
                val proxy = proxyConfig
                if (proxy != null && outcome.sessionId != null) {
                    val smsOutcome = SignalTor.withSocks(proxy, signalDispatcher) {
                        registrationFlow!!.requestSms(
                            pendingE164!!,
                            pendingPassword!!,
                            outcome.sessionId,
                        )
                    }
                    applyRegistrationOutcome(smsOutcome)
                    return
                }
                _pendingAuthStep.value = AuthStep(
                    kind = AuthStepKind.SIGNAL_SMS_CODE,
                    prompt = outcome.message ?: "Code SMS requis",
                    fields = listOf("code"),
                )
            }
            SignalRegistrationStep.SmsCodeRequired -> {
                _pendingAuthStep.value = AuthStep(
                    kind = AuthStepKind.SIGNAL_SMS_CODE,
                    prompt = outcome.message ?: "Entrez le code SMS (service en ligne accepté)",
                    fields = listOf("code"),
                )
                _connectionState.value = ConnectionState.CONNECTING
            }
            SignalRegistrationStep.PinRequired -> {
                _pendingAuthStep.value = AuthStep(
                    kind = AuthStepKind.SIGNAL_PIN,
                    prompt = outcome.message ?: "PIN optionnel (Registration Lock)",
                    fields = listOf("pin"),
                )
                _connectionState.value = ConnectionState.CONNECTING
            }
            SignalRegistrationStep.Complete -> {
                val creds = outcome.credentials ?: return
                val accId = accountId ?: UUID.randomUUID().toString()
                accountId = accId
                creds.forEach { (k, v) -> credentialStore.put(accId, k, v) }
                credentialStore.putAccountMeta(accId, ProtocolId.SIGNAL.name, outcome.displayName ?: pendingE164!!)
                repository.upsertAccount(
                    ltechnologies.onionphone.securemessenger.core.model.Account(
                        id = accId,
                        protocol = ProtocolId.SIGNAL,
                        displayName = outcome.displayName ?: pendingE164!!,
                        connectionState = ConnectionState.CONNECTED,
                    ),
                )
                proxyConfig?.let { proxy ->
                    restoreSession(
                        AccountCredentials(ProtocolId.SIGNAL, accId, outcome.displayName ?: pendingE164!!, creds),
                        creds,
                        proxy,
                    )
                }
                _pendingAuthStep.value = null
                _connectionState.value = ConnectionState.CONNECTED
                SignalForegroundService.start(context, accId)
            }
        }
    }

    private fun startSync(accId: String, proxy: ProxyConfig) {
        syncEngine?.stop()
        val activeSession = session ?: return
        val helper = SignalGroupHelper(context, accId).also { groupHelper = it }
        syncEngine = SignalSyncEngine(
            context = context,
            accountId = accId,
            session = activeSession,
            repository = repository,
            proxy = proxy,
            groupHelper = helper,
        ).also { it.start(ioScope) }
    }

    override fun observeConversations(): Flow<List<Conversation>> {
        val accId = accountId
        return repository.observeConversations().map { list ->
            if (accId == null) {
                list.filter { it.protocol == ProtocolId.SIGNAL }
            } else {
                list.filter { it.protocol == ProtocolId.SIGNAL && it.accountId == accId }
            }
        }
    }

    override fun observeMessages(conversationId: String): Flow<List<Message>> =
        repository.observeMessages(conversationId)

    override suspend fun startConversation(
        remoteId: String,
        initialMessage: SanitizedText?,
        accountId: String?,
    ): SendResult = withContext(signalDispatcher) {
        val accId = accountId ?: this@SignalProtocol.accountId ?: return@withContext SendResult.Failure("Compte non connecté")
        val proxy = proxyConfig ?: return@withContext SendResult.Failure("Proxy non configuré")
        val activeSession = session ?: return@withContext SendResult.Failure("Session Signal indisponible")
        return@withContext try {
            SignalTor.withSocks(proxy, signalDispatcher) {
                SignalRuntimeFactory.applyTorProxy(activeSession.network, proxy)
                val recipient = resolveSignalAddress(remoteId)
                val convId = signalConversationId(accId, recipient.identifier)
                repository.upsertConversation(
                    Conversation(
                        id = convId,
                        protocol = ProtocolId.SIGNAL,
                        accountId = accId,
                        remoteId = recipient.identifier,
                        title = recipient.number.orElse(recipient.identifier),
                    ),
                )
                if (initialMessage != null) {
                    deliverMessage(activeSession, convId, accId, recipient, initialMessage)
                } else {
                    SendResult.Success(convId)
                }
            }
        } catch (e: Exception) {
            SendResult.Failure(e.message ?: "Impossible de démarrer la conversation")
        }
    }

    override suspend fun sendMessage(
        conversationId: String,
        body: SanitizedText,
        accountId: String?,
    ): SendResult = withContext(signalDispatcher) {
        val accId = accountId ?: this@SignalProtocol.accountId ?: return@withContext SendResult.Failure("Compte non connecté")
        val proxy = proxyConfig ?: return@withContext SendResult.Failure("Proxy non configuré")
        val activeSession = session ?: return@withContext SendResult.Failure("Session Signal indisponible")
        val remoteId = conversationId.removePrefix("${accId}_")
        if (remoteId == conversationId) {
            return@withContext SendResult.Failure("Conversation Signal invalide")
        }
        return@withContext try {
            networkGuard.assertNetworkAllowed()
            SignalTor.withSocks(proxy, signalDispatcher) {
                SignalRuntimeFactory.applyTorProxy(activeSession.network, proxy)
                val masterKey = SignalGroupHelper.parseMasterKey(remoteId)
                if (masterKey != null) {
                    deliverGroupMessage(
                        activeSession = activeSession,
                        conversationId = conversationId,
                        accId = accId,
                        masterKeyBytes = masterKey,
                        body = body,
                    )
                } else {
                    deliverMessage(activeSession, conversationId, accId, resolveSignalAddress(remoteId), body)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Signal send failed")
            SendResult.Failure(e.message ?: "Envoi Signal échoué")
        }
    }

    override suspend fun sendMedia(
        conversationId: String,
        attachment: Attachment,
        caption: SanitizedText?,
        accountId: String?,
    ): SendResult = withContext(signalDispatcher) {
        val accId = accountId ?: this@SignalProtocol.accountId
            ?: return@withContext SendResult.Failure("Compte non connecté")
        val proxy = proxyConfig ?: return@withContext SendResult.Failure("Proxy non configuré")
        val activeSession = session ?: return@withContext SendResult.Failure("Session Signal indisponible")
        val remoteId = conversationId.removePrefix("${accId}_")
        if (remoteId == conversationId) {
            return@withContext SendResult.Failure("Conversation Signal invalide")
        }
        val localPath = attachment.localPath
            ?: return@withContext SendResult.Failure("Fichier local manquant")
        val file = File(localPath)
        if (!file.exists() || file.length() <= 0L) {
            return@withContext SendResult.Failure("Fichier introuvable")
        }
        return@withContext try {
            networkGuard.assertNetworkAllowed()
            SignalTor.withSocks(proxy, signalDispatcher) {
                SignalRuntimeFactory.applyTorProxy(activeSession.network, proxy)
                val form = activeSession.pushServiceSocket.attachmentV4UploadForm
                val uploadSpec = activeSession.pushServiceSocket.getResumableUploadSpec(form)
                FileInputStream(file).use { input ->
                    val stream = SignalServiceAttachment.newStreamBuilder()
                        .withStream(input)
                        .withContentType(attachment.mimeType)
                        .withFileName(attachment.fileName ?: file.name)
                        .withLength(file.length())
                        .withCaption(caption?.value)
                        .withResumableUploadSpec(uploadSpec)
                        .build()
                    val pointer = activeSession.messageSender.uploadAttachment(stream)
                    val localAtt = attachment.copy(
                        remoteRef = pointer.remoteId.toString(),
                        sizeBytes = file.length(),
                        state = AttachmentState.READY,
                    )
                    val captionBody = caption ?: SanitizedText(attachment.fileName ?: "📎")
                    val masterKey = SignalGroupHelper.parseMasterKey(remoteId)
                    if (masterKey != null) {
                        deliverGroupMessage(
                            activeSession = activeSession,
                            conversationId = conversationId,
                            accId = accId,
                            masterKeyBytes = masterKey,
                            body = captionBody,
                            attachments = listOf(pointer),
                            localAttachment = localAtt,
                        )
                    } else {
                        deliverMessage(
                            activeSession = activeSession,
                            conversationId = conversationId,
                            accId = accId,
                            recipient = resolveSignalAddress(remoteId),
                            body = captionBody,
                            attachments = listOf(pointer),
                            localAttachment = localAtt,
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Signal media send failed")
            SendResult.Failure(e.message ?: "Envoi média Signal échoué")
        }
    }

    private suspend fun deliverGroupMessage(
        activeSession: SignalSessionContext,
        conversationId: String,
        accId: String,
        masterKeyBytes: ByteArray,
        body: SanitizedText,
        attachments: List<org.whispersystems.signalservice.api.messages.SignalServiceAttachment> = emptyList(),
        localAttachment: Attachment? = null,
    ): SendResult {
        val helper = groupHelper ?: SignalGroupHelper(context, accId).also { groupHelper = it }
        val plan = helper.resolveSendTargets(activeSession, masterKeyBytes)
            ?: return SendResult.Failure("Membres du groupe Signal inconnus — attendez un message entrant")
        val timestamp = System.currentTimeMillis()
        val builder = SignalServiceDataMessage.newBuilder()
            .withTimestamp(timestamp)
            .withBody(body.value)
            .asGroupMessage(plan.groupContext)
        if (attachments.isNotEmpty()) {
            builder.withAttachments(attachments)
        }
        val dataMessage = builder.build()
        val results = if (plan.canUseSenderKeys) {
            activeSession.messageSender.sendGroupDataMessage(
                plan.distributionId,
                plan.recipients,
                plan.unidentifiedAccess,
                plan.groupSendEndorsements,
                false,
                ContentHint.DEFAULT,
                dataMessage,
                SignalServiceMessageSender.SenderKeyGroupEvents.EMPTY,
                true,
                false,
                null,
                null,
            )
        } else {
            Timber.w("Signal GV2 sender-key unavailable; falling back to fan-out")
            val sealed = List(plan.recipients.size) { SealedSenderAccess.NONE }
            activeSession.messageSender.sendDataMessage(
                plan.recipients,
                sealed,
                false,
                ContentHint.DEFAULT,
                dataMessage,
                SignalServiceMessageSender.LegacyGroupEvents.EMPTY,
                null,
                null,
                true,
            )
        }
        val anySuccess = results.any { it.isSuccess }
        if (!anySuccess) {
            return SendResult.Failure("Envoi Signal groupe échoué")
        }
        val remoteId = "gv2:" + android.util.Base64.encodeToString(masterKeyBytes, android.util.Base64.NO_WRAP)
        val title = helper.cachedTitle(masterKeyBytes) ?: "Groupe Signal"
        val msg = Message(
            id = "${conversationId}_$timestamp",
            conversationId = conversationId,
            protocol = ProtocolId.SIGNAL,
            body = body.value,
            timestamp = timestamp,
            direction = ltechnologies.onionphone.securemessenger.core.model.MessageDirection.OUTGOING,
            deliveryState = ltechnologies.onionphone.securemessenger.core.model.DeliveryState.SENT,
            attachments = listOfNotNull(localAttachment),
        )
        repository.upsertMessage(msg)
        repository.upsertConversation(
            Conversation(
                id = conversationId,
                protocol = ProtocolId.SIGNAL,
                accountId = accId,
                remoteId = remoteId,
                title = title,
                lastMessagePreview = body.value,
                lastMessageAt = timestamp,
            ),
        )
        return SendResult.Success(msg.id)
    }

    private suspend fun deliverMessage(
        activeSession: SignalSessionContext,
        conversationId: String,
        accId: String,
        recipient: SignalServiceAddress,
        body: SanitizedText,
        attachments: List<org.whispersystems.signalservice.api.messages.SignalServiceAttachment> = emptyList(),
        localAttachment: Attachment? = null,
    ): SendResult {
        val timestamp = System.currentTimeMillis()
        val builder = SignalServiceDataMessage.newBuilder()
            .withTimestamp(timestamp)
            .withBody(body.value)
        if (attachments.isNotEmpty()) {
            builder.withAttachments(attachments)
        }
        val dataMessage = builder.build()
        val result = activeSession.messageSender.sendDataMessage(
            recipient,
            SealedSenderAccess.NONE,
            ContentHint.DEFAULT,
            dataMessage,
            SignalServiceMessageSender.IndividualSendEvents.EMPTY,
            true,
            false,
        )
        if (!result.isSuccess) {
            return SendResult.Failure("Envoi Signal échoué")
        }
        val msg = Message(
            id = "${conversationId}_$timestamp",
            conversationId = conversationId,
            protocol = ProtocolId.SIGNAL,
            body = body.value,
            timestamp = timestamp,
            direction = ltechnologies.onionphone.securemessenger.core.model.MessageDirection.OUTGOING,
            deliveryState = ltechnologies.onionphone.securemessenger.core.model.DeliveryState.SENT,
            attachments = listOfNotNull(localAttachment),
        )
        repository.upsertMessage(msg)
        repository.upsertConversation(
            Conversation(
                id = conversationId,
                protocol = ProtocolId.SIGNAL,
                accountId = accId,
                remoteId = recipient.identifier,
                title = recipient.number.orElse(recipient.identifier),
                lastMessagePreview = body.value,
                lastMessageAt = timestamp,
            ),
        )
        return SendResult.Success(msg.id)
    }

    override suspend fun disconnect(accountId: String?) {
        withContext(signalDispatcher) {
            if (accountId == null || accountId == this@SignalProtocol.accountId) {
                syncEngine?.stop()
                syncEngine = null
                session?.shutdown()
                session = null
                groupHelper = null
                registrationFlow = null
                pendingE164 = null
                pendingPassword = null
                pendingSessionId = null
                pendingPreKeys = null
                _pendingAuthStep.value = null
                this@SignalProtocol.accountId = null
                proxyConfig = null
                _connectionState.value = ConnectionState.DISCONNECTED
                SignalForegroundService.stop(context)
            }
        }
    }

    fun cancelRegistration() {
        ioScope.launch {
            disconnect(accountId)
        }
    }
}
