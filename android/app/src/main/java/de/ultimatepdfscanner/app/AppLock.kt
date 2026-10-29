package de.ultimatepdfscanner.app

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Biometric app lock: fingerprint / face unlock, with the device PIN/pattern as fallback.
 * The app locks again after it has been in the background for [RELOCK_MS], so returning from
 * the share sheet or the photo picker does not ask again.
 */
object AppLock {
    const val RELOCK_MS = 60_000L

    private val authenticators =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_WEAK or DEVICE_CREDENTIAL else BIOMETRIC_WEAK

    /** null if the device can authenticate, otherwise a reason to show the user. */
    fun unavailableReason(activity: FragmentActivity): String? = when (BiometricManager.from(activity).canAuthenticate(authenticators)) {
        BiometricManager.BIOMETRIC_SUCCESS -> null
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> tr("Bitte zuerst Fingerabdruck, Gesichtsentsperrung oder eine Displaysperre einrichten.")
        else -> tr("Dieses Gerät unterstützt keine biometrische Entsperrung.")
    }

    fun prompt(activity: FragmentActivity, onSuccess: () -> Unit) {
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(tr("Ultimate PDF Scanner entsperren"))
            .setSubtitle(tr("Deine Scans sind geschützt"))
            .setAllowedAuthenticators(authenticators)
            .apply { if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) setNegativeButtonText(tr("Abbrechen")) }
            .build()
        prompt.authenticate(info)
    }
}
