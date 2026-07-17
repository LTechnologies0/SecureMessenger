package ltechnologies.onionphone.securemessenger.ui.screens

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import ltechnologies.onionphone.securemessenger.core.security.AppLockManager

@HiltViewModel
class SettingsLockViewModel @Inject constructor(
    val appLockManager: AppLockManager,
) : ViewModel()
