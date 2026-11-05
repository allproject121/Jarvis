package com.jarvis.assistant.ui.companion

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.macro.MacroEngine
import com.jarvis.assistant.automation.macro.MacroExecutionResult
import com.jarvis.assistant.automation.macro.MacroRepository
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.databinding.FragmentJarvisCompanionHomeBinding
import com.jarvis.assistant.maya.ui.CharacterSheet
import com.jarvis.assistant.maya.ui.MayaCharacter
import com.jarvis.assistant.maya.ui.MayaCharacters
import com.jarvis.assistant.maya.ui.MayaIntimateGate
import com.jarvis.assistant.maya.ui.MayaOverlayCharacter
import com.jarvis.assistant.maya.ui.MayaPalette
import com.jarvis.assistant.maya.ui.MayaScreens
import com.jarvis.assistant.maya.ui.MayaStageController
import com.jarvis.assistant.maya.ui.MayaUi
import com.jarvis.assistant.maya.ui.MayaUiPrefs
import com.jarvis.assistant.maya.ui.MayaVoicePreview
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.util.HudSoundManager
import com.jarvis.assistant.util.ThemeManager
import com.jarvis.assistant.voice.PocketTtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/**
 * Companion Home in the Maya layout: a full-bleed character stage (3D character via
 * maya_character.html, or the native orb on battery saver / < 3 GB RAM / character off),
 * a live status pill, Listening/Thinking/Speaking status bound to the conversation
 * service, a big talk button with ripple rings, mute + share-screen buttons, a chips
 * row into the Maya screens, and the companion cards below.
 */
class JarvisCompanionHomeFragment : Fragment(), MayaStageController.Listener {

    private var _binding: FragmentJarvisCompanionHomeBinding? = null
    private val binding get() = _binding!!
    private val preferences by lazy { JarvisApp.instance.preferences }
    private val macroRepository by lazy { MacroRepository(requireContext()) }
    private val macroEngine by lazy { MacroEngine(requireContext(), macroRepository) }
    private var voicePreview: MayaVoicePreview? = null

    // Stage
    private var stage: MayaStageController? = null
    private var stageLoaded = false
    private var stageFailed = false
    private var lastFallbackReason: String? = null
    private var loadedCharacterId: String? = null

    // Live state
    private var convState = ConversationState.IDLE
    private var sessionOn = false
    private var sharing = false
    private var micMuted = false
    private var userText = ""
    private var replyText = ""
    private var lastStatusTitle = ""
    private var lastKeywordReactionAt = 0L
    private var lastTaskStatus: String? = null
    private var dotAnimator: ValueAnimator? = null
    private var entranceDone = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentJarvisCompanionHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        voicePreview = MayaVoicePreview(requireContext())
        stage = MayaStageController(binding.mayaStageWebView, this)
        entranceDone = false

        setupModeSwitcher()
        setupOrbInteractions()
        setupSecondaryButtons()
        setupChips()
        setupCharacterCarousel()
        setupAutonomousActionGrid()
        setupStageTouch()
        styleAccentButtons()
        updateActiveCharacterUI()
        observeConversation()

