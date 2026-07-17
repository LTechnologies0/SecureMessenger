package ltechnologies.onionphone.securemessenger.core.security

import android.app.KeyguardManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

enum class AppLockState {
    /** Device has no PIN/pattern/biometric — app cannot store secrets. */
    DEVICE_INSECURE,
    /** Waiting for system lock (PIN / empreinte / visage). */
    LOCKED,
    /** User authenticated; encrypted stores are accessible. */
    UNLOCKED,
}

/**
 * Mandatory app lock tied to the Android device credential.
 * Sensitive data stays encrypted until [markUnlocked] after a successful system-auth prompt.
 */
@Singleton
class AppLockManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<AppLockState> = _state.asStateFlow()

    val isDeviceSecure: Boolean
        get() = keyguard?.isDeviceSecure == true

    val isUnlocked: Boolean
        get() = _state.value == AppLockState.UNLOCKED

    private fun initialState(): AppLockState =
        if (isDeviceSecure) AppLockState.LOCKED else AppLockState.DEVICE_INSECURE

    /** Blocks until the user has unlocked the app at least once in this process. */
    suspend fun awaitUnlocked() {
        state.first { it == AppLockState.UNLOCKED }
    }

    /** Call after BiometricPrompt / device-credential confirmation succeeds. */
    fun markUnlocked() {
        if (!isDeviceSecure) return
        _state.value = AppLockState.UNLOCKED
    }

    /** Re-lock when the app leaves the foreground (or on demand). */
    fun lock() {
        if (!isDeviceSecure) {
            _state.value = AppLockState.DEVICE_INSECURE
            return
        }
        _state.value = AppLockState.LOCKED
    }

    fun assertUnlocked() {
        check(isUnlocked) { "App is locked — authenticate with device lock first" }
    }
}
