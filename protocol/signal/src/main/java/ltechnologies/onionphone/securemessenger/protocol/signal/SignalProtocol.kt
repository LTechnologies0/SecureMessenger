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
import ltechnologies.onionphone.securemessenger.core.model.AccountProfile
import ltechnologies.onionphone.securemessenger.core.model.Attachment
import ltechnologies.onionphone.securemessenger.core.model.AttachmentState
import ltechnologies.onionphone.securemessenger.core.model.AuthStep
import ltechnologies.onionphone.securemessenger.core.model.AuthStepKind
import ltechnologies.onionphone.securemessenger.core.model.ConnectionResult
import ltechnologies.onionphone.securemessenger.core.model.ConnectionState
import ltechnologies.onionphone.securemessenger.core.model.Contact
import ltechnologies.onionphone.securemessenger.core.model.Conversation
import ltechnologies.onionphone.securemessenger.core.model.FeatureFlags
import ltechnologies.onionphone.securemessenger.core.model.Message
import ltechnologies.onionphone.securemessenger.core.model.MessageKind
import ltechnologies.onionphone.securemessenger.core.model.OutgoingContent
import ltechnologies.onionphone.securemessenger.core.model.ProtocolCapabilities
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.model.SanitizedText
import ltechnologies.onionphone.securemessenger.core.model.SendResult
import ltechnologies.onionphone.securemessenger.core.security.EncryptedCredentialStore
import ltechnologies.onionphone.securemessenger.data.MessengerRepository
import ltechnologies.onionphone.securemessenger.protocol.api.MessengerProtocol
import ltechnologies.onionphone.securemessenger.protocol.api.ProtocolNotEnabledException
import org.signal.core.util.SignalSocksHolder
import org.whispersystems.signalservice.api.crypto.ContentHint
import org.whispersystems.signalservice.api.crypto.SealedSenderAccess
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.api.messages.SignalServiceReceiptMessage
import org.whispersystems.signalservice.api.messages.SignalServiceTypingMessage
import org.whispersystems.signalservice.api.messages.shared.SharedContact
import org.whispersystems.signalservice.api.profiles.AvatarUploadParams
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.api.SignalServiceMessageSender
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.SecureRandom
import kotlinx.coroutines.flow.first
import ltechnologies.onionphone.securemessenger.core.proxy.SocksEndpointResolver
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class SignalProtocol @Inject constructor(
    @ApplicationContext private val context: Context,
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
        typingIndicators = true,
        readReceipts = true,
        endToEndEncryption = true,
        requiresPhoneAuth = true,
        contacts = true,
        profileEdit = true,
        voiceNotes = true,
        stickers = false,
        gifs = false,
        locationShare = false,
        polls = true,
        contactShare = true,
        ephemeralMessages = true,
        messageHistory = false,
        backupExport = true,
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
    private var linkFlow: SignalLinkFlow? = null
    private var linkCloseable: java.io.Closeable? = null
    private var pendingE164: String? = null
    private var pendingPassword: String? = null
    private var pendingSessionId: String? = null
    private var pendingPreKeys: SignalPreKeyMaterial? = null
    private var session: SignalSessionContext? = null
    private var syncEngine: SignalSyncEngine? = null
    private var groupHelper: SignalGroupHelper? = null

    private val _deviceLinkUrl = MutableStateFlow<String?>(null)
    fun observeDeviceLinkUrl(): StateFlow<String?> = _deviceLinkUrl.asStateFlow()

    val isEnabled: Boolean get() = ProtocolId.SIGNAL in FeatureFlags.enabled

    override suspend fun connect(account: AccountCredentials, proxy: ProxyConfig): ConnectionResult {
        if (!isEnabled) {
            return ConnectionResult.Failure(
                ProtocolNotEnabledException(id).message ?: "Signal not enabled",
            )
        }
        return withContext(signalDispatcher) {
            try {
                _connectionState.value = ConnectionState.CONNECTING
                accountId = account.accountId
                proxyConfig = proxy
                applySignalSocks(proxy)
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

                val outcome = registrationFlow!!.startSession(e164, password)
                val applied = applyRegistrationOutcome(outcome)
                when (val failed = applied.step as? SignalRegistrationStep.Failed) {
                    null -> ConnectionResult.Success
                    else -> {
                        _connectionState.value = ConnectionState.ERROR
                        ConnectionResult.Failure(failed.reason)
                    }
                }
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
            applySignalSocks(proxy)
            session = SignalRuntimeFactory.open(trustStore, credentialStore, account.accountId, secrets)
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

    /**
     * Classic secondary-device link: shows a QR (`sgnl://linkdevice?...`) for the primary Signal app to scan.
     */
    suspend fun startDeviceLink(
        deviceName: String = "SecureMessenger",
        proxy: ProxyConfig,
    ): ConnectionResult = withContext(signalDispatcher) {
        if (!isEnabled) {
            return@withContext ConnectionResult.Failure(
                ProtocolNotEnabledException(id).message ?: "Signal not enabled",
            )
        }
        try {
            cancelDeviceLinkLocked()
            _connectionState.value = ConnectionState.CONNECTING
            accountId = UUID.randomUUID().toString()
            proxyConfig = proxy
            applySignalSocks(proxy)
            linkFlow = SignalLinkFlow(trustStore)
            _deviceLinkUrl.value = null
            _pendingAuthStep.value = AuthStep(
                kind = AuthStepKind.SIGNAL_DEVICE_LINK,
                prompt = "Scannez ce QR depuis Signal (appareil principal) → Paramètres → Appareils liés",
                fields = emptyList(),
                url = null,
            )
            linkCloseable = linkFlow!!.start(
                deviceName = deviceName,
                onProvisioningUrl = { url ->
                    _deviceLinkUrl.value = url
                    _pendingAuthStep.value = AuthStep(
                        kind = AuthStepKind.SIGNAL_DEVICE_LINK,
                        prompt = "Scannez ce QR depuis Signal (appareil principal) → Paramètres → Appareils liés",
                        fields = emptyList(),
                        url = url,
                    )
                },
                onProgress = { msg ->
                    _pendingAuthStep.value = AuthStep(
                        kind = AuthStepKind.SIGNAL_DEVICE_LINK,
                        prompt = msg,
                        fields = emptyList(),
                        url = _deviceLinkUrl.value,
                    )
                },
                onOutcome = { outcome ->
                    ioScope.launch(signalDispatcher) {
                        applyRegistrationOutcome(outcome)
                    }
                },
            )
            ConnectionResult.Success
        } catch (e: Exception) {
            Timber.e(e, "Signal device link failed to start")
            _connectionState.value = ConnectionState.ERROR
            ConnectionResult.Failure(e.message ?: "Impossible de démarrer le lien")
        }
    }

    fun cancelDeviceLink() {
        ioScope.launch(signalDispatcher) {
            cancelDeviceLinkLocked()
            if (_connectionState.value != ConnectionState.CONNECTED) {
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    private fun cancelDeviceLinkLocked() {
        runCatching { linkCloseable?.close() }
        linkCloseable = null
        linkFlow = null
        _deviceLinkUrl.value = null
        if (_pendingAuthStep.value?.kind == AuthStepKind.SIGNAL_DEVICE_LINK) {
            _pendingAuthStep.value = null
        }
    }

    override suspend fun pendingAuthStep(): AuthStep? = _pendingAuthStep.value

    override suspend fun continueAuthentication(fields: Map<String, String>): ConnectionResult =
        withContext(signalDispatcher) {
            if (proxyConfig == null) return@withContext ConnectionResult.Failure("Session Signal non initialisée")
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
                        flow.submitCaptcha(e164, password, sessionId, token)
                    }
                    AuthStepKind.SIGNAL_SMS_CODE -> {
                        val code = fields["code"]?.trim().orEmpty()
                        if (code.isBlank()) return@withContext ConnectionResult.Failure("Code SMS requis")
                        flow.verifySmsCode(e164, password, sessionId, code, preKeys, fields["pin"])
                    }
                    AuthStepKind.SIGNAL_PIN -> {
                        val pin = fields["pin"]?.trim()
                        flow.registerWithVerifiedSession(e164, password, sessionId, preKeys, pin)
                    }
                    else -> return@withContext ConnectionResult.Failure("Étape d'authentification inconnue")
                }
                val applied = applyRegistrationOutcome(outcome)
                when (val failed = applied.step as? SignalRegistrationStep.Failed) {
                    null -> ConnectionResult.Success
                    else -> {
                        _connectionState.value = ConnectionState.ERROR
                        ConnectionResult.Failure(failed.reason)
                    }
                }
            } catch (e: Exception) {
                ConnectionResult.Failure(e.message ?: "Authentification échouée")
            }
        }

    fun resendSmsCode(): ConnectionResult {
        if (proxyConfig == null) return ConnectionResult.Failure("Session Signal non initialisée")
        val e164 = pendingE164 ?: return ConnectionResult.Failure("Session expirée")
        val password = pendingPassword ?: return ConnectionResult.Failure("Session expirée")
        val sessionId = pendingSessionId ?: return ConnectionResult.Failure("Session ID manquant")
        val flow = registrationFlow ?: return ConnectionResult.Failure("Flux indisponible")
        return try {
            val outcome = kotlinx.coroutines.runBlocking(signalDispatcher) {
                flow.requestSms(e164, password, sessionId)
            }
            val applied = kotlinx.coroutines.runBlocking { applyRegistrationOutcome(outcome) }
            when (val failed = applied.step as? SignalRegistrationStep.Failed) {
                null -> ConnectionResult.Success
                else -> ConnectionResult.Failure(failed.reason)
            }
        } catch (e: Exception) {
            ConnectionResult.Failure(e.message ?: "Renvoi SMS échoué")
        }
    }

    fun requestSmsAfterCaptcha(): ConnectionResult {
        if (proxyConfig == null) return ConnectionResult.Failure("Session Signal non initialisée")
        val e164 = pendingE164 ?: return ConnectionResult.Failure("Session expirée")
        val password = pendingPassword ?: return ConnectionResult.Failure("Session expirée")
        val sessionId = pendingSessionId ?: return ConnectionResult.Failure("Session ID manquant")
        val flow = registrationFlow ?: return ConnectionResult.Failure("Flux indisponible")
        return try {
            val outcome = kotlinx.coroutines.runBlocking(signalDispatcher) {
                flow.requestSms(e164, password, sessionId)
            }
            val applied = kotlinx.coroutines.runBlocking { applyRegistrationOutcome(outcome) }
            when (val failed = applied.step as? SignalRegistrationStep.Failed) {
                null -> ConnectionResult.Success
                else -> ConnectionResult.Failure(failed.reason)
            }
        } catch (e: Exception) {
            ConnectionResult.Failure(e.message ?: "Demande SMS échouée")
        }
    }

    private suspend fun applyRegistrationOutcome(outcome: SignalRegistrationOutcome): SignalRegistrationOutcome {
        pendingSessionId = outcome.sessionId
        when (outcome.step) {
            SignalRegistrationStep.CaptchaRequired -> {
                _pendingAuthStep.value = AuthStep(
                    kind = AuthStepKind.SIGNAL_CAPTCHA,
                    prompt = outcome.message ?: "Captcha requis",
                    fields = listOf("captcha"),
                )
                _connectionState.value = ConnectionState.CONNECTING
                return outcome
            }
            SignalRegistrationStep.RequestSms -> {
                if (proxyConfig != null && outcome.sessionId != null) {
                    val smsOutcome = registrationFlow!!.requestSms(
                        pendingE164!!,
                        pendingPassword!!,
                        outcome.sessionId,
                    )
                    return applyRegistrationOutcome(smsOutcome)
                }
                _pendingAuthStep.value = AuthStep(
                    kind = AuthStepKind.SIGNAL_SMS_CODE,
                    prompt = outcome.message ?: "Code SMS requis",
                    fields = listOf("code"),
                )
                return outcome
            }
            SignalRegistrationStep.SmsCodeRequired -> {
                _pendingAuthStep.value = AuthStep(
                    kind = AuthStepKind.SIGNAL_SMS_CODE,
                    prompt = outcome.message ?: "Entrez le code SMS (service en ligne accepté)",
                    fields = listOf("code"),
                )
                _connectionState.value = ConnectionState.CONNECTING
                return outcome
            }
            SignalRegistrationStep.PinRequired -> {
                _pendingAuthStep.value = AuthStep(
                    kind = AuthStepKind.SIGNAL_PIN,
                    prompt = outcome.message ?: "PIN optionnel (Registration Lock)",
                    fields = listOf("pin"),
                )
                _connectionState.value = ConnectionState.CONNECTING
                return outcome
            }
            SignalRegistrationStep.Complete -> {
                val creds = outcome.credentials ?: return outcome
                val accId = accountId ?: UUID.randomUUID().toString()
                accountId = accId
                val displayName = outcome.displayName
                    ?: creds[SignalCredentialKeys.E164]
                    ?: pendingE164
                    ?: "Signal"
                runCatching { linkCloseable?.close() }
                linkCloseable = null
                linkFlow = null
                _deviceLinkUrl.value = null
                creds.forEach { (k, v) -> credentialStore.put(accId, k, v) }
                credentialStore.putAccountMeta(accId, ProtocolId.SIGNAL.name, displayName)
                repository.upsertAccount(
                    ltechnologies.onionphone.securemessenger.core.model.Account(
                        id = accId,
                        protocol = ProtocolId.SIGNAL,
                        displayName = displayName,
                        connectionState = ConnectionState.CONNECTED,
                    ),
                )
                proxyConfig?.let { proxy ->
                    restoreSession(
                        AccountCredentials(ProtocolId.SIGNAL, accId, displayName, creds),
                        creds,
                        proxy,
                    )
                }
                _pendingAuthStep.value = null
                _connectionState.value = ConnectionState.CONNECTED
                SignalForegroundService.start(context, accId)
                return outcome
            }
            is SignalRegistrationStep.Failed -> {
                val reason = outcome.step.reason
                Timber.w("Signal auth/link failed: %s", reason)
                // Keep SIGNAL_DEVICE_LINK step so the QR screen can show the error.
                if (_deviceLinkUrl.value != null || linkFlow != null) {
                    _pendingAuthStep.value = AuthStep(
                        kind = AuthStepKind.SIGNAL_DEVICE_LINK,
                        prompt = reason,
                        fields = emptyList(),
                        url = _deviceLinkUrl.value,
                    )
                } else {
                    _pendingAuthStep.value = null
                }
                _connectionState.value = ConnectionState.ERROR
                return outcome
            }
        }
    }

    private fun startSync(accId: String, proxy: ProxyConfig) {
        applySignalSocks(proxy)
        syncEngine?.stop()
        val activeSession = session ?: return
        val helper = SignalGroupHelper(context, accId, credentialStore).also { groupHelper = it }
        syncEngine = SignalSyncEngine(
            context = context,
            accountId = accId,
            session = activeSession,
            repository = repository,
            proxy = proxy,
            groupHelper = helper,
        ).also { it.start(ioScope) }
    }

    /** Routes Signal OkHttp/WebSocket through OnionVPN SOCKS when Tor is enabled. */
    private fun applySignalSocks(proxy: ProxyConfig) {
        if (proxy.torRequired) {
            val host = SocksEndpointResolver.resolveReachableHost(proxy.host, proxy.port)
            SignalSocksHolder.set(Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, proxy.port)))
        } else {
            SignalSocksHolder.clear()
        }
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
        asGroup: Boolean,
    ): SendResult = withContext(signalDispatcher) {
        val accId = accountId ?: this@SignalProtocol.accountId ?: return@withContext SendResult.Failure("Compte non connecté")
        val activeSession = session ?: return@withContext SendResult.Failure("Session Signal indisponible")
        return@withContext try {
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
                when (val send = deliverMessage(activeSession, convId, accId, recipient, initialMessage)) {
                    is SendResult.Failure -> send
                    else -> SendResult.Success(convId)
                }
            } else {
                SendResult.Success(convId)
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
        val activeSession = session ?: return@withContext SendResult.Failure("Session Signal indisponible")
        val remoteId = conversationId.removePrefix("${accId}_")
        if (remoteId == conversationId) {
            return@withContext SendResult.Failure("Conversation Signal invalide")
        }
        return@withContext try {
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
        kind: MessageKind = MessageKind.TEXT,
        payloadJson: String? = null,
        expireSeconds: Int? = null,
        configure: (SignalServiceDataMessage.Builder) -> Unit = {},
    ): SendResult {
        val helper = groupHelper ?: SignalGroupHelper(context, accId, credentialStore).also { groupHelper = it }
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
        if (expireSeconds != null && expireSeconds > 0) {
            builder.withExpiration(expireSeconds)
        }
        configure(builder)
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
            kind = kind,
            payloadJson = payloadJson,
            expireSeconds = expireSeconds,
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
        kind: MessageKind = MessageKind.TEXT,
        payloadJson: String? = null,
        expireSeconds: Int? = null,
        configure: (SignalServiceDataMessage.Builder) -> Unit = {},
    ): SendResult {
        val timestamp = System.currentTimeMillis()
        val builder = SignalServiceDataMessage.newBuilder()
            .withTimestamp(timestamp)
            .withBody(body.value)
        if (attachments.isNotEmpty()) {
            builder.withAttachments(attachments)
        }
        if (expireSeconds != null && expireSeconds > 0) {
            builder.withExpiration(expireSeconds)
        }
        configure(builder)
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
            kind = kind,
            payloadJson = payloadJson,
            expireSeconds = expireSeconds,
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

    override suspend fun sendContent(
        conversationId: String,
        content: OutgoingContent,
        accountId: String?,
    ): SendResult = withContext(signalDispatcher) {
        when (content) {
            is OutgoingContent.Text -> sendMessage(conversationId, content.body, accountId)
            is OutgoingContent.Media -> sendMedia(conversationId, content.attachment, content.caption, accountId)
            is OutgoingContent.Location ->
                SendResult.Failure("Partage GPS non supporté par Signal DataMessage")
            is OutgoingContent.Ephemeral -> sendEphemeral(conversationId, content, accountId)
            is OutgoingContent.VoiceNote -> sendVoiceNote(conversationId, content, accountId)
            is OutgoingContent.Sticker -> sendSticker(conversationId, content, accountId)
            is OutgoingContent.Poll -> sendPoll(conversationId, content, accountId)
            is OutgoingContent.ContactCard -> sendContactCard(conversationId, content, accountId)
        }
    }

    private suspend fun sendEphemeral(
        conversationId: String,
        content: OutgoingContent.Ephemeral,
        accountId: String?,
    ): SendResult {
        val accId = accountId ?: this.accountId ?: return SendResult.Failure("Compte non connecté")
        val activeSession = session ?: return SendResult.Failure("Session Signal indisponible")
        val remoteId = conversationId.removePrefix("${accId}_")
        if (remoteId == conversationId) return SendResult.Failure("Conversation Signal invalide")
        return try {
            val masterKey = SignalGroupHelper.parseMasterKey(remoteId)
            if (masterKey != null) {
                deliverGroupMessage(
                    activeSession, conversationId, accId, masterKey, content.body,
                    expireSeconds = content.expireSeconds,
                )
            } else {
                deliverMessage(
                    activeSession, conversationId, accId, resolveSignalAddress(remoteId), content.body,
                    expireSeconds = content.expireSeconds,
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Signal ephemeral send failed")
            SendResult.Failure(e.message ?: "Envoi éphémère échoué")
        }
    }

    private suspend fun uploadLocalAttachment(
        activeSession: SignalSessionContext,
        file: File,
        mimeType: String,
        fileName: String?,
        voiceNote: Boolean = false,
        caption: String? = null,
    ): Pair<SignalServiceAttachmentPointer, Attachment> {
        val form = activeSession.pushServiceSocket.attachmentV4UploadForm
        val uploadSpec = activeSession.pushServiceSocket.getResumableUploadSpec(form)
        return FileInputStream(file).use { input ->
            val streamBuilder = SignalServiceAttachment.newStreamBuilder()
                .withStream(input)
                .withContentType(mimeType)
                .withFileName(fileName ?: file.name)
                .withLength(file.length())
                .withCaption(caption)
                .withResumableUploadSpec(uploadSpec)
            if (voiceNote) {
                streamBuilder.withVoiceNote(true)
            }
            val pointer = activeSession.messageSender.uploadAttachment(streamBuilder.build())
            val local = Attachment(
                id = java.util.UUID.randomUUID().toString(),
                mimeType = mimeType,
                fileName = fileName ?: file.name,
                localPath = file.absolutePath,
                remoteRef = pointer.remoteId.toString(),
                sizeBytes = file.length(),
                state = AttachmentState.READY,
            )
            pointer to local
        }
    }

    private suspend fun sendVoiceNote(
        conversationId: String,
        content: OutgoingContent.VoiceNote,
        accountId: String?,
    ): SendResult {
        val accId = accountId ?: this.accountId ?: return SendResult.Failure("Compte non connecté")
        val activeSession = session ?: return SendResult.Failure("Session Signal indisponible")
        val remoteId = conversationId.removePrefix("${accId}_")
        if (remoteId == conversationId) return SendResult.Failure("Conversation Signal invalide")
        val path = content.attachment.localPath ?: return SendResult.Failure("Fichier vocal manquant")
        val file = File(path)
        if (!file.exists()) return SendResult.Failure("Fichier vocal introuvable")
        return try {
            val mime = content.attachment.mimeType.ifBlank { "audio/aac" }
            val (pointer, local) = uploadLocalAttachment(
                activeSession, file, mime, content.attachment.fileName ?: file.name, voiceNote = true,
            )
            val body = SanitizedText("🎤")
            val payload = JSONObject().put("durationMs", content.durationMs).toString()
            val masterKey = SignalGroupHelper.parseMasterKey(remoteId)
            if (masterKey != null) {
                deliverGroupMessage(
                    activeSession, conversationId, accId, masterKey, body,
                    attachments = listOf(pointer), localAttachment = local,
                    kind = MessageKind.VOICE, payloadJson = payload,
                )
            } else {
                deliverMessage(
                    activeSession, conversationId, accId, resolveSignalAddress(remoteId), body,
                    attachments = listOf(pointer), localAttachment = local,
                    kind = MessageKind.VOICE, payloadJson = payload,
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Signal voice note send failed")
            SendResult.Failure(e.message ?: "Envoi vocal échoué")
        }
    }

    private suspend fun sendSticker(
        conversationId: String,
        content: OutgoingContent.Sticker,
        accountId: String?,
    ): SendResult {
        // Signal stickers require a real packId/packKey from an installed sticker pack.
        // Random bytes are not interoperable with Signal clients — refuse rather than fake.
        return SendResult.Failure(
            "Stickers Signal nécessitent un pack installé (packId/packKey) — non supporté pour l'instant",
        )
    }

    private suspend fun sendPoll(
        conversationId: String,
        content: OutgoingContent.Poll,
        accountId: String?,
    ): SendResult {
        val accId = accountId ?: this.accountId ?: return SendResult.Failure("Compte non connecté")
        val activeSession = session ?: return SendResult.Failure("Session Signal indisponible")
        val remoteId = conversationId.removePrefix("${accId}_")
        if (remoteId == conversationId) return SendResult.Failure("Conversation Signal invalide")
        if (content.options.size < 2) return SendResult.Failure("Un sondage nécessite au moins 2 options")
        return try {
            val poll = SignalServiceDataMessage.PollCreate(
                content.question,
                content.multipleAnswers,
                content.options,
            )
            val body = SanitizedText(content.question)
            val optionsArr = JSONArray()
            content.options.forEach { optionsArr.put(it) }
            val payload = JSONObject()
                .put("question", content.question)
                .put("options", optionsArr)
                .put("multipleAnswers", content.multipleAnswers)
                .toString()
            val configure: (SignalServiceDataMessage.Builder) -> Unit = { it.withPollCreate(poll) }
            val masterKey = SignalGroupHelper.parseMasterKey(remoteId)
            if (masterKey != null) {
                deliverGroupMessage(
                    activeSession, conversationId, accId, masterKey, body,
                    kind = MessageKind.POLL, payloadJson = payload, configure = configure,
                )
            } else {
                deliverMessage(
                    activeSession, conversationId, accId, resolveSignalAddress(remoteId), body,
                    kind = MessageKind.POLL, payloadJson = payload, configure = configure,
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Signal poll send failed")
            SendResult.Failure(e.message ?: "Envoi sondage échoué")
        }
    }

    private suspend fun sendContactCard(
        conversationId: String,
        content: OutgoingContent.ContactCard,
        accountId: String?,
    ): SendResult {
        val accId = accountId ?: this.accountId ?: return SendResult.Failure("Compte non connecté")
        val activeSession = session ?: return SendResult.Failure("Session Signal indisponible")
        val remoteId = conversationId.removePrefix("${accId}_")
        if (remoteId == conversationId) return SendResult.Failure("Conversation Signal invalide")
        return try {
            val name = SharedContact.Name.newBuilder()
                .setGiven(content.firstName)
                .setFamily(content.lastName)
                .build()
            val builder = SharedContact.newBuilder().setName(name)
            content.phone?.takeIf { it.isNotBlank() }?.let { phone ->
                builder.withPhone(
                    SharedContact.Phone.newBuilder()
                        .setValue(phone)
                        .setType(SharedContact.Phone.Type.MOBILE)
                        .build(),
                )
            }
            val shared = builder.build()
            val display = "${content.firstName} ${content.lastName}".trim()
            val body = SanitizedText(display.ifBlank { content.phone ?: "Contact" })
            val payload = JSONObject()
                .put("firstName", content.firstName)
                .put("lastName", content.lastName)
                .put("phone", content.phone)
                .toString()
            val configure: (SignalServiceDataMessage.Builder) -> Unit = { it.withSharedContact(shared) }
            val masterKey = SignalGroupHelper.parseMasterKey(remoteId)
            if (masterKey != null) {
                deliverGroupMessage(
                    activeSession, conversationId, accId, masterKey, body,
                    kind = MessageKind.CONTACT, payloadJson = payload, configure = configure,
                )
            } else {
                deliverMessage(
                    activeSession, conversationId, accId, resolveSignalAddress(remoteId), body,
                    kind = MessageKind.CONTACT, payloadJson = payload, configure = configure,
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Signal contact card send failed")
            SendResult.Failure(e.message ?: "Envoi contact échoué")
        }
    }

    override fun observeContacts(accountId: String): Flow<List<Contact>> =
        repository.observeContacts(accountId)

    override suspend fun refreshContacts(accountId: String): Result<Int> = withContext(signalDispatcher) {
        val activeSession = session
            ?: return@withContext Result.failure(IllegalStateException("Session Signal indisponible"))
        runCatching {
            val stored = repository.observeContacts(accountId).first()
            val conversationNumbers = repository.listConversationsForAccount(accountId)
                .map { it.remoteId }
                .filter { it.startsWith("+") }
                .toSet()
            val phoneCandidates = (
                stored.mapNotNull { it.phone?.takeIf { p -> p.startsWith("+") } } +
                    conversationNumbers
                ).toSet()

            if (phoneCandidates.isNotEmpty()) {
                val results = SignalFeatureHelpers.lookupRegisteredUsers(
                    activeSession,
                    phoneCandidates,
                    previousToken = null,
                ) { token ->
                    credentialStore.put(
                        accountId,
                        SignalCredentialKeys.CDSI_TOKEN,
                        android.util.Base64.encodeToString(token, android.util.Base64.NO_WRAP),
                    )
                }
                if (results.isNotEmpty()) {
                    val contacts = results.map { (e164, item) ->
                        val remoteId = item.aci.map { it.toString() }.orElse(e164)
                        val existingName = stored.firstOrNull {
                            it.phone == e164 || it.remoteId == remoteId
                        }?.displayName
                        Contact(
                            id = "${accountId}_$remoteId",
                            protocol = ProtocolId.SIGNAL,
                            accountId = accountId,
                            remoteId = remoteId,
                            displayName = existingName ?: e164,
                            handle = item.aci.map { it.toString() }.orElse(null),
                            phone = e164,
                        )
                    }
                    repository.replaceContacts(accountId, contacts)
                    return@runCatching contacts.size
                }
            }
            // Fallback: expose contacts already stored from SyncMessage.Contacts
            stored.size
        }
    }

    override suspend fun setTyping(conversationId: String, typing: Boolean) {
        withContext(signalDispatcher) {
            val accId = accountId ?: return@withContext
            val activeSession = session ?: return@withContext
            val remoteId = conversationId.removePrefix("${accId}_")
            if (remoteId == conversationId) return@withContext
            try {
                val action = if (typing) {
                    SignalServiceTypingMessage.Action.STARTED
                } else {
                    SignalServiceTypingMessage.Action.STOPPED
                }
                val masterKey = SignalGroupHelper.parseMasterKey(remoteId)
                val groupId = masterKey?.let {
                    // GroupId for typing is derived from master key via helper when available; optional empty for fan-out.
                    Optional.empty<ByteArray>()
                } ?: Optional.empty()
                val typingMessage = SignalServiceTypingMessage(action, System.currentTimeMillis(), groupId)
                if (masterKey != null) {
                    val helper = groupHelper ?: return@withContext
                    val plan = helper.resolveSendTargets(activeSession, masterKey) ?: return@withContext
                    if (plan.canUseSenderKeys) {
                        activeSession.messageSender.sendGroupTyping(
                            plan.distributionId,
                            plan.recipients,
                            plan.unidentifiedAccess,
                            plan.groupSendEndorsements!!,
                            typingMessage,
                        )
                    } else {
                        val sealed = List(plan.recipients.size) { SealedSenderAccess.NONE }
                        activeSession.messageSender.sendTyping(plan.recipients, sealed, typingMessage, null)
                    }
                } else {
                    val recipient = resolveSignalAddress(remoteId)
                    activeSession.messageSender.sendTyping(
                        listOf(recipient),
                        listOf(SealedSenderAccess.NONE),
                        typingMessage,
                        null,
                    )
                }
            } catch (e: Exception) {
                Timber.d(e, "Signal typing send failed")
            }
        }
    }

    override suspend fun markRead(conversationId: String, messageId: String?) {
        withContext(signalDispatcher) {
            val accId = accountId ?: return@withContext
            val activeSession = session ?: return@withContext
            val remoteId = conversationId.removePrefix("${accId}_")
            if (remoteId == conversationId) return@withContext
            // Read receipts are 1:1 only.
            if (SignalGroupHelper.parseMasterKey(remoteId) != null) {
                clearUnread(conversationId, accId, remoteId)
                return@withContext
            }
            try {
                val messages = repository.observeMessages(conversationId).first()
                val timestamps = when {
                    messageId != null -> {
                        val ts = messages.firstOrNull { it.id == messageId }?.timestamp
                            ?: messageId.substringAfterLast('_').toLongOrNull()
                        listOfNotNull(ts)
                    }
                    else -> messages
                        .filter {
                            it.direction == ltechnologies.onionphone.securemessenger.core.model.MessageDirection.INCOMING
                        }
                        .map { it.timestamp }
                        .takeLast(20)
                }
                if (timestamps.isNotEmpty()) {
                    val receipt = SignalServiceReceiptMessage(
                        SignalServiceReceiptMessage.Type.READ,
                        timestamps,
                        System.currentTimeMillis(),
                    )
                    activeSession.messageSender.sendReceipt(
                        resolveSignalAddress(remoteId),
                        SealedSenderAccess.NONE,
                        receipt,
                        false,
                    )
                }
                clearUnread(conversationId, accId, remoteId)
            } catch (e: Exception) {
                Timber.d(e, "Signal markRead failed")
            }
        }
    }

    private suspend fun clearUnread(conversationId: String, accId: String, remoteId: String) {
        val existing = repository.listConversationsForAccount(accId)
            .firstOrNull { it.id == conversationId }
            ?: return
        if (existing.unreadCount > 0) {
            repository.upsertConversation(existing.copy(unreadCount = 0, remoteId = remoteId))
        }
    }

    override suspend fun getAccountProfile(accountId: String): AccountProfile? = withContext(signalDispatcher) {
        val activeSession = session
        val storedName = credentialStore.get(accountId, SignalCredentialKeys.PROFILE_NAME)
        val storedAbout = credentialStore.get(accountId, SignalCredentialKeys.PROFILE_ABOUT)
        if (activeSession != null && activeSession.profileKey != null) {
            runCatching {
                val result = activeSession.profileApi.getVersionedProfile(
                    activeSession.aci,
                    activeSession.profileKey,
                    null,
                )
                val profile = result.successOrThrow()
                val (name, about) = SignalFeatureHelpers.decryptProfile(activeSession.profileKey, profile)
                if (name != null) credentialStore.put(accountId, SignalCredentialKeys.PROFILE_NAME, name)
                if (about != null) credentialStore.put(accountId, SignalCredentialKeys.PROFILE_ABOUT, about)
                return@withContext AccountProfile(
                    accountId = accountId,
                    protocol = ProtocolId.SIGNAL,
                    displayName = name ?: storedName ?: activeSession.e164,
                    phone = activeSession.e164,
                    bio = about ?: storedAbout,
                )
            }.onFailure { Timber.d(it, "Signal profile fetch failed") }
        }
        AccountProfile(
            accountId = accountId,
            protocol = ProtocolId.SIGNAL,
            displayName = storedName ?: activeSession?.e164 ?: pendingE164 ?: accountId,
            phone = activeSession?.e164 ?: pendingE164,
            bio = storedAbout,
        )
    }

    override suspend fun updateAccountProfile(
        accountId: String,
        displayName: String,
        bio: String?,
    ): Result<Unit> = withContext(signalDispatcher) {
        val activeSession = session
            ?: return@withContext Result.failure(IllegalStateException("Session Signal indisponible"))
        runCatching {
            var profileKey = activeSession.profileKey
            if (profileKey == null) {
                profileKey = SignalFeatureHelpers.generateProfileKey()
                credentialStore.put(accountId, SignalCredentialKeys.PROFILE_KEY, SignalFeatureHelpers.encodeProfileKey(profileKey))
                // Re-open session so profileKey is available next connect; use generated key for this call.
            }
            val result = activeSession.profileApi.setVersionedProfile(
                activeSession.aci,
                profileKey!!,
                displayName,
                bio,
                null,
                null,
                AvatarUploadParams.unchanged(false),
                emptyList(),
                false,
            )
            result.successOrThrow()
            credentialStore.put(accountId, SignalCredentialKeys.PROFILE_NAME, displayName)
            if (bio != null) {
                credentialStore.put(accountId, SignalCredentialKeys.PROFILE_ABOUT, bio)
            }
            Unit
        }
    }

    override suspend fun exportBackup(accountId: String, destinationPath: String) =
        withContext(signalDispatcher) {
            runCatching {
                val (convs, messages) = repository.exportSnapshot(accountId)
                val root = org.json.JSONObject()
                    .put("protocol", "SIGNAL")
                    .put("accountId", accountId)
                    .put("exportedAt", System.currentTimeMillis())
                val convArr = org.json.JSONArray()
                convs.forEach { c ->
                    convArr.put(
                        org.json.JSONObject()
                            .put("id", c.id)
                            .put("title", c.title)
                            .put("remoteId", c.remoteId),
                    )
                }
                val msgArr = org.json.JSONArray()
                messages.forEach { m ->
                    msgArr.put(
                        org.json.JSONObject()
                            .put("id", m.id)
                            .put("conversationId", m.conversationId)
                            .put("body", m.body)
                            .put("timestamp", m.timestamp)
                            .put("kind", m.kind.name),
                    )
                }
                root.put("conversations", convArr)
                root.put("messages", msgArr)
                java.io.File(destinationPath).writeText(root.toString(2))
                ltechnologies.onionphone.securemessenger.core.model.BackupExportResult.Success(
                    destinationPath,
                    messages.size,
                    convs.size,
                )
            }.getOrElse {
                ltechnologies.onionphone.securemessenger.core.model.BackupExportResult.Failure(
                    it.message ?: "Export échoué",
                )
            }
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
                cancelDeviceLinkLocked()
                pendingE164 = null
                pendingPassword = null
                pendingSessionId = null
                pendingPreKeys = null
                _pendingAuthStep.value = null
                this@SignalProtocol.accountId = null
                proxyConfig = null
                SignalSocksHolder.clear()
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
