package com.jarvis.assistant.ui.supercore

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.assistant.JarvisVoiceInteractionService
import com.jarvis.assistant.automation.notification.JarvisNotificationListener
import com.jarvis.assistant.core.JarvisDrivingModeManager
import com.jarvis.assistant.databinding.ActivitySuperCoreBinding
import com.jarvis.assistant.ui.settings.ApiConnectionActivity
import com.jarvis.assistant.util.HudSoundManager
import com.jarvis.assistant.util.ThemeManager

/**
 * Jarvis SuperCore & System Control Activity.
 * Provides unified, organized control over:
 *  1. Android Default Digital Assistant (VoiceInteraction)
 *  2. WhatsApp & Telegram Voice Reader / Direct Reply
 *  3. Driving Mode & Call Sentinel
 *  4. Local Host AI (Ollama), Mem0, and MUSE Self-Learning
 */
class SuperCoreActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySuperCoreBinding
    private val preferences by lazy { JarvisApp.instance.preferences }
    private var appliedThemeKey: String = ""
    private var appliedCustomHex: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        appliedThemeKey = preferences.appTheme
        appliedCustomHex = preferences.customThemeColor
        super.onCreate(savedInstanceState)
        binding = ActivitySuperCoreBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.superCoreRoot)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.superCoreRoot) { _, insets ->
            val statusBarInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density
            val topInset = statusBarInsets.top
            val safeTopPadding = if (topInset > 0) topInset + (6 * density).toInt() else (14 * density).toInt()
            binding.topBar.setPadding(
                binding.topBar.paddingLeft,
                safeTopPadding,
                binding.topBar.paddingRight,
                binding.topBar.paddingBottom
            )
            insets
        }

        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        if (appliedThemeKey != preferences.appTheme || appliedCustomHex != preferences.customThemeColor) {
            appliedThemeKey = preferences.appTheme
            appliedCustomHex = preferences.customThemeColor
            recreate()
            return
        }
        loadPreferencesAndStatus()
    }

    private fun loadPreferencesAndStatus() {
        // 1. Check Default Assistant Status
        val isDefaultAssist = isJarvisDefaultAssistant()
        if (isDefaultAssist) {
            binding.tvDefaultAssistantBadge.text = "ACTIVE"
            binding.tvDefaultAssistantBadge.setTextColor(Color.parseColor("#10B981"))
            binding.tvDefaultAssistantBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge_green)
            binding.btnSetDefaultAssistant.text = "JARVIS IS DEFAULT ASSISTANT ✓"
            binding.btnSetDefaultAssistant.isEnabled = false
        } else {
            binding.tvDefaultAssistantBadge.text = "NOT SET"
            binding.tvDefaultAssistantBadge.setTextColor(ContextCompat.getColor(this, R.color.primary_cyan))
            binding.tvDefaultAssistantBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge)
            binding.btnSetDefaultAssistant.text = "SET JARVIS AS DEFAULT ASSISTANT"
            binding.btnSetDefaultAssistant.isEnabled = true
        }

        // 2. Check Notification Listener Status
        val isNotifEnabled = JarvisNotificationListener.isNotificationServiceEnabled(this)
        if (isNotifEnabled) {
            binding.tvNotificationListenerBadge.text = "ACTIVE"
            binding.tvNotificationListenerBadge.setTextColor(Color.parseColor("#10B981"))
            binding.tvNotificationListenerBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge_green)
            binding.btnGrantNotificationAccess.text = "NOTIFICATION ACCESS GRANTED ✓"
            binding.btnGrantNotificationAccess.isEnabled = false
        } else {
            binding.tvNotificationListenerBadge.text = "PERMISSION NEEDED"
            binding.tvNotificationListenerBadge.setTextColor(Color.parseColor("#F59E0B"))
            binding.tvNotificationListenerBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge)
            binding.btnGrantNotificationAccess.text = "GRANT NOTIFICATION ACCESS"
            binding.btnGrantNotificationAccess.isEnabled = true
        }

        // 3. Driving Mode Status
        val isDriving = JarvisDrivingModeManager.getInstance(this).isDrivingMode.value
        binding.tvDrivingModeBadge.text = if (isDriving) "DRIVING ACTIVE" else "STANDBY"
        binding.tvDrivingModeBadge.setTextColor(if (isDriving) Color.parseColor("#10B981") else Color.parseColor("#F59E0B"))

        // Switches
        binding.switchScreenIntelligence.isChecked = preferences.isDefaultAssistantScreenCaptureEnabled
        binding.switchWhatsAppReader.isChecked = preferences.isWhatsAppReaderEnabled
        binding.switchTelegramReader.isChecked = preferences.isTelegramReaderEnabled
        binding.switchAutoReplyConfirm.isChecked = preferences.isMessageAutoReplyConfirmEnabled
        binding.switchAutoCarAudio.isChecked = preferences.isDrivingModeAutoCarEnabled
        binding.switchCallerIdAnnouncement.isChecked = preferences.isCallerIdAnnouncementEnabled
        binding.switchMem0Memory.isChecked = preferences.isMem0MemoryEnabled
        binding.switchMuseExperience.isChecked = preferences.isMuseExperienceEnabled
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            HudSoundManager.playTapSound()
            finish()
        }

        binding.btnSetDefaultAssistant.setOnClickListener {
            HudSoundManager.playTapSound()
            openDefaultAssistantSettings()
        }

        binding.btnGrantNotificationAccess.setOnClickListener {
            HudSoundManager.playTapSound()
            openNotificationListenerSettings()
        }

        binding.btnConfigureOllama.setOnClickListener {
            HudSoundManager.playTapSound()
            startActivity(Intent(this, ApiConnectionActivity::class.java))
        }

        // Switch listeners
        binding.switchScreenIntelligence.setOnCheckedChangeListener { _, isChecked ->
            preferences.isDefaultAssistantScreenCaptureEnabled = isChecked
            HudSoundManager.playTapSound()
        }

        binding.switchWhatsAppReader.setOnCheckedChangeListener { _, isChecked ->
            preferences.isWhatsAppReaderEnabled = isChecked
            HudSoundManager.playTapSound()
        }

        binding.switchTelegramReader.setOnCheckedChangeListener { _, isChecked ->
            preferences.isTelegramReaderEnabled = isChecked
            HudSoundManager.playTapSound()
        }

        binding.switchAutoReplyConfirm.setOnCheckedChangeListener { _, isChecked ->
            preferences.isMessageAutoReplyConfirmEnabled = isChecked
            HudSoundManager.playTapSound()
        }

        binding.switchAutoCarAudio.setOnCheckedChangeListener { _, isChecked ->
            preferences.isDrivingModeAutoCarEnabled = isChecked
            HudSoundManager.playTapSound()
        }

        binding.switchCallerIdAnnouncement.setOnCheckedChangeListener { _, isChecked ->
            preferences.isCallerIdAnnouncementEnabled = isChecked
            HudSoundManager.playTapSound()
        }

        binding.switchMem0Memory.setOnCheckedChangeListener { _, isChecked ->
            preferences.isMem0MemoryEnabled = isChecked
            HudSoundManager.playTapSound()
        }

        binding.switchMuseExperience.setOnCheckedChangeListener { _, isChecked ->
            preferences.isMuseExperienceEnabled = isChecked
            HudSoundManager.playTapSound()
        }
    }

    private fun isJarvisDefaultAssistant(): Boolean {
        return try {
            val setting = Settings.Secure.getString(contentResolver, "assistant")
            val myComponent = ComponentName(this, JarvisVoiceInteractionService::class.java).flattenToString()
            setting != null && setting.contains(packageName)
        } catch (_: Exception) {
            false
        }
    }

    private fun openDefaultAssistantSettings() {
        try {
            val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallbackIntent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                startActivity(fallbackIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "Please set Jarvis as default assistant in Phone Settings.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openNotificationListenerSettings() {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Please enable notification access in Phone Settings.", Toast.LENGTH_LONG).show()
        }
    }
}
