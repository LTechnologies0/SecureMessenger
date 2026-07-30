package ltechnologies.onionphone.securemessenger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ltechnologies.onionphone.securemessenger.core.model.ProtocolCapabilities
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId

/** Honest capability chips — only shows flags that are true. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProtocolCapabilityChips(
    protocol: ProtocolId,
    caps: ProtocolCapabilities,
    modifier: Modifier = Modifier,
) {
    val items = buildList {
        add(protocolShortPrefix(protocol))
        if (caps.endToEndEncryption) add("E2EE")
        if (caps.messageHistory) add("historique")
        if (caps.contacts) add("contacts")
        if (caps.profileEdit) add("profil")
        if (caps.mediaSend) add("médias")
        if (caps.voiceNotes) add("vocal")
        if (caps.gifs) add("GIF")
        if (caps.stickers) add("stickers")
        if (caps.locationShare) add("localisation")
        if (caps.polls) add("sondages")
        if (caps.contactShare) add("contact")
        if (caps.ephemeralMessages) add("éphémères")
        if (caps.typingIndicators) add("frappe")
        if (caps.readReceipts) add("lus")
        if (caps.groupChats) add("groupes")
        if (caps.backupExport) add("export")
        if (caps.requiresPhoneAuth) add("téléphone")
    }
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEachIndexed { index, label ->
            if (index == 0) {
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                )
            } else {
                FilterChip(
                    selected = true,
                    onClick = {},
                    enabled = false,
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
