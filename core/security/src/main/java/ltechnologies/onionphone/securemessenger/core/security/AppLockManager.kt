package ltechnologies.onionphone.securemessenger.core.security

import android.app.KeyguardManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    private val lockScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pendingLockJob: Job? = null

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

    /**
     * Call after BiometricPrompt success, or after the user accepts the insecure-device warning
     * (same escape hatch as OnionVPN — needed when Keyguard reports insecure in some profiles).
     */
    fun markUnlocked() {
        _state.value = AppLockState.UNLOCKED
    }

    /** Re-lock immediately (or after [INACTIVITY_LOCK_MS] via [scheduleLockAfterInactivity]). */
    fun lock() {
        pendingLockJob?.cancel()
        pendingLockJob = null
        if (!isDeviceSecure) {
            _state.value = AppLockState.DEVICE_INSECURE
            return
        }
        _state.value = AppLockState.LOCKED
    }

    /**
     * Process went to background. Do not lock on Activity.onStop (SAF, biometric,
     * in-app Activity hops). Wait [INACTIVITY_LOCK_MS] then lock if still background.
     */
    fun scheduleLockAfterInactivity(delayMs: Long = INACTIVITY_LOCK_MS) {
        pendingLockJob?.cancel()
        pendingLockJob = lockScope.launch {
            delay(delayMs)
            lock()
        }
    }

    fun cancelScheduledLock() {
        pendingLockJob?.cancel()
        pendingLockJob = null
    }

    companion object {
        const val INACTIVITY_LOCK_MS = 60_000L
    }

    fun assertUnlocked() {
        check(isUnlocked) { "App is locked — authenticate with device lock first" }
    }
}
