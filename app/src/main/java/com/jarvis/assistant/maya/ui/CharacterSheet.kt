package com.jarvis.assistant.maya.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.util.HudSoundManager
import com.jarvis.assistant.voice.overlay.OverlayPermission

/**
 * Maya's "Characters" sheet: Maya (free), Maya Pro, Maya Intimate (18+ gate),
 * Friday, Venom and Jarvis — each with a monogram, its Gemini voices (bundled
 * previews), "Use" to make it the active companion, plus "Show on home screen"
 * (floating overlay) and "Use as live wallpaper".
 *
 * Selecting sets `jarvisActiveCharacter`, `voice` (Gemini voice name used by the Live
 * session) and `personality`, then posts a fragment result [REQUEST_KEY].
 */
class CharacterSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "MayaCharacterSheet"
        const val REQUEST_KEY = "maya_character_sheet"
        const val RESULT_CHARACTER_ID = "character_id"
        fun newInstance() = CharacterSheet()
    }

    private val prefs by lazy { JarvisApp.instance.preferences }
    private var preview: MayaVoicePreview? = null
    private val chosenVoice = HashMap<String, String>()
    private var list: LinearLayout? = null
    private var homeSwitch: MaterialSwitch? = null
    private var homeSwitchSub: TextView? = null
    private var overlayButton: TextView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.bottom_sheet_maya_characters, container, false)

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        preview = MayaVoicePreview(requireContext())
        list = view.findViewById(R.id.mayaCharacterList)
        homeSwitch = view.findViewById(R.id.switchMayaSheetCharacterOnHome)
        homeSwitchSub = view.findViewById(R.id.tvMayaSheetCharacterOnHomeSub)
        overlayButton = view.findViewById(R.id.btnMayaShowOnHomeScreen)
        val wallpaperButton = view.findViewById<TextView>(R.id.btnMayaUseAsWallpaper)
        overlayButton?.let { MayaUi.styleOutlineButton(it) }
        MayaUi.styleOutlineButton(wallpaperButton)

        homeSwitch?.isChecked = MayaUiPrefs.isCharacterOnHome(requireContext())
        updateHomeSwitchSubtitle()
        homeSwitch?.setOnCheckedChangeListener { _, checked ->
            MayaUiPrefs.setCharacterOnHome(requireContext(), checked)
            updateHomeSwitchSubtitle()
            notifyChanged(MayaCharacters.byPrefName(prefs.jarvisActiveCharacter).id)
        }
        view.findViewById<View>(R.id.rowMayaSheetCharacterOnHome).setOnClickListener { homeSwitch?.toggle() }

        overlayButton?.setOnClickListener {
            HudSoundManager.playTapSound()
            val ctx = requireContext()
            if (MayaOverlayCharacter.isShowing()) {
                MayaOverlayCharacter.disable(ctx)
                Toast.makeText(ctx, "Character removed from your home screen", Toast.LENGTH_SHORT).show()
            } else {
                MayaOverlayCharacter.enable(ctx)
            }
            updateOverlayButton()
        }
        wallpaperButton.setOnClickListener {
            HudSoundManager.playTapSound()
            MayaLiveWallpaper.use(requireActivity())
        }
        updateOverlayButton()
        buildCards()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the overlay permission screen
        if (MayaUiPrefs.isOverlayEnabled(requireContext()) && OverlayPermission.isGranted(requireContext())) {
            MayaOverlayCharacter.restoreIfEnabled(requireContext())
        }
        updateOverlayButton()
    }

    override fun onDestroyView() {
        preview?.stop()
        preview = null
        list = null
        super.onDestroyView()
    }

    private fun updateHomeSwitchSubtitle() {
        val ctx = context ?: return
        val reason = MayaUiPrefs.characterFallbackReason(ctx)
        homeSwitchSub?.text = when {
            !MayaUiPrefs.isCharacterOnHome(ctx) -> "Off — Home shows the orb."
            reason != null -> reason
            else -> "A 3D character inside the app, instead of the orb."
        }
    }

    private fun updateOverlayButton() {
        overlayButton?.text = if (MayaOverlayCharacter.isShowing()) "Remove from home screen" else "Show on home screen"
    }

    private fun voicesFor(c: MayaCharacter): List<String> {
        val withPreview = MayaCharacters.voicesWithPreview(requireContext(), c)
        return if (withPreview.isNotEmpty()) withPreview else c.voices.take(8)
    }

    private fun currentVoiceFor(c: MayaCharacter, voices: List<String>): String {
        chosenVoice[c.id]?.let { return it }
        val active = MayaCharacters.byPrefName(prefs.jarvisActiveCharacter)
        val v = if (active.id == c.id && voices.contains(prefs.voice)) prefs.voice else c.defaultVoice
        return if (voices.contains(v) || voices.isEmpty()) v else voices.first()
    }

    private fun buildCards() {
        val container = list ?: return
        container.removeAllViews()
        val ctx = requireContext()
        val active = MayaCharacters.byPrefName(prefs.jarvisActiveCharacter)
        MayaCharacters.ALL.forEachIndexed { index, c ->
            val card = buildCard(c, c.id == active.id)
            container.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = MayaUi.dp(ctx, 12f) })
            card.alpha = 0f
            card.translationY = MayaUi.dp(ctx, 14f).toFloat()
            card.animate().alpha(1f).translationY(0f).setStartDelay(40L * index).setDuration(260L).start()
        }
    }

    private fun buildCard(c: MayaCharacter, isActive: Boolean): View {
        val ctx = requireContext()
        val pad = MayaUi.dp(ctx, 16f)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(if (isActive) R.drawable.bg_maya_card_active else R.drawable.bg_maya_card)
            setPadding(pad, pad, pad, pad)
        }

        // Header: monogram · name + badge · tagline · active pill
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(MayaUi.monogram(ctx, c, 52f), LinearLayout.LayoutParams(MayaUi.dp(ctx, 52f), MayaUi.dp(ctx, 52f)))
        val titles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val nameRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nameRow.addView(MayaUi.text(ctx, c.title, 17f, 600))
        nameRow.addView(MayaUi.badge(ctx, c.badge), LinearLayout.LayoutParams(-2, -2).apply { marginStart = MayaUi.dp(ctx, 8f) })
        titles.addView(nameRow)
        titles.addView(MayaUi.text(ctx, c.tagline, 12.5f, 500, MayaUi.textSecondary(ctx)), LinearLayout.LayoutParams(-2, -2).apply { topMargin = MayaUi.dp(ctx, 4f) })
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = MayaUi.dp(ctx, 14f) })
        if (isActive) {
            header.addView(MayaUi.text(ctx, "Active", 11f, 600, MayaUi.accent(ctx)).apply {
                setBackgroundResource(R.drawable.bg_maya_chip_selected)
                setPadding(MayaUi.dp(ctx, 10f), MayaUi.dp(ctx, 5f), MayaUi.dp(ctx, 10f), MayaUi.dp(ctx, 5f))
            })
        }
        card.addView(header)

        card.addView(MayaUi.text(ctx, c.description, 13f, 400, MayaUi.textMuted(ctx)).apply {
            setLineSpacing(MayaUi.dp(ctx, 2f).toFloat(), 1f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = MayaUi.dp(ctx, 10f) })

        // Voice chooser
        val voices = voicesFor(c)
        var selected = currentVoiceFor(c, voices)
        card.addView(MayaUi.text(ctx, "VOICE", 10.5f, 600, MayaUi.textMuted(ctx)).apply { letterSpacing = 0.08f },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = MayaUi.dp(ctx, 14f) })
        val chipsScroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val chipViews = ArrayList<TextView>()
        fun paintChips() {
            chipViews.forEach { chip ->
                val on = chip.tag == selected
                chip.setBackgroundResource(if (on) R.drawable.bg_maya_chip_selected else R.drawable.bg_maya_chip)
                chip.setTextColor(if (on) MayaUi.accent(ctx) else MayaUi.textPrimary(ctx))
            }
        }
        voices.forEach { v ->
            val chip = MayaUi.text(ctx, v, 12.5f, 500).apply {
                tag = v
                setPadding(MayaUi.dp(ctx, 12f), MayaUi.dp(ctx, 7f), MayaUi.dp(ctx, 12f), MayaUi.dp(ctx, 7f))
                setOnClickListener {
                    selected = v
                    chosenVoice[c.id] = v
                    paintChips()
                    preview?.play(c, v)
                }
            }
            chipViews.add(chip)
            chips.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = MayaUi.dp(ctx, 8f) })
        }
        paintChips()
        chipsScroll.addView(chips)
        card.addView(chipsScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = MayaUi.dp(ctx, 8f) })

        // Actions
        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val previewBtn = MayaUi.text(ctx, "▶  Preview", 13f, 600).apply {
            gravity = Gravity.CENTER
            MayaUi.styleOutlineButton(this)
            setOnClickListener {
                HudSoundManager.playTapSound()
                val bundled = preview?.play(c, selected) ?: false
                if (!bundled) Toast.makeText(ctx, "No bundled sample for $selected — reading with the offline voice", Toast.LENGTH_SHORT).show()
            }
        }
        val useBtn = MayaUi.text(ctx, if (isActive) "In use · change voice" else "Use ${c.title}", 13f, 600).apply {
            gravity = Gravity.CENTER
            MayaUi.styleAccentButton(this)
            setOnClickListener {
                HudSoundManager.playTapSound()
                select(c, selected)
            }
        }
        actions.addView(previewBtn, LinearLayout.LayoutParams(0, MayaUi.dp(ctx, 44f), 1f).apply { marginEnd = MayaUi.dp(ctx, 6f) })
        actions.addView(useBtn, LinearLayout.LayoutParams(0, MayaUi.dp(ctx, 44f), 1.4f).apply { marginStart = MayaUi.dp(ctx, 6f) })
        card.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = MayaUi.dp(ctx, 14f) })
        return card
    }

    private fun select(c: MayaCharacter, voice: String) {
        val ctx = requireContext()
        if (c.adultGate) {
            MayaIntimateGate.open(ctx) { _ -> applySelection(c, voice, personalityAlreadySet = true) }
            return
        }
        applySelection(c, voice, personalityAlreadySet = false)
    }

    private fun applySelection(c: MayaCharacter, voice: String, personalityAlreadySet: Boolean) {
        val ctx = context ?: return
        val voiceChanged = !prefs.voice.equals(voice, ignoreCase = true)
        prefs.jarvisActiveCharacter = c.prefName
        if (!personalityAlreadySet) prefs.personality = c.personality
        prefs.voice = voice
        preview?.stop()
        buildCards()
        notifyChanged(c.id)
        MayaOverlayCharacter.refreshCharacter()
        Toast.makeText(ctx, "${c.title} is your companion · voice $voice", Toast.LENGTH_SHORT).show()
        if (voiceChanged && JarvisConversationService.isSessionOn.value) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Use the new voice now?")
                .setMessage("${c.title} will speak with $voice after a quick reconnect of the live session.")
                .setPositiveButton("Reconnect") { _, _ -> JarvisConversationService.restartSession() }
                .setNegativeButton("Next time", null)
                .show()
        }
    }

    private fun notifyChanged(id: String) {
        try {
            parentFragmentManager.setFragmentResult(REQUEST_KEY, bundleOf(RESULT_CHARACTER_ID to id))
        } catch (_: Throwable) {
        }
    }
}
