package com.jarvis.assistant.ui.character

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.databinding.ActivityMoraCharacterBinding
import com.jarvis.assistant.util.HudSoundManager
import com.jarvis.assistant.util.JarvisWebViewClient
import com.jarvis.assistant.util.ModelVaultManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Dedicated Activity for REDMAGIC-inspired AI Character: Mora.
 * Features an independent 3D WebGL viewport, separate bone/animation controller,
 * real-time conversational reactions, and audio-reactive facial lip-sync.
 */
class MoraCharacterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMoraCharacterBinding
    private var isPageLoaded = false
    private var lastAudioPushTime = 0L

    companion object {
        const val MORA_URL = "https://${JarvisWebViewClient.ASSET_DOMAIN}/assets/mora_character.html"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        binding = ActivityMoraCharacterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        setupTopBar()
        setupButtons()
        setupWebView()
        observeConversationPipeline()
    }

    private fun setupTopBar() {
        binding.btnBack.setOnClickListener {
            HudSoundManager.playTapSound()
            finish()
        }
    }

    private fun setupButtons() {
        binding.btnEmoteWave.setOnClickListener {
            HudSoundManager.playTapSound()
            triggerMoraReaction("wave")
        }

        binding.btnEmoteJoy.setOnClickListener {
            HudSoundManager.playTapSound()
            triggerMoraReaction("joy")
        }

        binding.btnEmoteTeach.setOnClickListener {
            HudSoundManager.playTapSound()
            triggerMoraReaction("teach")
        }

        binding.btnEmoteDance.setOnClickListener {
            HudSoundManager.playTapSound()
            triggerMoraReaction("dance")
        }

        binding.btnEmoteTalk.setOnClickListener {
            HudSoundManager.playTapSound()
            setMoraState("speaking")
            binding.moraWebView.evaluateJavascript("if(window.MoraCompanion){ window.MoraCompanion.setExternalAudio(0.8); }", null)
        }

        binding.btnApplyAsMain.setOnClickListener {
            HudSoundManager.playTapSound()
            ModelVaultManager.setActiveVaultItemId(this, ModelVaultManager.BUILTIN_MORA.id)
            JarvisApp.instance.preferences.orbEntityType = "mora_cyber"
            Toast.makeText(this, "REDMAGIC Mora set as active companion on Home Screen!", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.moraWebView.apply {
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
            clearCache(true)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                loadsImagesAutomatically = true
                mediaPlaybackRequiresUserGesture = false
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(cm: android.webkit.ConsoleMessage?): Boolean {
                    android.util.Log.d("MoraJS", "${cm?.message()} -- line ${cm?.lineNumber()} of ${cm?.sourceId()}")
                    return true
                }
            }
            webViewClient = JarvisWebViewClient(this@MoraCharacterActivity) { _, _ ->
                isPageLoaded = true
                setMoraState("idle")
                val theme = JarvisApp.instance.preferences.appTheme
                val hex = JarvisApp.instance.preferences.customThemeColor
                evaluateJavascript("if(window.MoraCompanion && window.MoraCompanion.setTheme){ window.MoraCompanion.setTheme('" + theme + "', '" + hex + "'); }", null)
            }

            val currentTheme = JarvisApp.instance.preferences.appTheme
            val currentHex = JarvisApp.instance.preferences.customThemeColor
            loadUrl(MORA_URL + "?theme=" + currentTheme + "&hex=" + android.net.Uri.encode(currentHex))
        }
    }

    private fun observeConversationPipeline() {
        lifecycleScope.launch {
            JarvisConversationService.conversationState.collectLatest { state ->
                when (state) {
                    ConversationState.LISTENING -> {
                        setMoraState("listening")
                        binding.tvConversationStatus.text = "LISTENING • SPEAK NOW"
                        binding.ivStatusDot.setColorFilter(Color.parseColor("#10B981"))
                    }
                    ConversationState.THINKING -> {
                        setMoraState("thinking")
                        binding.tvConversationStatus.text = "PROCESSING REQUEST..."
                        binding.ivStatusDot.setColorFilter(Color.parseColor("#F59E0B"))
                    }
                    ConversationState.SPEAKING -> {
                        setMoraState("speaking")
                        binding.tvConversationStatus.text = "MORA SPEAKING..."
                        binding.ivStatusDot.setColorFilter(Color.parseColor("#00F0FF"))
                    }
                    ConversationState.IDLE -> {
                        setMoraState("idle")
                        binding.tvConversationStatus.text = "READY • TAP MORA TO INTERACT"
                        binding.ivStatusDot.setColorFilter(Color.parseColor("#FF0055"))
                    }
                    else -> {
                        setMoraState("idle")
                        binding.tvConversationStatus.text = "READY • TAP MORA TO INTERACT"
                        binding.ivStatusDot.setColorFilter(Color.parseColor("#FF0055"))
                    }
                }
            }
        }

        lifecycleScope.launch {
            JarvisConversationService.liveUserTranscript.collectLatest { transcript ->
                if (transcript.isNotBlank()) {
                    val lower = transcript.lowercase().trim()
                    if (lower.contains("hello") || lower.contains("hi") || lower.contains("hey")) {
                        triggerMoraReaction("wave")
                    } else if (lower.contains("thank") || lower.contains("good") || lower.contains("awesome")) {
                        triggerMoraReaction("joy")
                    } else if (lower.contains("remember") || lower.contains("preference") || lower.contains("teach")) {
                        triggerMoraReaction("teach")
                    } else if (lower.contains("dance") || lower.contains("rumba")) {
                        triggerMoraReaction("dance")
                    } else if (lower.contains("stop") || lower.contains("wait") || lower.contains("chup")) {
                        interruptMora()
                    }
                }
            }
        }

        lifecycleScope.launch {
            JarvisConversationService.audioFrequency.collectLatest { freq ->
                val amp = freq.amplitude
                val now = System.currentTimeMillis()
                if (now - lastAudioPushTime >= 33L && isPageLoaded) {
                    lastAudioPushTime = now
                    binding.moraWebView.evaluateJavascript(
                        "if(window.MoraCompanion && window.MoraCompanion.setExternalAudio){ window.MoraCompanion.setExternalAudio($amp); }",
                        null
                    )
                }
            }
        }
    }

    private fun setMoraState(state: String) {
        if (!isPageLoaded) return
        binding.moraWebView.evaluateJavascript(
            "if(window.MoraCompanion && window.MoraCompanion.setState){ window.MoraCompanion.setState('$state'); }",
            null
        )
    }

    private fun triggerMoraReaction(reaction: String) {
        if (!isPageLoaded) return
        binding.moraWebView.evaluateJavascript(
            "if(window.MoraCompanion && window.MoraCompanion.triggerReaction){ window.MoraCompanion.triggerReaction('$reaction'); }",
            null
        )
    }

    private fun interruptMora() {
        if (!isPageLoaded) return
        binding.moraWebView.evaluateJavascript(
            "if(window.MoraCompanion && window.MoraCompanion.interrupt){ window.MoraCompanion.interrupt(); }",
            null
        )
    }

    override fun onResume() {
        super.onResume()
        binding.moraWebView.onResume()
        binding.moraWebView.resumeTimers()
    }

    override fun onPause() {
        super.onPause()
        binding.moraWebView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.moraWebView.destroy()
    }
}
