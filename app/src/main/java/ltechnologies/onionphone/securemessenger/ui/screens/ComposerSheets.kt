package ltechnologies.onionphone.securemessenger.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import ltechnologies.onionphone.securemessenger.core.model.ProtocolCapabilities

internal enum class ComposerPickKind {
    IMAGE,
    FILE,
    GIF,
    VOICE,
}

internal enum class ComposerDialog {
    NONE,
    LOCATION,
    POLL,
    CONTACT,
    EPHEMERAL,
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ComposerAttachSheet(
    capabilities: ProtocolCapabilities,
    onDismiss: () -> Unit,
    onPickMedia: (ComposerPickKind) -> Unit,
    onOpenDialog: (ComposerDialog) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "Joindre",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            if (capabilities.mediaSend || capabilities.gifs || capabilities.voiceNotes) {
                SectionHeader("Médias")
                FlowRow(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    if (capabilities.mediaSend) {
                        AttachSheetItem(
                            icon = Icons.Default.Image,
                            title = "Image",
                            subtitle = "Galerie photo",
                            onClick = {
                                onDismiss()
                                onPickMedia(ComposerPickKind.IMAGE)
                            },
                        )
                        AttachSheetItem(
                            icon = Icons.Default.AttachFile,
                            title = "Fichier",
                            subtitle = "Document / PDF",
                            onClick = {
                                onDismiss()
                                onPickMedia(ComposerPickKind.FILE)
                            },
                        )
                    }
                    if (capabilities.gifs) {
                        AttachSheetItem(
                            icon = Icons.Default.Gif,
                            title = "GIF",
                            subtitle = "Animation",
                            onClick = {
                                onDismiss()
                                onPickMedia(ComposerPickKind.GIF)
                            },
                        )
                    }
                    if (capabilities.voiceNotes) {
                        AttachSheetItem(
                            icon = Icons.Default.Mic,
                            title = "Vocal",
                            subtitle = "Note audio",
                            onClick = {
                                onDismiss()
                                onPickMedia(ComposerPickKind.VOICE)
                            },
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            if (capabilities.locationShare || capabilities.polls || capabilities.contactShare) {
                SectionHeader("Contenu enrichi")
                if (capabilities.locationShare) {
                    AttachSheetItem(
                        icon = Icons.Default.LocationOn,
                        title = "Lieu",
                        subtitle = "Coordonnées GPS",
                        onClick = {
                            onDismiss()
                            onOpenDialog(ComposerDialog.LOCATION)
                        },
                    )
                }
                if (capabilities.polls) {
                    AttachSheetItem(
                        icon = Icons.Default.Poll,
                        title = "Sondage",
                        subtitle = "Question + options",
                        onClick = {
                            onDismiss()
                            onOpenDialog(ComposerDialog.POLL)
                        },
                    )
                }
                if (capabilities.contactShare) {
                    AttachSheetItem(
                        icon = Icons.Default.Person,
                        title = "Contact",
                        subtitle = "Carte vCard",
                        onClick = {
                            onDismiss()
                            onOpenDialog(ComposerDialog.CONTACT)
                        },
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            if (capabilities.ephemeralMessages) {
                SectionHeader("Confidentialité")
                AttachSheetItem(
                    icon = Icons.Default.Timer,
                    title = "Message éphémère",
                    subtitle = "Disparaît après un délai",
                    onClick = {
                        onDismiss()
                        onOpenDialog(ComposerDialog.EPHEMERAL)
                    },
                )
            }

            if (!capabilities.mediaSend && !capabilities.gifs && !capabilities.voiceNotes &&
                !capabilities.locationShare && !capabilities.polls && !capabilities.contactShare &&
                !capabilities.ephemeralMessages
            ) {
                Text(
                    text = "Aucune pièce jointe disponible pour ce protocole.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
    )
}

@Composable
private fun AttachSheetItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

@Composable
internal fun LocationComposerDialog(
    onDismiss: () -> Unit,
    onSend: (Double, Double) -> Unit,
) {
    var lat by remember { mutableStateOf("48.8566") }
    var lon by remember { mutableStateOf("2.3522") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Partager un lieu") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = lat,
                    onValueChange = { lat = it },
                    label = { Text("Latitude") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = lon,
                    onValueChange = { lon = it },
                    label = { Text("Longitude") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val la = lat.toDoubleOrNull()
                    val lo = lon.toDoubleOrNull()
                    if (la != null && lo != null) onSend(la, lo)
                },
            ) { Text("Envoyer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
internal fun PollComposerDialog(
    onDismiss: () -> Unit,
    onSend: (String, List<String>) -> Unit,
) {
    var question by remember { mutableStateOf("") }
    var optionsText by remember { mutableStateOf("Oui\nNon") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sondage") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    label = { Text("Question") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = optionsText,
                    onValueChange = { optionsText = it },
                    label = { Text("Options (une par ligne)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val options = optionsText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    if (question.isNotBlank() && options.size >= 2) {
                        onSend(question.trim(), options)
                    }
                },
            ) { Text("Envoyer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
internal fun ContactComposerDialog(
    onDismiss: () -> Unit,
    onSend: (String, String, String?) -> Unit,
) {
    var first by remember { mutableStateOf("") }
    var last by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Partager un contact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it },
                    label = { Text("Prénom") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = last,
                    onValueChange = { last = it },
                    label = { Text("Nom") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Téléphone") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (first.isNotBlank()) {
                        onSend(first.trim(), last.trim(), phone.trim().ifBlank { null })
                    }
                },
            ) { Text("Envoyer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
internal fun EphemeralComposerDialog(
    initialBody: String,
    onDismiss: () -> Unit,
    onSend: (String, Int) -> Unit,
) {
    var body by remember { mutableStateOf(initialBody) }
    var seconds by remember { mutableStateOf("60") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message éphémère") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Message") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                )
                OutlinedTextField(
                    value = seconds,
                    onValueChange = { seconds = it },
                    label = { Text("Expire (secondes)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 60, 300, 3600).forEach { sec ->
                        TextButton(onClick = { seconds = sec.toString() }) {
                            Text(
                                when (sec) {
                                    30 -> "30s"
                                    60 -> "1 min"
                                    300 -> "5 min"
                                    else -> "1 h"
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val sec = seconds.toIntOrNull()
                    if (body.isNotBlank() && sec != null && sec > 0) onSend(body, sec)
                },
            ) { Text("Envoyer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
