package ltechnologies.onionphone.securemessenger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.ui.components.protocolAccentColor
import ltechnologies.onionphone.securemessenger.ui.components.protocolShortPrefix

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AddAccountScreen(
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
    onPickTelegram: () -> Unit,
    onPickProtocol: (ProtocolId) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TopAppBar(
            title = { Text("Ajouter un compte") },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                }
            },
        )

        Text(
            text = "Choisissez un service",
            style = MaterialTheme.typography.headlineSmall,
        )

        ProtocolChoiceCard(
            title = "Telegram",
            subtitle = "Numéro de téléphone + code SMS",
            accent = protocolAccentColor(ProtocolId.TELEGRAM),
            onClick = onPickTelegram,
        )
        ProtocolChoiceCard(
            title = protocolShortPrefix(ProtocolId.MATRIX),
            subtitle = "Identifiant Matrix + mot de passe",
            accent = protocolAccentColor(ProtocolId.MATRIX),
            onClick = { onPickProtocol(ProtocolId.MATRIX) },
        )
        ProtocolChoiceCard(
            title = protocolShortPrefix(ProtocolId.XMPP),
            subtitle = "JID + mot de passe",
            accent = protocolAccentColor(ProtocolId.XMPP),
            onClick = { onPickProtocol(ProtocolId.XMPP) },
        )
    }
}

@Composable
private fun ProtocolChoiceCard(
    title: String,
    subtitle: String,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.largeIncreased,
        color = accent.copy(alpha = 0.12f),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