        childFragmentManager.setFragmentResultListener(CharacterSheet.REQUEST_KEY, viewLifecycleOwner) { _, _ ->
            updateActiveCharacterUI()
            refreshStageCharacter()
            applyStageMode()
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.jarvisScrollView) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density
            val top = if (bars.top > 0) bars.top + (8 * density).toInt() else (20 * density).toInt()
            (binding.mayaTopBar.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.topMargin = top
                binding.mayaTopBar.layoutParams = it
            }
            (binding.tvJarvisActivePersonaLabel.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.topMargin = top + (44 * density).toInt()
                binding.tvJarvisActivePersonaLabel.layoutParams = it
            }
            (binding.tvMayaFallbackNotice.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.topMargin = top + (86 * density).toInt()
                binding.tvMayaFallbackNotice.layoutParams = it
            }
            insets
        }
        binding.root.post { sizeStage() }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            updateActiveCharacterUI()
            renderStatus()
            if (isShownToUser()) {
                applyStageMode()
                stage?.onResume()
                runEntranceIfNeeded()
            }
            context?.let { MayaOverlayCharacter.restoreIfEnabled(it) }
        }
    }

    override fun onPause() {
        stage?.onPause()
        voicePreview?.stop()
        super.onPause()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (_binding == null) return
        if (!hidden) {
            updateActiveCharacterUI()
            applyStageMode()
            stage?.onResume()
            runEntranceIfNeeded()
        } else {
            stage?.onPause()
            voicePreview?.stop()
        }
    }

    override fun onDestroyView() {
        dotAnimator?.cancel()
        dotAnimator = null
        voicePreview?.stop()
        voicePreview = null
        stage?.destroy()
        stage = null
        stageLoaded = false
        super.onDestroyView()
        _binding = null
    }

    private fun isShownToUser(): Boolean =
        _binding != null && !isHidden && preferences.isJarvisCompanionMode && binding.root.visibility == View.VISIBLE

    private fun dp(v: Float): Int = MayaUi.dp(requireContext(), v)

    // ─────────────────────────────── Stage ───────────────────────────────

    private fun sizeStage() {
        val b = _binding ?: return
        val h = b.root.height
        if (h <= 0) return
        val target = max(h - dp(96f), dp(540f))
        val lp = b.mayaStage.layoutParams
        if (lp.height != target) {
            lp.height = target
            b.mayaStage.layoutParams = lp
        }
    }

    private fun activeCharacter(): MayaCharacter = MayaCharacters.byPrefName(preferences.jarvisActiveCharacter)

    /** Decide between the 3D character and the native orb, and (lazily) load the stage. */
    private fun applyStageMode() {
        val b = _binding ?: return
        val ctx = context ?: return
        if (!isShownToUser()) return
        val reason = if (stageFailed) "The 3D character could not start on this phone, so the orb is shown instead."
        else MayaUiPrefs.characterFallbackReason(ctx)
        val character = activeCharacter()
        if (reason == null) {
            b.mayaOrbView.visibility = View.GONE
            if (b.mayaStageWebView.visibility != View.VISIBLE) {
                b.mayaStageWebView.alpha = 0f
                b.mayaStageWebView.visibility = View.VISIBLE
                b.mayaStageWebView.animate().alpha(1f).setDuration(600L).start()
            }
            if (!stageLoaded) {
                stage?.load(
                    MayaStageController.optionsFromPrefs(ctx, MayaStageController.FRAMING_HALF, MayaStageController.BG_FULL)
                        .copy(displayName = character.title)
                )
                stageLoaded = true
                loadedCharacterId = character.id
            } else {
                stage?.setLowPower(MayaUiPrefs.lowPowerStage(ctx))
                refreshStageCharacter()
            }
            hideFallbackNotice()
        } else {
            if (stageLoaded) {
                stage?.unload()
                stageLoaded = false
            }
            b.mayaStageWebView.visibility = View.INVISIBLE
            val accent = MayaPalette.monogramColor(character.id)
            b.mayaOrbView.setAccent(accent, MayaPalette.LIGHT_BLUE, MayaPalette.CYAN_GLOW)
            b.mayaOrbView.light = ThemeManager.isLightTheme()
            b.mayaOrbView.lowPower = MayaUiPrefs.isBatterySaverOn()
            b.mayaOrbView.centerYFraction = 0.38f
            b.mayaOrbView.state = convState.orbKey
            if (b.mayaOrbView.visibility != View.VISIBLE) {
                b.mayaOrbView.alpha = 0f
                b.mayaOrbView.visibility = View.VISIBLE
                b.mayaOrbView.animate().alpha(1f).setDuration(500L).start()
            }
            if (reason != lastFallbackReason) showFallbackNotice(reason)
        }
        lastFallbackReason = reason
    }

    private fun refreshStageCharacter() {
        val id = activeCharacter().id
        if (stageLoaded && id != loadedCharacterId) {
            stage?.setCharacter(id)
            loadedCharacterId = id
        }
        MayaOverlayCharacter.refreshCharacter()
    }

    private fun showFallbackNotice(text: String) {
        val notice = _binding?.tvMayaFallbackNotice ?: return
        notice.text = text
        notice.alpha = 0f
        notice.translationY = -dp(8f).toFloat()
        notice.visibility = View.VISIBLE
        notice.animate().alpha(1f).translationY(0f).setDuration(300L).withEndAction {
            notice.animate().alpha(0f).setStartDelay(5200L).setDuration(500L).withEndAction {
                notice.visibility = View.GONE
            }.start()
        }.start()
    }

    private fun hideFallbackNotice() {
        _binding?.tvMayaFallbackNotice?.apply { animate().cancel(); visibility = View.GONE }
    }

    override fun onStageReady() {
        stage?.setState(convState.orbKey)
    }

    override fun onStageError(message: String) {
        stageFailed = true
        applyStageMode()
    }

    override fun onPoke(zone: String) {
        _binding?.mayaStageWebView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** Horizontal drags / pinches belong to the stage; vertical drags still scroll the page. */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupStageTouch() {
        val slop = ViewConfiguration.get(requireContext()).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        binding.mayaStageWebView.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; v.parent?.requestDisallowInterceptTouchEvent(false) }
                MotionEvent.ACTION_POINTER_DOWN -> v.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_MOVE -> {
                    val dx = abs(e.x - downX)
                    val dy = abs(e.y - downY)
                    if (dx > slop && dx > dy) v.parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            false
        }
    }

    // ─────────────────────────── Conversation binding ───────────────────────────

    private fun observeConversation() {
        val main = activity as? MainActivity
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    JarvisConversationService.conversationState.collect { s -> onConversationState(s) }
                }
                launch {
                    combine(
                        JarvisConversationService.isSessionOn,
                        JarvisConversationService.connectionStatus,
                        JarvisConversationService.isStandby,
                        JarvisConversationService.liveError
                    ) { on, status, standby, error -> PillState(on, status, standby, error != null) }
                        .collect { renderPill(it) }
                }
                launch {
                    JarvisConversationService.audioLevel.collect { level -> onAudioLevel(level) }
                }
                launch {
                    JarvisConversationService.isScreenSharing.collect { s ->
                        sharing = s
                        renderShareButton()
                        renderStatus()
                    }
                }
                launch {
                    JarvisConversationService.liveUserTranscript.collect { t ->
                        userText = t
                        renderStatus()
                        keywordReaction(t)
                    }
                }
                launch {
                    JarvisConversationService.liveAssistantResponse.collect { t ->
                        replyText = t
                        renderStatus()
                    }
                }
                launch {
                    JarvisConversationService.avatarAction.collect { action -> stage?.react(action) }
                }
                if (main != null) {
                    launch {
                        main.viewModel.isMicMuted.collect { muted ->
                            micMuted = muted
                            renderMuteButton()
                            renderTalkIcon()
                        }
                    }
                }
                launch {
                    com.jarvis.assistant.automation.agent.AgentTaskEngine.taskState.collect { snapshot ->
                        val status = snapshot?.currentStatus
                        if (status != null && status != lastTaskStatus) {
                            when (status) {
                                "COMPLETED" -> stage?.react("thumbs_up")
                                "FAILED", "BLOCKED" -> stage?.react("shrug")
                                "RUNNING", "EXECUTING", "PLANNING", "IN_PROGRESS" -> stage?.react("nod")
                            }
                        }
                        lastTaskStatus = status
                    }
                }
            }
        }
    }

    private data class PillState(val on: Boolean, val status: String, val standby: Boolean, val error: Boolean)

    private fun onConversationState(s: ConversationState) {
        val changed = s != convState
        convState = s
        stage?.setState(s.orbKey)
        _binding?.mayaOrbView?.state = s.orbKey
        renderRings()
        renderTalkIcon()
        renderStatus()
        if (changed) pulseTalkButton()
    }

    private fun onAudioLevel(level: Float) {
        val b = _binding ?: return
        b.mayaTalkRings.setLevel(level)
        if (b.mayaOrbView.visibility == View.VISIBLE) b.mayaOrbView.setAudioLevel(level)
        if (convState == ConversationState.SPEAKING && stageLoaded && !isHidden) stage?.pushAudio(level)
    }

    private fun renderPill(p: PillState) {
        val b = _binding ?: return
        val name = preferences.assistantName.ifBlank { "JARVIS" }
        val wasOn = sessionOn
        sessionOn = p.on
        val (label, color, live) = when {
            !p.on -> Triple("Offline", MayaPalette.TEXT_MUTED, false)
            p.error -> Triple("Reconnecting…", MayaPalette.DANGER_SOFT, false)
            p.standby -> Triple("Standby", MayaPalette.WARNING, false)
            p.status.equals("LIVE", ignoreCase = true) -> Triple("Live", MayaPalette.SUCCESS, true)
            p.status.startsWith("OFFLINE", ignoreCase = true) -> Triple("Offline AI", MayaPalette.SKY, true)
            else -> Triple(prettyStatus(p.status), MayaPalette.WARNING, false)
        }
        b.tvMayaPillTitle.text = "$name • $label"
        b.mayaStatusDot.backgroundTintList = ColorStateList.valueOf(color)
        if (live) startDotPulse() else stopDotPulse()
        if (wasOn != p.on) {
            renderRings()
            renderStatus()
            renderTalkIcon()
        }
    }

    private fun prettyStatus(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return "Connecting…"
        return t.lowercase().replaceFirstChar { it.uppercase() }
    }

    private fun startDotPulse() {
        val dot = _binding?.mayaStatusDot ?: return
        if (dotAnimator?.isRunning == true) return
        dotAnimator = ValueAnimator.ofFloat(1f, 0.35f).apply {
            duration = 900L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { dot.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun stopDotPulse() {
        dotAnimator?.cancel()
        dotAnimator = null
        _binding?.mayaStatusDot?.alpha = 1f
    }

    private fun renderStatus() {
        val b = _binding ?: return
        val name = preferences.assistantName.ifBlank { "JARVIS" }
        val (title, sub) = when (convState) {
            ConversationState.LISTENING -> "Listening…" to userText.trim().takeLast(110).ifBlank { if (micMuted) "Microphone is muted" else "Go ahead, I'm listening" }
            ConversationState.THINKING -> "Thinking…" to userText.trim().takeLast(110).ifBlank { "Working on it" }
            ConversationState.SPEAKING -> "Speaking…" to replyText.trim().takeLast(140).ifBlank { "" }
            ConversationState.IDLE -> (if (sessionOn) "Tap to talk" else "Tap to start") to "Ask $name anything…"
        }
        val full = if (sharing) "$title  ·  Sharing screen" else title
        if (full != lastStatusTitle) {
            lastStatusTitle = full
            b.tvJarvisVoiceStatus.animate().cancel()
            b.tvJarvisVoiceStatus.animate().alpha(0f).setDuration(90L).withEndAction {
                _binding?.tvJarvisVoiceStatus?.let { tv ->
                    tv.text = full
                    tv.animate().alpha(1f).setDuration(160L).start()
                }
            }.start()
        }
        b.tvJarvisVoiceSub.text = sub
        b.tvMayaTalkHint.text = when {
            !sessionOn -> "Tap to start · ${activeCharacter().title} is ready"
            convState == ConversationState.SPEAKING -> "Tap to interrupt · hold to end"
            else -> "Tap to mute or unmute · hold to end"
        }
    }

    private fun renderRings() {
        val b = _binding ?: return
        b.mayaTalkRings.buttonRadiusPx = dp(44f).toFloat()
        b.mayaTalkRings.setState(convState.orbKey, sessionOn, MayaUi.accent(requireContext()))
    }

    private fun renderTalkIcon() {
        val b = _binding ?: return
        val icon = when {
            sessionOn && convState == ConversationState.SPEAKING -> R.drawable.ic_close
            sessionOn && micMuted -> R.drawable.ic_mic_mute_jarvis
            else -> R.drawable.ic_mic_jarvis
        }
        b.ivJarvisOrbIcon.setImageResource(icon)
        b.ivJarvisOrbIcon.imageTintList = ColorStateList.valueOf(MayaUi.onAccent(requireContext()))
    }

    private fun renderMuteButton() {
        val b = _binding ?: return
        b.btnMayaMute.setBackgroundResource(if (micMuted) R.drawable.bg_maya_round_button_active else R.drawable.bg_maya_round_button)
        b.ivMayaMuteIcon.setImageResource(if (micMuted) R.drawable.ic_mic_mute_jarvis else R.drawable.ic_mic_jarvis)
        b.ivMayaMuteIcon.imageTintList = ColorStateList.valueOf(
            if (micMuted) MayaUi.accent(requireContext()) else MayaUi.textPrimary(requireContext())
        )
        b.btnMayaMute.contentDescription = if (micMuted) "Unmute microphone" else "Mute microphone"
    }

    private fun renderShareButton() {
        val b = _binding ?: return
        b.btnMayaShareScreen.setBackgroundResource(if (sharing) R.drawable.bg_maya_round_button_active else R.drawable.bg_maya_round_button)
        b.ivMayaShareIcon.imageTintList = ColorStateList.valueOf(
            if (sharing) MayaUi.accent(requireContext()) else MayaUi.textPrimary(requireContext())
        )
        b.btnMayaShareScreen.contentDescription = if (sharing) "Stop sharing screen" else "Share screen"
    }

    private fun pulseTalkButton() {
        val b = _binding ?: return
        b.mayaTalkRings.burst()
        b.btnJarvisOrb.animate().cancel()
        b.btnJarvisOrb.animate().scaleX(1.08f).scaleY(1.08f).setDuration(110L).withEndAction {
            _binding?.btnJarvisOrb?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(260L)?.setInterpolator(OvershootInterpolator(2.2f))?.start()
        }.start()
    }

    private fun keywordReaction(transcript: String) {
        val lower = transcript.lowercase().trim()
        if (lower.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastKeywordReactionAt < 3500L) return
        val reaction = when {
            lower.contains("stop") || lower.contains("wait") || lower.contains("chup") -> { stage?.interrupt(); null }
            lower.contains("hello") || lower.contains("hi ") || lower == "hi" || lower.contains("hey") || lower.contains("namaste") -> "wave"
            lower.contains("love you") || lower.contains("kiss") -> "blow_kiss"
            lower.contains("thank") || lower.contains("shukriya") -> "heart_hands"
            lower.contains("awesome") || lower.contains("great") || lower.contains("badhiya") -> "thumbs_up"
            lower.contains("dance") || lower.contains("party") -> "excited_bounce"
            lower.contains("peace") -> "peace_sign"
            else -> null
        }
        if (reaction != null) {
            lastKeywordReactionAt = now
            stage?.react(reaction)
        }
    }

    // ─────────────────────────────── Controls ───────────────────────────────

    private fun setupModeSwitcher() {
        binding.btnSwitchToJarvisMode.setOnClickListener {
            HudSoundManager.playTapSound()
            preferences.experienceMode = AppPreferences.MODE_JARVIS
            Toast.makeText(requireContext(), "⚡ Switched to JARVIS Mode", Toast.LENGTH_SHORT).show()
            (activity as? MainActivity)?.switchExperienceMode(AppPreferences.MODE_JARVIS)
        }
    }

    private fun setupOrbInteractions() {
        binding.btnJarvisOrb.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            binding.btnJarvisOrb.animate()
                .scaleX(0.92f).scaleY(0.92f)
                .setDuration(90)
                .withEndAction {
                    _binding?.btnJarvisOrb?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(220)?.setInterpolator(OvershootInterpolator(2.5f))?.start()
                }.start()
            binding.mayaTalkRings.burst()
            (activity as? MainActivity)?.handleMicOrOrbClick()
        }

        binding.btnJarvisOrb.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            (activity as? MainActivity)?.handleHoldMicOrOrb()
            true
        }
        binding.mayaStatusPill.setOnClickListener {
            HudSoundManager.playTapSound()
            val status = JarvisConversationService.connectionStatus.value
            Toast.makeText(requireContext(), if (sessionOn) "Connection: $status" else "Tap the talk button to start", Toast.LENGTH_SHORT).show()
        }
        binding.tvJarvisActivePersonaLabel.setOnClickListener { openCharacterSheet() }
    }

    private fun setupSecondaryButtons() {
        binding.btnMayaMute.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val main = activity as? MainActivity
            if (main != null) {
                main.viewModel.toggleMicMute()
            } else {
                micMuted = !micMuted
                preferences.isMicMuted = micMuted
                JarvisConversationService.setMicMuted(micMuted)
                renderMuteButton()
            }
        }
        binding.btnMayaShareScreen.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            (activity as? MainActivity)?.toggleScreenShare()
        }
        micMuted = preferences.isMicMuted
        renderMuteButton()
        renderShareButton()
        renderRings()
        renderTalkIcon()
    }

    private fun setupChips() {
        val ctx = requireContext()
        val row = binding.mayaChipsRow
        row.removeAllViews()
        val chips: List<Pair<String, () -> Unit>> = listOf(
            "Characters" to { openCharacterSheet() },
            "Voice engine" to { MayaScreens.open(ctx, MayaScreens.VOICE_ENGINE, "Voice engine isn't available in this build yet") },
            "Memory & data" to {
                if (MayaScreens.exists(MayaScreens.WIKI)) MayaScreens.open(ctx, MayaScreens.WIKI, "Memory & data isn't available yet")
                else startActivity(Intent(ctx, com.jarvis.assistant.ui.memory.MemoryActivity::class.java))
            },
            "Macros" to { MayaScreens.open(ctx, MayaScreens.MACROS, "Macros & watches aren't available in this build yet") },
            "Connectors" to {
                if (MayaScreens.exists(MayaScreens.CONNECTORS)) MayaScreens.open(ctx, MayaScreens.CONNECTORS, "Connectors aren't available yet")
                else com.jarvis.assistant.connectors.AppConnectorsBottomSheet.newInstance()
                    .show(childFragmentManager, com.jarvis.assistant.connectors.AppConnectorsBottomSheet.TAG)
            },
            "Hub" to { MayaScreens.open(ctx, MayaScreens.HUB, "The Hub isn't available in this build yet") },
            "Media" to { MayaScreens.open(ctx, MayaScreens.MEDIA_STUDIO, "Media studio isn't available in this build yet") },
            "Code Studio" to { MayaScreens.open(ctx, MayaScreens.CODE_STUDIO, "Code Studio isn't available in this build") },
            "God's Eye" to { startActivity(Intent(ctx, com.jarvis.assistant.ui.godseye.GodsEyeActivity::class.java)) }
        )
        chips.forEach { (label, action) ->
            val chip = MayaUi.text(ctx, label, 13f, 500).apply {
                setBackgroundResource(R.drawable.bg_maya_chip)
                setPadding(dp(14f), dp(9f), dp(14f), dp(9f))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    HudSoundManager.playTapSound()
                    action()
                }
            }
            row.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8f) })
        }
    }

    private fun openCharacterSheet() {
        HudSoundManager.playTapSound()
        if (childFragmentManager.findFragmentByTag(CharacterSheet.TAG) == null) {
            CharacterSheet.newInstance().show(childFragmentManager, CharacterSheet.TAG)
        }
    }

    private fun styleAccentButtons() {
        listOf(
            binding.btnRunChatGptImage, binding.btnRunGeminiMedia,
            binding.btnTestPocketTts
        ).forEach { MayaUi.styleAccentButton(it) }
    }

    // ───────────────────────────── Entrance motion ─────────────────────────────

    private fun runEntranceIfNeeded() {
        val b = _binding ?: return
        if (entranceDone) return
        entranceDone = true
        val decel = DecelerateInterpolator(1.6f)
        b.mayaStageCanvas.alpha = 0f
        b.mayaStageCanvas.animate().alpha(1f).setDuration(700L).start()
        b.mayaTopBar.alpha = 0f
        b.mayaTopBar.translationY = -dp(16f).toFloat()
        b.mayaTopBar.animate().alpha(1f).translationY(0f).setStartDelay(120L).setDuration(420L).setInterpolator(decel).start()
        b.tvJarvisActivePersonaLabel.alpha = 0f
        b.tvJarvisActivePersonaLabel.animate().alpha(1f).setStartDelay(260L).setDuration(400L).start()
        b.mayaBottomControls.alpha = 0f
        b.mayaBottomControls.translationY = dp(36f).toFloat()
        b.mayaBottomControls.animate().alpha(1f).translationY(0f).setStartDelay(220L).setDuration(520L).setInterpolator(decel).start()
        for (i in 0 until b.mayaChipsRow.childCount) {
            val c = b.mayaChipsRow.getChildAt(i)
            c.alpha = 0f
            c.translationX = dp(24f).toFloat()
            c.animate().alpha(1f).translationX(0f).setStartDelay(380L + i * 45L).setDuration(360L).setInterpolator(decel).start()
        }
        val cards = b.mayaCardsSection
        for (i in 0 until cards.childCount) {
            val c = cards.getChildAt(i)
            c.alpha = 0f
            c.translationY = dp(22f).toFloat()
            c.animate().alpha(1f).translationY(0f).setStartDelay(480L + i * 70L).setDuration(420L).setInterpolator(decel).start()
        }
    }

    // ───────────────────────────── Character carousel ─────────────────────────────

    private fun carouselCards(): List<Triple<View, FrameLayout, MayaCharacter>> = listOf(
        Triple(binding.cardCharMaya, binding.monoCharMaya, MayaCharacters.MAYA),
        Triple(binding.cardCharMayaPro, binding.monoCharMayaPro, MayaCharacters.MAYA_PRO),
        Triple(binding.cardCharMayaIntimate, binding.monoCharMayaIntimate, MayaCharacters.MAYA_INTIMATE),
        Triple(binding.cardCharFriday, binding.monoCharFriday, MayaCharacters.FRIDAY),
        Triple(binding.cardCharVenom, binding.monoCharVenom, MayaCharacters.VENOM),
        Triple(binding.cardCharJarvis, binding.monoCharJarvis, MayaCharacters.JARVIS)
    )

    private fun setupCharacterCarousel() {
        carouselCards().forEach { (_, mono, c) ->
            mono.removeAllViews()
            mono.addView(MayaUi.monogram(requireContext(), c, 44f), FrameLayout.LayoutParams(dp(44f), dp(44f)))
        }

        binding.cardCharMaya.setOnClickListener { selectCharacter(MayaCharacters.MAYA) }
        binding.btnPreviewMaya.setOnClickListener { playVoiceSample(MayaCharacters.MAYA) }

        binding.cardCharMayaPro.setOnClickListener { selectCharacter(MayaCharacters.MAYA_PRO) }
        binding.btnPreviewMayaPro.setOnClickListener { playVoiceSample(MayaCharacters.MAYA_PRO) }

        binding.cardCharMayaIntimate.setOnClickListener { showIntimateSettingsDialog() }
        binding.btnPreviewMayaIntimate.setOnClickListener { showIntimateSettingsDialog() }

        binding.cardCharFriday.setOnClickListener { selectCharacter(MayaCharacters.FRIDAY) }
        binding.btnPreviewFriday.setOnClickListener { playVoiceSample(MayaCharacters.FRIDAY) }

        binding.cardCharVenom.setOnClickListener { selectCharacter(MayaCharacters.VENOM) }
        binding.btnPreviewVenom.setOnClickListener { playVoiceSample(MayaCharacters.VENOM) }

        binding.cardCharJarvis.setOnClickListener { selectCharacter(MayaCharacters.JARVIS) }
        binding.btnPreviewJarvis.setOnClickListener { playVoiceSample(MayaCharacters.JARVIS) }

        binding.tvMayaSeeAllCharacters.setOnClickListener { openCharacterSheet() }
    }

    private fun selectCharacter(c: MayaCharacter) {
        HudSoundManager.playTapSound()
        preferences.jarvisActiveCharacter = c.prefName
        preferences.personality = c.personality
        // Keep the user's chosen voice when it belongs to this character, otherwise use its default.
        if (c.voices.none { it.equals(preferences.voice, ignoreCase = true) }) preferences.voice = c.defaultVoice
        updateActiveCharacterUI()
        refreshStageCharacter()
        stage?.react("wave")
        Toast.makeText(requireContext(), "Selected Persona: ${c.title}", Toast.LENGTH_SHORT).show()
    }

    private fun updateActiveCharacterUI() {
        val b = _binding ?: return
        val active = activeCharacter()
        b.tvJarvisActivePersonaLabel.text = "${active.title} · ${active.badge.lowercase().replaceFirstChar { it.uppercase() }}"
        carouselCards().forEach { (card, _, c) ->
            card.setBackgroundResource(if (c.id == active.id) R.drawable.bg_maya_card_active else R.drawable.bg_maya_card)
        }
    }

    private fun playVoiceSample(c: MayaCharacter) {
        val ctx = context ?: return
        val active = activeCharacter()
        val voice = if (active.id == c.id && c.voices.any { it.equals(preferences.voice, ignoreCase = true) }) preferences.voice else c.defaultVoice
        val bundled = voicePreview?.play(c, voice) ?: false
        Toast.makeText(ctx, if (bundled) "Playing ${c.title} · $voice" else "Synthesizing with PocketTTS…", Toast.LENGTH_SHORT).show()
    }

    private fun showIntimateSettingsDialog() {
        MayaIntimateGate.open(requireContext()) { _ ->
            updateActiveCharacterUI()
            refreshStageCharacter()
            stage?.react("blow_kiss")
        }
    }

    // ───────────────────────────── Autonomous cards ─────────────────────────────

    private fun setupAutonomousActionGrid() {
        // 1. ChatGPT Image Macro
        binding.btnRunChatGptImage.setOnClickListener {
            promptAndRunMacro("chatgpt image", "image prompt", "Futuristic cyberpunk warrior with neon armor")
        }

        // 2. Gemini Video / Image Macro
        binding.btnRunGeminiMedia.setOnClickListener {
            val items = arrayOf("Gemini Image Generator", "Gemini Veo Video Creator")
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Select Gemini Autonomous Directive")
                .setItems(items) { _, which ->
                    if (which == 0) {
                        promptAndRunMacro("gemini image", "image prompt", "Majestic dragon flying over hyper-tech city")
                    } else {
                        promptAndRunMacro("gemini video", "video prompt", "Cinematic drone shot flying through futuristic neon Tokyo")
                    }
                }
                .show()
        }

        // 4. PocketTTS Engine Test
        binding.btnTestPocketTts.setOnClickListener {
            val text = "नमस्ते Roshan! Pocket TTS इंजन बिल्कुल सही काम कर रहा है।"
            PocketTtsEngine.speak(text, requireContext())
            Toast.makeText(requireContext(), "Synthesizing: $text", Toast.LENGTH_LONG).show()
        }

    }

    private fun promptAndRunMacro(macroName: String, paramKey: String, defaultPrompt: String) {
        val input = EditText(requireContext()).apply {
            setText(defaultPrompt)
            setSelection(defaultPrompt.length)
            setPadding(32, 24, 32, 24)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Execute '$macroName'")
            .setMessage("Enter the prompt for the autonomous macro pipeline:")
            .setView(input)
            .setPositiveButton("Execute Now") { _, _ ->
                val promptText = input.text.toString().trim()
                executeMacroDirectly(macroName, mapOf(paramKey to promptText))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeMacroDirectly(macroName: String, params: Map<String, String>) {
        if (JarvisAccessibilityService.instance == null) {
            Toast.makeText(
                requireContext(),
                "Please enable Accessibility Service for Autonomous Macros",
                Toast.LENGTH_LONG
            ).show()
            try {
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) {}
            return
        }

        Toast.makeText(requireContext(), "Starting macro '$macroName'…", Toast.LENGTH_SHORT).show()
        stage?.react("nod")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                macroEngine.executeMacroByName(macroName, params)
            }
            val ctx = context ?: return@launch
            when (result) {
                is MacroExecutionResult.Success -> {
                    stage?.react("thumbs_up")
                    Toast.makeText(ctx, "Macro Finished: ${result.message}", Toast.LENGTH_LONG).show()
                }
                is MacroExecutionResult.Failure -> {
                    stage?.react("shrug")
                    Toast.makeText(ctx, "Macro Failed: ${result.reason}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

}
