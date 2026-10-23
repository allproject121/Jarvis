package com.jarvis.assistant.ui.facelock

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.jarvis.assistant.databinding.ActivityJarvisAppLockBinding
import java.util.concurrent.ConcurrentHashMap

/**
 * JARVIS Protocol 7: Biometric & FaceLock App Guardian.
 *
 * Protects selected applications with hardware-backed Face Unlock, Fingerprint, or Device PIN.
 */
class JarvisAppLockActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "JarvisAppLock"
        const val EXTRA_PACKAGE_NAME = "pkg"
        const val EXTRA_APP_LABEL = "label"

        // Cache of temporarily unlocked packages (e.g. for 30 seconds after successful verification)
        private val temporarilyUnlocked = ConcurrentHashMap<String, Long>()

        fun isPackageUnlocked(pkg: String): Boolean {
            val unlockTime = temporarilyUnlocked[pkg] ?: return false
            if (System.currentTimeMillis() - unlockTime < 30_000L) {
                return true
            }
            temporarilyUnlocked.remove(pkg)
            return false
        }

        fun markPackageUnlocked(pkg: String) {
            temporarilyUnlocked[pkg] = System.currentTimeMillis()
        }
    }

    private lateinit var binding: ActivityJarvisAppLockBinding
    private var targetPackage: String = ""
    private var appLabel: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityJarvisAppLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        targetPackage = intent?.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        appLabel = intent?.getStringExtra(EXTRA_APP_LABEL).orEmpty()

        if (targetPackage.isNotBlank()) {
            binding.tvAppLockTitle.text = if (appLabel.isNotBlank()) {
                "Authentication required to access $appLabel"
            } else {
                "Authentication required to access this application"
            }
        }

        binding.btnAuthenticate.setOnClickListener {
            launchBiometricVerification()
        }

        binding.btnCancelHome.setOnClickListener {
            exitToHomeScreen()
        }

        // Automatically prompt for biometrics on launch
        binding.root.post {
            launchBiometricVerification()
        }
    }

    private fun launchBiometricVerification() {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                Log.i(TAG, "Authentication succeeded for package: $targetPackage")
                if (targetPackage.isNotBlank()) {
                    markPackageUnlocked(targetPackage)
                }
                finish()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Log.w(TAG, "Biometric authentication error ($errorCode): $errString")
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED) {
                    exitToHomeScreen()
                } else {
                    Toast.makeText(this@JarvisAppLockActivity, "Verification failed: $errString", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Log.w(TAG, "Biometric authentication failed attempt")
            }
        })

        val promptInfoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("JARVIS PROTOCOL 7")
            .setSubtitle(if (appLabel.isNotBlank()) "Unlock $appLabel" else "Security Verification")
            .setDescription("Verify your identity with Face, Fingerprint, or Device PIN")

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            promptInfoBuilder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            promptInfoBuilder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
            )
            promptInfoBuilder.setNegativeButtonText("Cancel")
        }

        val promptInfo = promptInfoBuilder.build()

        try {
            prompt.authenticate(promptInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching biometric prompt: ${e.message}", e)
            Toast.makeText(this, "Biometric authentication initialized", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exitToHomeScreen() {
        if (targetPackage.isNotBlank() && targetPackage != packageName) {
            try {
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(homeIntent)
            } catch (ignored: Exception) {}
        }
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        exitToHomeScreen()
    }
}

