package ltechnologies.onionphone.securemessenger.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor
import androidx.webkit.ProxyConfig as WebViewProxyConfig

/**
 * Full-screen WebView used for Matrix UIA fallback stages (captcha/email/terms) that have no
 * native UI. Traffic is force-routed through the app's SOCKS/Tor proxy via [ProxyController] —
 * there is no OS-level VPN killswitch in this app, so a plain WebView would leak the real IP.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RegistrationWebViewDialog(
    url: String,
    instructions: String?,
    socksHost: String,
    socksPort: Int,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    var proxyReady by remember { mutableStateOf(false) }
    var proxyError by remember { mutableStateOf<String?>(null) }
    val executor = remember { Executor { command -> command.run() } }

    DisposableEffect(socksHost, socksPort) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            proxyError = "Ce téléphone ne supporte pas le proxy WebView — étape impossible sans risquer " +
                "de contourner Tor."
        } else {
            val proxyConfig = WebViewProxyConfig.Builder()
                .addProxyRule("socks5://$socksHost:$socksPort")
                .build()
            ProxyController.getInstance().setProxyOverride(proxyConfig, executor) {
                proxyReady = true
            }
        }
        onDispose {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                runCatching { ProxyController.getInstance().clearProxyOverride(executor) {} }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Étape requise par le serveur") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Fermer")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            instructions?.let { Text(it, modifier = Modifier.padding(16.dp)) }
            proxyError?.let { Text(it, modifier = Modifier.padding(16.dp)) }
            if (proxyReady) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            webViewClient = WebViewClient()
                            loadUrl(url)
                        }
                    },
                )
            }
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                enabled = proxyReady,
            ) {
                Text("Continuer")
            }
        }
    }
}
