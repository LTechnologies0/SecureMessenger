package ltechnologies.onionphone.securemessenger.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ltechnologies.onionphone.securemessenger.core.model.FeatureFlags

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onOpenProxy: () -> Unit = {},
    onClose: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                onClose?.let { close ->
                    IconButton(onClick = close) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Fermer")
                    }
                }
            },
        )
        Text("SecureMessenger 1.0.0-alpha")
        Text("Enabled protocols: ${FeatureFlags.enabled.joinToString { it.name }}")
        Text("Discord and Signal (Molly) are reserved for a future release.")
        Button(
            onClick = onOpenProxy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        ) {
            Text("Proxy / Tor")
        }
    }
}
