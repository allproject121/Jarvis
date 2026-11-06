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
import com.jarvis.assistant.databinding.ActivitySuperCoreBinding
import com.jarvis.assistant.ui.settings.ApiConnectionActivity
import com.jarvis.assistant.util.HudSoundManager
import com.jarvis.assistant.util.ThemeManager

/**
 * Jarvis SuperCore & System Control Activity.
 * Provides unified, organized control over:
 *  1. Android Default Digital Assistant (VoiceInteraction)
 *  2. Messaging notification reader / Direct Reply
 *  3. Call Sentinel (caller-ID announcement)
 *  4. Local Host AI (Ollama) and Mem0 memory
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
            binding.tvDefaultAssistantBadge.text = "Active"
            binding.tvDefaultAssistantBadge.setTextColor(ThemeManager.attrColor(this, R.attr.themeSuccess, Color.parseColor("#10B981")))
            binding.tvDefaultAssistantBadge.setBackgroundResource(R.drawable.bg_set_badge)
            binding.btnSetDefaultAssistant.text = "Jarvis is your default assistant ✓"
            binding.btnSetDefaultAssistant.isEnabled = false
        } else {
            binding.tvDefaultAssistantBadge.text = "Not set"
            binding.tvDefaultAssistantBadge.setTextColor(ThemeManager.attrColor(this, R.attr.themeTextSecondary, ContextCompat.getColor(this, R.color.primary_cyan)))
            binding.tvDefaultAssistantBadge.setBackgroundResource(R.drawable.bg_set_badge)
            binding.btnSetDefaultAssistant.text = "Set Jarvis as default assistant"
            binding.btnSetDefaultAssistant.isEnabled = true
        }

        // 2. Check Notification Listener Status
        val isNotifEnabled = JarvisNotificationListener.isNotificationServiceEnabled(this)
        if (isNotifEnabled) {
            binding.tvNotificationListenerBadge.text = "Active"
            binding.tvNotificationListenerBadge.setTextColor(ThemeManager.attrColor(this, R.attr.themeSuccess, Color.parseColor("#10B981")))
            binding.tvNotificationListenerBadge.setBackgroundResource(R.drawable.bg_set_badge)
            binding.btnGrantNotificationAccess.text = "Notification access granted ✓"
            binding.btnGrantNotificationAccess.isEnabled = false
        } else {
            binding.tvNotificationListenerBadge.text = "Needs permission"
            binding.tvNotificationListenerBadge.setTextColor(ThemeManager.attrColor(this, R.attr.themeWarning, Color.parseColor("#F59E0B")))
            binding.tvNotificationListenerBadge.setBackgroundResource(R.drawable.bg_set_badge)
            binding.btnGrantNotificationAccess.text = "Grant notification access"
            binding.btnGrantNotificationAccess.isEnabled = true
        }

        // Switches
        binding.switchWhatsAppReader.isChecked = preferences.isWhatsAppReaderEnabled
        binding.switchAutoReplyConfirm.isChecked = preferences.isMessageAutoReplyConfirmEnabled
        binding.switchCallerIdAnnouncement.isChecked = preferences.isCallerIdAnnouncementEnabled
        binding.switchMem0Memory.isChecked = preferences.isMem0MemoryEnabled
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
        binding.switchWhatsAppReader.setOnCheckedChangeListener { _, isChecked ->
            preferences.isWhatsAppReaderEnabled = isChecked
            HudSoundManager.playTapSound()
        }

        binding.switchAutoReplyConfirm.setOnCheckedChangeListener { _, isChecked ->
            preferences.isMessageAutoReplyConfirmEnabled = isChecked
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
