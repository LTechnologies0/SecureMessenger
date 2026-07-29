package ltechnologies.onionphone.securemessenger.core.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.inject.Inject
import javax.inject.Singleton

sealed class AppLockAuthResult {
    data object Success : AppLockAuthResult()
    data class Failure(val message: String) : AppLockAuthResult()
    data object Cancelled : AppLockAuthResult()
}

/**
 * System lock prompt: BIOMETRIC_STRONG | DEVICE_CREDENTIAL (PIN / schéma / password).
 * Same pattern as OnionVPN — works in Android private profiles when a screen lock exists.
 */
@Singleton
class AppLockAuthenticator @Inject constructor() {

    fun authenticate(
        activity: FragmentActivity,
        onResult: (AppLockAuthResult) -> Unit,
    ) {
        val manager = BiometricManager.from(activity)
        val authenticators =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        when (manager.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Unit
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            -> {
                onResult(AppLockAuthResult.Failure("Configurez un verrouillage d'écran Android d'abord"))
                return
            }
            // Private profiles / GrapheneOS may report other statuses; still show the prompt.
            else -> Unit
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onResult(AppLockAuthResult.Success)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    ) {
                        onResult(AppLockAuthResult.Cancelled)
                    } else {
                        onResult(AppLockAuthResult.Failure(errString.toString()))
                    }
                }

                override fun onAuthenticationFailed() {
                    // Keep prompt open; user can retry or fall back to PIN.
                }
            },
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Déverrouiller SecureMessenger")
            .setSubtitle("Empreinte, visage ou code PIN de l'appareil")
            .setAllowedAuthenticators(authenticators)
            .build()

        prompt.authenticate(info)
    }
}
