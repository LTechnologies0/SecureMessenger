package ltechnologies.onionphone.securemessenger.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import ltechnologies.onionphone.securemessenger.core.model.Account
import ltechnologies.onionphone.securemessenger.core.model.ConnectionState
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.ui.theme.PrimaryBlue
import ltechnologies.onionphone.securemessenger.ui.theme.SecondaryTeal
import ltechnologies.onionphone.securemessenger.ui.theme.TertiaryAmber

fun protocolShortPrefix(protocol: ProtocolId): String = when (protocol) {
    ProtocolId.TELEGRAM -> "TG"
    ProtocolId.MATRIX -> "Matrix"
    ProtocolId.XMPP -> "XMPP"
    ProtocolId.SIGNAL -> "Signal"
}

fun accountRailLabel(account: Account, indexAmongProtocol: Int): String {
    val prefix = protocolShortPrefix(account.protocol)
    return "$prefix#${indexAmongProtocol + 1}"
}

fun protocolAccentColor(protocol: ProtocolId): Color = when (protocol) {
    ProtocolId.TELEGRAM -> PrimaryBlue
    ProtocolId.MATRIX -> SecondaryTeal
    ProtocolId.XMPP -> TertiaryAmber
    ProtocolId.SIGNAL -> Color(0xFF3A76F0)
}

fun protocolIcon(protocol: ProtocolId): ImageVector = when (protocol) {
    ProtocolId.TELEGRAM -> Icons.Default.Send
    ProtocolId.MATRIX -> Icons.Default.Forum
    ProtocolId.XMPP -> Icons.AutoMirrored.Filled.Chat
    ProtocolId.SIGNAL -> Icons.AutoMirrored.Filled.Chat
}

@Composable
fun connectionIndicatorColor(state: ConnectionState): Color = when (state) {
    ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
    ConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
    ConnectionState.ERROR -> MaterialTheme.colorScheme.error
    ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outline
}
