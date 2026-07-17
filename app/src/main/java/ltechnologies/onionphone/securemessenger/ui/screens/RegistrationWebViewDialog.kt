package ltechnologies.onionphone.securemessenger.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
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
import ltechnologies.onionphone.securemessenger.core.model.MatrixSsoRedirect

/**
 * Full-screen WebView used for Matrix UIA / SSO stages. Traffic is force-routed through Tor via
 * [ProxyController]. When [onLoginToken] is set, SSO redirects to [MatrixSsoRedirect.URI]
 * are intercepted and the loginToken is returned automatically.
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
    onLoginToken: ((String) -> Unit)? = null,
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
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val target = request?.url?.toString() ?: return false
                                    if (onLoginToken != null && MatrixSsoRedirect.matchesRedirect(target)) {
                                        val token = MatrixSsoRedirect.extractLoginToken(target)
                                        if (!token.isNullOrBlank()) {
                                            onLoginToken(token)
                                            return true
                                        }
                                    }
                                    return false
                                }
                            }
                            loadUrl(url)
                        }
                    },
                )
            }
            if (onLoginToken == null) {
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
}
