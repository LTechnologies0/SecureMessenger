package ltechnologies.onionphone.securemessenger.ui.applock

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import ltechnologies.onionphone.securemessenger.core.security.AppLockAuthResult
import ltechnologies.onionphone.securemessenger.core.security.AppLockAuthenticator
import ltechnologies.onionphone.securemessenger.core.security.AppLockManager
import ltechnologies.onionphone.securemessenger.core.security.AppLockState

@HiltViewModel
class AppLockViewModel @Inject constructor(
    private val appLockManager: AppLockManager,
    private val authenticator: AppLockAuthenticator,
) : ViewModel() {
    val lockState: StateFlow<AppLockState> = appLockManager.state

    fun authenticate(activity: FragmentActivity, onResult: (AppLockAuthResult) -> Unit) {
        authenticator.authenticate(activity) { result ->
            if (result is AppLockAuthResult.Success) {
                appLockManager.markUnlocked()
            }
            onResult(result)
        }
    }
}
