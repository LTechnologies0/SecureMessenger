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
import androidx.compose.material.icons.filled.Forum
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
}

fun protocolDisplayName(protocol: ProtocolId): String = when (protocol) {
    ProtocolId.TELEGRAM -> "Telegram"
    ProtocolId.MATRIX -> "Matrix"
    ProtocolId.XMPP -> "XMPP"
    ProtocolId.SIGNAL -> "Signal"
    ProtocolId.EMAIL -> "Email"
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
}

fun protocolIcon(protocol: ProtocolId): ImageVector = when (protocol) {
    ProtocolId.TELEGRAM -> Icons.AutoMirrored.Filled.Send
    ProtocolId.MATRIX -> Icons.Default.Forum
    ProtocolId.XMPP -> Icons.AutoMirrored.Filled.Chat
    ProtocolId.SIGNAL -> Icons.AutoMirrored.Filled.Chat
    ProtocolId.EMAIL -> Icons.Default.Email
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
        if (caps.locationShare) add("lieu")
        if (caps.polls) add("sondages")
        if (caps.contactShare) add("vCard")
        if (caps.ephemeralMessages) add("éphémère")
        if (caps.messageHistory) add("historique")
        if (caps.backupExport) add("backup")
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
