package ltechnologies.onionphone.securemessenger.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.ui.MainViewModel
import ltechnologies.onionphone.securemessenger.ui.components.ProtocolAvatar
import ltechnologies.onionphone.securemessenger.ui.components.protocolDisplayName
import ltechnologies.onionphone.securemessenger.ui.components.protocolShortPrefix

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContactsScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel,
    accountId: String,
    protocol: ProtocolId,
    onBack: () -> Unit,
    onStarted: (conversationId: String, title: String, protocol: ProtocolId) -> Unit,
) {
    val contactsFlow = remember(accountId) { viewModel.contactsFor(accountId) }
    val contacts by contactsFlow.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    fun refresh() {
        refreshing = true
        status = null
        viewModel.refreshContacts(accountId, protocol) { result ->
            refreshing = false
            status = result.fold(
                onSuccess = { count -> "$count contact(s) synchronisé(s)" },
                onFailure = { it.message ?: "Actualisation échouée" },
            )
        }
    }

    LaunchedEffect(status) {
        val msg = status ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
    }

    val filtered = remember(contacts, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            contacts
        } else {
            contacts.filter { c ->
                c.displayName.contains(q, ignoreCase = true) ||
                    (c.handle?.contains(q, ignoreCase = true) == true) ||
                    (c.phone?.contains(q, ignoreCase = true) == true) ||
                    c.remoteId.contains(q, ignoreCase = true)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Contacts")
                        Text(
                            protocolDisplayName(protocol),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = { refresh() }, enabled = !refreshing) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualiser")
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(Modifier.fillMaxSize()) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Rechercher un contact") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null)
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0f),
                        unfocusedIndicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0f),
                    ),
                )

                when {
                    refreshing && contacts.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Text(
                                    "Actualisation…",
                                    modifier = Modifier.padding(top = 12.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    filtered.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(24.dp),
                            ) {
                                Icon(
                                    Icons.Default.PersonSearch,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = when {
                                        query.isNotBlank() -> "Aucun contact pour « $query »"
                                        status?.contains("échou", ignoreCase = true) == true ->
                                            status ?: "Erreur de synchronisation"
                                        else -> "Aucun contact. Tire pour actualiser."
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (query.isBlank()) {
                                    OutlinedButton(onClick = { refresh() }, enabled = !refreshing) {
                                        Text("Actualiser")
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            items(filtered, key = { it.id }) { contact ->
                                ListItem(
                                    headlineContent = { Text(contact.displayName) },
                                    supportingContent = {
                                        Text(
                                            listOfNotNull(
                                                contact.handle,
                                                contact.phone,
                                                contact.remoteId,
                                                protocolShortPrefix(contact.protocol),
                                            ).distinct().joinToString(" · "),
                                        )
                                    },
                                    leadingContent = {
                                        ProtocolAvatar(protocol = contact.protocol)
                                    },
                                    modifier = Modifier.clickable {
                                        status = null
                                        viewModel.startConversation(
                                            protocol = protocol,
                                            remoteId = contact.remoteId,
                                            message = null,
                                            accountId = accountId,
                                        ) { convId ->
                                            if (convId != null) {
                                                onStarted(convId, contact.displayName, protocol)
                                            } else {
                                                status = "Impossible d'ouvrir la conversation"
                                            }
                                        }
                                    },
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 72.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
