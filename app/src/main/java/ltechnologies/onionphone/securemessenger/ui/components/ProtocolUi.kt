package ltechnologies.onionphone.securemessenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ltechnologies.onionphone.securemessenger.core.model.Account
import ltechnologies.onionphone.securemessenger.core.model.ConnectionState
import ltechnologies.onionphone.securemessenger.core.model.ProtocolCapabilities
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.ui.theme.ConnectedGreen
import ltechnologies.onionphone.securemessenger.ui.theme.ConnectedGreenDark
import ltechnologies.onionphone.securemessenger.ui.theme.ConnectingAmber
import ltechnologies.onionphone.securemessenger.ui.theme.EmailRose
import ltechnologies.onionphone.securemessenger.ui.theme.IrcGreen
import ltechnologies.onionphone.securemessenger.ui.theme.PrimaryBlue
import ltechnologies.onionphone.securemessenger.ui.theme.SecondaryTeal
import ltechnologies.onionphone.securemessenger.ui.theme.SignalBlue
import ltechnologies.onionphone.securemessenger.ui.theme.TertiaryAmber

fun protocolShortPrefix(protocol: ProtocolId): String = when (protocol) {
    ProtocolId.TELEGRAM -> "TG"
    ProtocolId.MATRIX -> "Matrix"
    ProtocolId.XMPP -> "XMPP"
    ProtocolId.SIGNAL -> "Signal"
    ProtocolId.EMAIL -> "Mail"
    ProtocolId.IRC -> "IRC"
}

fun protocolDisplayName(protocol: ProtocolId): String = when (protocol) {
    ProtocolId.TELEGRAM -> "Telegram"
    ProtocolId.MATRIX -> "Matrix"
    ProtocolId.XMPP -> "XMPP"
    ProtocolId.SIGNAL -> "Signal"
    ProtocolId.EMAIL -> "Email"
    ProtocolId.IRC -> "IRC"
}

fun accountRailLabel(account: Account, indexAmongProtocol: Int): String {
    val prefix = protocolShortPrefix(account.protocol)
    return "$prefix#${indexAmongProtocol + 1}"
}

fun protocolAccentColor(protocol: ProtocolId): Color = when (protocol) {
    ProtocolId.TELEGRAM -> PrimaryBlue
    ProtocolId.MATRIX -> SecondaryTeal
    ProtocolId.XMPP -> TertiaryAmber
    ProtocolId.SIGNAL -> SignalBlue
    ProtocolId.EMAIL -> EmailRose
    ProtocolId.IRC -> IrcGreen
}

fun protocolIcon(protocol: ProtocolId): ImageVector = when (protocol) {
    ProtocolId.TELEGRAM -> Icons.AutoMirrored.Filled.Send
    ProtocolId.MATRIX -> Icons.Default.Hub
    ProtocolId.XMPP -> Icons.AutoMirrored.Filled.Chat
    ProtocolId.SIGNAL -> Icons.Default.Lock
    ProtocolId.EMAIL -> Icons.Default.Email
    ProtocolId.IRC -> Icons.Default.Tag
}

fun connectionStateLabel(state: ConnectionState): String = when (state) {
    ConnectionState.CONNECTED -> "Connecté"
    ConnectionState.CONNECTING -> "Connexion…"
    ConnectionState.DISCONNECTED -> "Déconnecté"
    ConnectionState.ERROR -> "Erreur"
}

@Composable
fun connectionIndicatorColor(state: ConnectionState): Color = when (state) {
    ConnectionState.CONNECTED ->
        if (MaterialTheme.colorScheme.background.luminance() < 0.5f) ConnectedGreenDark else ConnectedGreen
    ConnectionState.CONNECTING -> ConnectingAmber
    ConnectionState.ERROR -> MaterialTheme.colorScheme.error
    ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outline
}

private fun Color.luminance(): Float {
    val r = red
    val g = green
    val b = blue
    return 0.299f * r + 0.587f * g + 0.114f * b
}

fun inboxEmptySubtitle(protocol: ProtocolId?): String = when (protocol) {
    ProtocolId.TELEGRAM -> "Ouvre un chat avec un @username, un numéro ou un ID Telegram."
    ProtocolId.SIGNAL -> "Démarre un DM avec un numéro E.164 ou un ACI Signal."
    ProtocolId.XMPP -> "Discute via JID (ami@domain) ou rejoins une salle MUC."
    ProtocolId.MATRIX -> "Ouvre une room (!id:serveur) ou un DM (@user:serveur)."
    ProtocolId.EMAIL -> "Les fils récents apparaissent ici (fenêtre IMAP/JMAP limitée)."
    ProtocolId.IRC -> "Rejoins un canal (#libera) ou envoie un PRIVMSG à un nick."
    null -> "Lance un nouveau chat pour démarrer."
}

fun newChatFabLabel(protocol: ProtocolId?): String = when (protocol) {
    ProtocolId.EMAIL -> "Nouveau message"
    ProtocolId.IRC -> "Canal / DM"
    else -> "Nouveau chat"
}

