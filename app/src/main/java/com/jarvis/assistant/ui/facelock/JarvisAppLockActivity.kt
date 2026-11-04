package com.jarvis.assistant.ui.facelock

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.jarvis.assistant.databinding.ActivityJarvisAppLockBinding

/**
 * JARVIS Protocol 7: Biometric & FaceLock App Guardian.
 *
 * Two modes:
 *  - LOCK: started by [AppLockEnforcer] (via JarvisAccessibilityService) with [EXTRA_LOCKED_PACKAGE]
 *    when a locked app comes to the foreground. Success unlocks that app until the user leaves it
 *    and returns to it; cancel sends the user HOME.
 *  - MANAGE: started from the FaceLock entry points (no locked package / Jarvis' own package).
 *    After verification it opens the [AppLockPicker] so the locked-app list can be changed.
 */
class JarvisAppLockActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "JarvisAppLock"
        const val EXTRA_PACKAGE_NAME = "pkg"
        const val EXTRA_APP_LABEL = "label"
        /** Package being guarded; present only when launched by the app-lock enforcer. */
        const val EXTRA_LOCKED_PACKAGE = "locked_package"

        fun isPackageUnlocked(pkg: String): Boolean = AppLockStore.isSessionUnlocked(pkg)

        fun markPackageUnlocked(pkg: String) {
            AppLockStore.markSessionUnlocked(pkg)
        }
    }

    private lateinit var binding: ActivityJarvisAppLockBinding
    /** Package to unlock (LOCK mode); null = MANAGE mode. */
    private var targetPackage: String? = null
    private var appLabel: String = ""
    private var authInProgress = false
    private var picker: AppLockPicker? = null

    private val isManageMode: Boolean get() = targetPackage == null

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityJarvisAppLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnAuthenticate.setOnClickListener {
            launchBiometricVerification()
        }

        binding.btnCancelHome.setOnClickListener {
            exitToHomeScreen()
        }

        readIntent(intent)

        // Automatically prompt on launch: Maya Face Lock first (if set up), then biometrics
        binding.root.post {
            launchFaceThenBiometric()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask: the enforcer re-uses this instance (another locked app, or the same one again).
        setIntent(intent)
        picker?.dismiss()
        picker = null
        readIntent(intent)
        if (!authInProgress) {
            binding.root.post { launchFaceThenBiometric() }
        }
    }

    override fun onResume() {
        super.onResume()
        picker?.refreshAccessibilityRow()
    }

    private fun readIntent(intent: Intent?) {
        val locked = intent?.getStringExtra(EXTRA_LOCKED_PACKAGE)
            ?: intent?.getStringExtra(EXTRA_PACKAGE_NAME)
        targetPackage = locked?.takeIf { it.isNotBlank() && it != packageName }
        appLabel = intent?.getStringExtra(EXTRA_APP_LABEL).orEmpty()

        binding.tvAppLockTitle.text = when {
            isManageMode -> "Verify your identity to manage App Lock"
            appLabel.isNotBlank() -> "Authentication required to access $appLabel"
            else -> "Authentication required to access this application"
        }
        binding.btnCancelHome.text = if (isManageMode) "Close" else "Exit to Home"
    }

    /**
     * Maya Face Lock (com.jarvis.assistant.maya.guardian.FaceLockController): when the owner's face
     * is enrolled and face lock is on, look for it for up to 5 s; otherwise / on failure fall back
     * to the BiometricPrompt below. The "Authenticate" button always goes straight to biometrics.
     */
    private fun launchFaceThenBiometric() {
        if (authInProgress || isFinishing) return
        val usable = try {
            com.jarvis.assistant.maya.guardian.FaceLockController.isFaceUsable(this)
        } catch (e: Throwable) { false }
        if (!usable) {
            launchBiometricVerification()
            return
        }
        authInProgress = true
        binding.tvAppLockTitle.text = "Looking for your face…"
        com.jarvis.assistant.maya.guardian.FaceLockController.tryFace(this) { result ->
            authInProgress = false
            if (isFinishing) return@tryFace
            if (result.success) {
                Log.i(TAG, "Face Lock succeeded (target=${targetPackage ?: "manage"})")
                onVerified()
            } else {
                readIntent(intent)
                launchBiometricVerification()
            }
        }
    }

    private fun allowedAuthenticators(): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
        }

    private fun launchBiometricVerification() {
        if (authInProgress || isFinishing) return

        val canAuth = BiometricManager.from(this).canAuthenticate(allowedAuthenticators())
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            handleNoScreenLock(canAuth)
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                authInProgress = false
                Log.i(TAG, "Authentication succeeded (target=${targetPackage ?: "manage"})")
                onVerified()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                authInProgress = false
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
            .setSubtitle(
                when {
                    isManageMode -> "Manage App Lock"
                    appLabel.isNotBlank() -> "Unlock $appLabel"
                    else -> "Security Verification"
                }
            )
            .setDescription("Verify your identity with Face, Fingerprint, or Device PIN")
            .setAllowedAuthenticators(allowedAuthenticators())

        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            promptInfoBuilder.setNegativeButtonText("Cancel")
        }

        try {
            authInProgress = true
            prompt.authenticate(promptInfoBuilder.build())
        } catch (e: Exception) {
            authInProgress = false
            Log.e(TAG, "Error launching biometric prompt: ${e.message}", e)
            Toast.makeText(this, "Could not start verification: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onVerified() {
        val target = targetPackage
        if (target == null) {
            showPicker()
            return
        }
        AppLockStore.markSessionUnlocked(target)
        returnToUnlockedApp(target)
    }

    /**
     * No fingerprint/face/PIN is set up on the phone. MANAGE: explain and close.
     * LOCK: fail open (an unconfigurable lock would trap the user out of their own app).
     */
    private fun handleNoScreenLock(code: Int) {
        Log.w(TAG, "Device cannot authenticate (code=$code)")
        val target = targetPackage
        if (target == null) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Screen lock needed")
                .setMessage("App Lock uses your phone's Face / Fingerprint / PIN. Set up a screen lock in phone Settings > Security, then try again.")
                .setPositiveButton("OK") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
        } else {
            Toast.makeText(this, "App Lock needs a phone screen lock (PIN / fingerprint) - opening unlocked.", Toast.LENGTH_LONG).show()
            AppLockStore.markSessionUnlocked(target)
            returnToUnlockedApp(target)
        }
    }

    private fun showPicker() {
        if (picker?.isShowing == true) return
        picker = AppLockPicker(this) {
            picker = null
            finish()
        }.also { it.show() }
    }

    /**
     * Leaves the lock screen so the user lands back in [pkg]. When this activity lives in its own
     * task (manifest taskAffinity="") finishing is enough; otherwise it shares Jarvis' task, so the
     * guarded app's task is brought back to the front explicitly.
     */
    private fun returnToUnlockedApp(pkg: String) {
        val ownTask = try {
            val info = packageManager.getActivityInfo(componentName, PackageManager.GET_META_DATA)
            info.taskAffinity != packageName
        } catch (_: Exception) { false }

        if (ownTask) {
            finishAndRemoveTask()
            return
        }
        finish()
        try {
            packageManager.getLaunchIntentForPackage(pkg)?.let { launch ->
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not bring $pkg back to front: ${e.message}")
        }
    }

    private fun exitToHomeScreen() {
        if (!isManageMode) {
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

    override fun onDestroy() {
        picker?.dismiss()
        picker = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        exitToHomeScreen()
    }
}
