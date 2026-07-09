package ltechnologies.onionphone.securemessenger.ui.navigation

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import ltechnologies.onionphone.securemessenger.ui.MainViewModel
import ltechnologies.onionphone.securemessenger.ui.screens.MessengerShellScreen

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SecureMessengerNavHost(
    snackbarHostState: SnackbarHostState,
    viewModel: MainViewModel = hiltViewModel(),
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        MessengerShellScreen(
            modifier = Modifier.padding(padding),
            viewModel = viewModel,
        )
    }
}
