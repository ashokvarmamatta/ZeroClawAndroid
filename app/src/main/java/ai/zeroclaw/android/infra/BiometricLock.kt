package ai.zeroclaw.android.infra

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.CompletableDeferred

/**
 * BiometricLock — Phase 128: Fingerprint/face auth to protect the app.
 *
 * Wraps Android BiometricPrompt API into a simple coroutine-friendly interface.
 * Supports:
 * - Fingerprint, face recognition, iris (device-dependent)
 * - Device credential fallback (PIN, pattern, password)
 * - Availability check before showing prompt
 * - Suspend function: await user authentication result
 */
object BiometricLock {

    enum class AuthResult {
        SUCCESS,
        FAILED,         // Biometric not recognised
        ERROR,          // System error or user cancelled
        NOT_AVAILABLE   // No biometric enrolled / hardware missing
    }

    data class AuthConfig(
        val title: String = "ZeroClaw Authentication",
        val subtitle: String = "Verify your identity to continue",
        val negativeButtonText: String = "Cancel",
        val allowDeviceCredential: Boolean = true
    )

    /**
     * Check if biometric authentication is available on this device.
     */
    fun isAvailable(context: Context): Boolean {
        val manager = BiometricManager.from(context)
        val authenticators = BIOMETRIC_WEAK or (if (true) DEVICE_CREDENTIAL else 0)
        return manager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Check capability with detail (for settings UI).
     */
    fun getCapabilityStatus(context: Context): String {
        val manager = BiometricManager.from(context)
        return when (manager.canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)) {
            BiometricManager.BIOMETRIC_SUCCESS         -> "Available"
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "No hardware"
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Hardware unavailable"
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED  -> "No biometrics enrolled"
            else -> "Unknown"
        }
    }

    /**
     * Show biometric prompt and suspend until the user authenticates (or fails/cancels).
     *
     * Must be called from a coroutine on the main thread with a FragmentActivity.
     */
    suspend fun authenticate(
        activity: FragmentActivity,
        config: AuthConfig = AuthConfig()
    ): AuthResult {
        if (!isAvailable(activity)) {
            ZeroClawService.log("BIOMETRIC: not available — ${getCapabilityStatus(activity)}")
            return AuthResult.NOT_AVAILABLE
        }

        val deferred = CompletableDeferred<AuthResult>()

        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                ZeroClawService.log("BIOMETRIC: authentication succeeded")
                deferred.complete(AuthResult.SUCCESS)
            }

            override fun onAuthenticationFailed() {
                // Not yet cancelled — user can retry
                ZeroClawService.log("BIOMETRIC: attempt failed (wrong biometric)")
                // Don't complete — let the user try again or cancel
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                ZeroClawService.log("BIOMETRIC: error $errorCode — $errString")
                val result = if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_USER_CANCELED) {
                    AuthResult.ERROR
                } else {
                    AuthResult.ERROR
                }
                if (!deferred.isCompleted) deferred.complete(result)
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)

        val promptInfo = buildPromptInfo(config)
        prompt.authenticate(promptInfo)

        return deferred.await()
    }

    private fun buildPromptInfo(config: AuthConfig): BiometricPrompt.PromptInfo {
        return if (config.allowDeviceCredential) {
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(config.title)
                .setSubtitle(config.subtitle)
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                .build()
        } else {
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(config.title)
                .setSubtitle(config.subtitle)
                .setNegativeButtonText(config.negativeButtonText)
                .setAllowedAuthenticators(BIOMETRIC_WEAK)
                .build()
        }
    }

    /**
     * Quick helper: authenticate and run a block only on success.
     * Returns true if the block ran, false otherwise.
     */
    suspend fun <T> runIfAuthenticated(
        activity: FragmentActivity,
        config: AuthConfig = AuthConfig(),
        block: suspend () -> T
    ): T? {
        return if (authenticate(activity, config) == AuthResult.SUCCESS) {
            block()
        } else null
    }
}
