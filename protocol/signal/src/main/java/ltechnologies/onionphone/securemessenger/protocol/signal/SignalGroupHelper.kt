package ltechnologies.onionphone.securemessenger.protocol.signal

import android.content.Context
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.signal.core.models.ServiceId.ACI
import org.signal.libsignal.metadata.certificate.SenderCertificate
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.signal.libsignal.zkgroup.groups.GroupSecretParams
import org.whispersystems.signalservice.api.crypto.UnidentifiedAccess
import org.whispersystems.signalservice.api.groupsv2.GroupSendEndorsements
import org.whispersystems.signalservice.api.groupsv2.toAciList
import org.whispersystems.signalservice.api.messages.SignalServiceGroupV2
import org.whispersystems.signalservice.api.push.DistributionId
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import timber.log.Timber

/**
 * GV2 helper: member cache, GroupsV2 refresh, DistributionId, and sender-key send plan
 * (GroupSendEndorsements + UnidentifiedAccess + SenderCertificate).
 */
internal class SignalGroupHelper(
    context: Context,
    private val accountId: String,
) {
    private val prefs = context.getSharedPreferences("signal_gv2_$accountId", Context.MODE_PRIVATE)

    fun rememberMember(masterKeyBytes: ByteArray, memberAci: String) {
        val key = prefsKey(masterKeyBytes)
        val existing = prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (existing.add(memberAci)) {
            prefs.edit().putStringSet(key, existing).apply()
        }
        if (!prefs.contains(revPrefsKey(masterKeyBytes))) {
            prefs.edit().putInt(revPrefsKey(masterKeyBytes), 0).apply()
        }
    }

    fun rememberRevision(masterKeyBytes: ByteArray, revision: Int) {
        prefs.edit().putInt(revPrefsKey(masterKeyBytes), revision.coerceAtLeast(0)).apply()
    }

    fun cachedMembers(masterKeyBytes: ByteArray): Set<String> =
        prefs.getStringSet(prefsKey(masterKeyBytes), emptySet()).orEmpty()

    fun cachedRevision(masterKeyBytes: ByteArray): Int =
        prefs.getInt(revPrefsKey(masterKeyBytes), 0)

    fun cachedTitle(masterKeyBytes: ByteArray): String? =
        prefs.getString(titlePrefsKey(masterKeyBytes), null)

    fun distributionId(masterKeyBytes: ByteArray): DistributionId {
        val key = distPrefsKey(masterKeyBytes)
        val existing = prefs.getString(key, null)
        if (existing != null) {
            return runCatching { DistributionId.from(UUID.fromString(existing)) }
                .getOrElse { DistributionId.create().also { persistDistributionId(masterKeyBytes, it) } }
        }
        return DistributionId.create().also { persistDistributionId(masterKeyBytes, it) }
    }

    private fun persistDistributionId(masterKeyBytes: ByteArray, id: DistributionId) {
        prefs.edit().putString(distPrefsKey(masterKeyBytes), id.asUuid().toString()).apply()
    }

    /**
     * Builds a sender-key send plan: refresh group state + endorsements from storage,
     * fetch delivery certificate, and assemble UnidentifiedAccess for each recipient.
     * Falls back to recipient list only (legacy fan-out) when endorsements/cert are unavailable.
     */
    fun resolveSendTargets(
        session: SignalSessionContext,
        masterKeyBytes: ByteArray,
    ): GroupSendPlan? {
        val masterKey = GroupMasterKey(masterKeyBytes)
        val secretParams = GroupSecretParams.deriveFromMasterKey(masterKey)
        val fetched = fetchGroupState(session, masterKeyBytes, secretParams)
        val members = fetched?.members
            ?: cachedMembers(masterKeyBytes).takeIf { it.isNotEmpty() }
            ?: return null
        val revision = fetched?.revision ?: cachedRevision(masterKeyBytes)
        val recipients = members.mapNotNull { aciString ->
            runCatching { SignalServiceAddress(ACI.parseOrThrow(aciString)) }.getOrNull()
        }
        if (recipients.isEmpty()) return null

        val groupContext = SignalServiceGroupV2.newBuilder(masterKey)
            .withRevision(revision)
            .build()
        val distributionId = distributionId(masterKeyBytes)

        val senderKey = tryBuildSenderKeyPlan(session, secretParams, fetched, recipients)
        return GroupSendPlan(
            recipients = recipients,
            groupContext = groupContext,
            distributionId = distributionId,
            groupSendEndorsements = senderKey?.endorsements,
            unidentifiedAccess = senderKey?.unidentifiedAccess.orEmpty(),
        )
    }

    private data class FetchedGroup(
        val members: Set<String>,
        val revision: Int,
        val endorsementsResponse: org.signal.libsignal.zkgroup.groupsend.GroupSendEndorsementsResponse?,
        val decryptedGroup: org.signal.storageservice.storage.protos.groups.local.DecryptedGroup,
    )

    private data class SenderKeyParts(
        val endorsements: GroupSendEndorsements,
        val unidentifiedAccess: List<UnidentifiedAccess>,
    )

    private fun fetchGroupState(
        session: SignalSessionContext,
        masterKeyBytes: ByteArray,
        secretParams: GroupSecretParams,
    ): FetchedGroup? = try {
        val groupsApi = session.accountManager.groupsV2Api
        val todaySeconds = TimeUnit.DAYS.toSeconds(TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis()))
        val credentialMaps = groupsApi.getCredentials(todaySeconds)
        val entry = credentialMaps.authCredentialWithPniResponseHashMap.entries
            .firstOrNull { it.key == todaySeconds }
            ?: credentialMaps.authCredentialWithPniResponseHashMap.entries.firstOrNull()
            ?: return null
        val authString = groupsApi.getGroupsV2AuthorizationString(
            session.aci,
            session.pni,
            entry.key,
            secretParams,
            entry.value,
        )
        val response = groupsApi.getGroup(secretParams, authString)
        val members = response.group.members.toAciList()
            .map { it.toString() }
            .filter { it != session.aci.toString() }
            .toSet()
        prefs.edit()
            .putStringSet(prefsKey(masterKeyBytes), members)
            .putInt(revPrefsKey(masterKeyBytes), response.group.revision)
            .apply()
        if (response.group.title.isNotBlank()) {
            prefs.edit().putString(titlePrefsKey(masterKeyBytes), response.group.title).apply()
        }
        FetchedGroup(
            members = members,
            revision = response.group.revision,
            endorsementsResponse = response.groupSendEndorsementsResponse,
            decryptedGroup = response.group,
        )
    } catch (e: Exception) {
        Timber.w(e, "GV2 fetch failed; using cache")
        null
    }

    private fun tryBuildSenderKeyPlan(
        session: SignalSessionContext,
        secretParams: GroupSecretParams,
        fetched: FetchedGroup?,
        recipients: List<SignalServiceAddress>,
    ): SenderKeyParts? {
        if (fetched == null) return null
        return try {
            val received = session.groupsV2Operations.forGroup(secretParams)
                .receiveGroupSendEndorsements(
                    session.aci,
                    fetched.decryptedGroup,
                    fetched.endorsementsResponse,
                ) ?: return null
            val certBytes = session.pushServiceSocket.senderCertificate
            val senderCertificate = SenderCertificate(certBytes)
            val endorsements = GroupSendEndorsements(
                expirationMs = received.expirationMs,
                endorsements = received.endorsements,
                sealedSenderCertificate = senderCertificate,
                groupSecretParams = secretParams,
            )
            // Access keys are synthetic; GSE tokens provide the sealed-sender auth path.
            val accessKey = ByteArray(16)
            val ua = recipients.map {
                UnidentifiedAccess(accessKey, certBytes, /* isUnrestrictedForStory = */ true)
            }
            SenderKeyParts(endorsements, ua)
        } catch (e: Exception) {
            Timber.w(e, "GV2 sender-key plan unavailable; will fan-out")
            null
        }
    }

    data class GroupSendPlan(
        val recipients: List<SignalServiceAddress>,
        val groupContext: SignalServiceGroupV2,
        val distributionId: DistributionId,
        val groupSendEndorsements: GroupSendEndorsements?,
        val unidentifiedAccess: List<UnidentifiedAccess>,
    ) {
        val canUseSenderKeys: Boolean
            get() = groupSendEndorsements != null &&
                unidentifiedAccess.size == recipients.size &&
                recipients.isNotEmpty()
    }

    companion object {
        fun parseMasterKey(remoteId: String): ByteArray? {
            if (!remoteId.startsWith("gv2:")) return null
            return try {
                runCatching {
                    android.util.Base64.decode(remoteId.removePrefix("gv2:"), android.util.Base64.NO_WRAP)
                }.getOrElse {
                    java.util.Base64.getDecoder().decode(remoteId.removePrefix("gv2:"))
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun prefsKey(masterKeyBytes: ByteArray): String =
            "members_" + java.util.Base64.getEncoder().encodeToString(masterKeyBytes)

        private fun revPrefsKey(masterKeyBytes: ByteArray): String =
            "rev_" + java.util.Base64.getEncoder().encodeToString(masterKeyBytes)

        private fun titlePrefsKey(masterKeyBytes: ByteArray): String =
            "title_" + java.util.Base64.getEncoder().encodeToString(masterKeyBytes)

        private fun distPrefsKey(masterKeyBytes: ByteArray): String =
            "dist_" + java.util.Base64.getEncoder().encodeToString(masterKeyBytes)
    }
}
