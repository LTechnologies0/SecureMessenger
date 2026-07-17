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
 * Prompts the user with the system lock (PIN / schéma / biométrie).
 * Successful auth satisfies the Keystore user-auth requirement for [AuthenticatedCrypto.AUTH_VALIDITY_SECONDS].
 */
@Singleton
class AppLockAuthenticator @Inject constructor() {

    fun canAuthenticate(activity: FragmentActivity): Int {
        val manager = BiometricManager.from(activity)
        return manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        )
    }

    fun authenticate(
        activity: FragmentActivity,
        onResult: (AppLockAuthResult) -> Unit,
    ) {
        when (canAuthenticate(activity)) {
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            -> {
                onResult(AppLockAuthResult.Failure("Verrouillage système requis"))
                return
            }
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
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        onResult(AppLockAuthResult.Cancelled)
                    } else {
                        onResult(AppLockAuthResult.Failure(errString.toString()))
                    }
                }

                override fun onAuthenticationFailed() {
                    onResult(AppLockAuthResult.Failure("Authentification échouée"))
                }
            },
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Déverrouiller SecureMessenger")
            .setSubtitle("Utilisez le verrouillage de votre appareil")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()

        prompt.authenticate(info)
    }
}