fun chatComposerPlaceholder(protocol: ProtocolId): String = when (protocol) {
    ProtocolId.EMAIL -> "Corps du message"
    ProtocolId.IRC -> "Message IRC"
    ProtocolId.SIGNAL -> "Message Signal"
    ProtocolId.XMPP -> "Message XMPP"
    ProtocolId.MATRIX -> "Message Matrix"
    ProtocolId.TELEGRAM -> "Message"
}

fun conversationKindHint(protocol: ProtocolId, remoteId: String): String? {
    val r = remoteId.trim()
    return when (protocol) {
        ProtocolId.IRC ->
            if (r.startsWith("#") || r.startsWith("&") || r.startsWith("+")) "Canal IRC" else "Message privé"
        ProtocolId.XMPP ->
            if (r.contains("@conference.") || r.contains("@groups.")) "Salle MUC" else "JID direct"
        ProtocolId.MATRIX ->
            if (r.startsWith("!")) "Room Matrix" else "Conversation directe"
        ProtocolId.EMAIL -> "Fil email"
        ProtocolId.SIGNAL ->
            if (r.startsWith("gv2:")) "Groupe Signal (GV2)" else "DM Signal"
        ProtocolId.TELEGRAM -> null
    }
}

fun contactsEmptyHint(protocol: ProtocolId): String = when (protocol) {
    ProtocolId.TELEGRAM -> "Synchronise les contacts Telegram ou cherche par numéro dans Nouveau chat."
    ProtocolId.SIGNAL -> "Les contacts Signal apparaissent après synchronisation du carnet chiffré."
    ProtocolId.XMPP -> "Le roster XMPP se remplit depuis le serveur et vos salles."
    ProtocolId.MATRIX -> "Contacts Matrix locaux et membres de rooms synchronisés."
    ProtocolId.EMAIL -> "Adresses tirées des fils récents — pas un carnet serveur complet."
    ProtocolId.IRC -> "Nicks visibles dans vos canaux (LIST / JOIN). Tapez /me action pour un CTCP ACTION."
}

/** Tab / screen title for the contacts surface (IRC is nicks, not an address book). */
fun contactsScreenTitle(protocol: ProtocolId?): String = when (protocol) {
    ProtocolId.IRC -> "Nicks"
    ProtocolId.EMAIL -> "Adresses"
    null -> "Contacts"
    else -> "Contacts"
}

fun capabilityLabels(caps: ProtocolCapabilities?, canRegister: Boolean = false): List<String> {
    if (caps == null) return emptyList()
    return buildList {
        if (caps.directMessages) add("DM")
        if (caps.groupChats) add("groupes")
        if (caps.mediaSend) add("médias")
        if (caps.endToEndEncryption) add("E2EE")
        if (caps.typingIndicators) add("frappe")
        if (caps.readReceipts) add("lus")
        if (caps.requiresPhoneAuth) add("téléphone")
        if (caps.contacts) add("contacts")
        if (caps.profileEdit) add("profil")
        if (caps.voiceNotes) add("vocal")
        if (caps.gifs) add("GIF")
        if (caps.stickers) add("stickers")
        if (caps.locationShare) add("localisation")
        if (caps.polls) add("sondages")
        if (caps.contactShare) add("contact")
        if (caps.ephemeralMessages) add("éphémères")
        if (caps.messageHistory) add("historique")
        if (caps.reactions) add("réactions")
        if (caps.messageEdit) add("édition")
        if (caps.messageDelete) add("suppression")
        if (caps.messageForward) add("transfert")
        if (caps.backupExport) add("export")
        if (canRegister) add("inscription")
    }
}

@Composable
fun ProtocolAccentChip(
    protocol: ProtocolId,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val accent = protocolAccentColor(protocol)
    if (onClick != null) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = { Text(protocolShortPrefix(protocol)) },
            leadingIcon = {
                Icon(
                    protocolIcon(protocol),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = accent,
                )
            },
            modifier = modifier,
        )
    } else {
        SuggestionChip(
            onClick = {},
            enabled = false,
            label = {
                Text(
                    protocolShortPrefix(protocol),
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            icon = {
                Icon(
                    protocolIcon(protocol),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = accent,
                )
            },
            modifier = modifier,
        )
    }
}

@Composable
fun ProtocolAvatar(
    protocol: ProtocolId,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 40.dp,
) {
    val accent = protocolAccentColor(protocol)
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = protocolIcon(protocol),
            contentDescription = protocolDisplayName(protocol),
            tint = accent,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

@Composable
fun ConnectionDot(
    state: ConnectionState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(connectionIndicatorColor(state)),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CapabilityChipRow(
    labels: List<String>,
    modifier: Modifier = Modifier,
) {
    if (labels.isEmpty()) {
        Text(
            "Capacités indisponibles (déverrouillez / reconnectez)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEach { label ->
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.padding(0.dp),
            )
        }
    }
}
