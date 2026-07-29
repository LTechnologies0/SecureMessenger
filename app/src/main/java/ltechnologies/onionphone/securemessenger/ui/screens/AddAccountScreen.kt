package ltechnologies.onionphone.securemessenger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import ltechnologies.onionphone.securemessenger.core.model.FeatureFlags
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.ui.components.protocolAccentColor

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AddAccountScreen(
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
    onPickTelegram: () -> Unit,
    onPickSignal: () -> Unit,
    onPickProtocol: (ProtocolId) -> Unit,
) {
    val choices = listOf(
        ProtocolChoice(
            protocol = ProtocolId.SIGNAL,
            title = "Signal",
            subtitle = "Numéro E.164 + code SMS",
            onClick = onPickSignal,
        ),
        ProtocolChoice(
            protocol = ProtocolId.TELEGRAM,
            title = "Telegram",
            subtitle = "Numéro de téléphone + code SMS",
            onClick = onPickTelegram,
        ),
        ProtocolChoice(
            protocol = ProtocolId.MATRIX,
            title = "Matrix",
            subtitle = "Identifiant Matrix + mot de passe",
            onClick = { onPickProtocol(ProtocolId.MATRIX) },
        ),
        ProtocolChoice(
            protocol = ProtocolId.XMPP,
            title = "XMPP",
            subtitle = "JID + mot de passe",
            onClick = { onPickProtocol(ProtocolId.XMPP) },
        ),
    ).filter { it.protocol in FeatureFlags.enabled }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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

        choices.forEach { choice ->
            ProtocolChoiceCard(
                title = choice.title,
                subtitle = choice.subtitle,
                accent = protocolAccentColor(choice.protocol),
                onClick = choice.onClick,
            )
        }
    }
}

private data class ProtocolChoice(
    val protocol: ProtocolId,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit,
)

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
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
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
