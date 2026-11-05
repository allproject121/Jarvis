package com.jarvis.assistant.maya.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.voice.overlay.OverlayPermission

/**
 * "Maya character" option for the Overlay & Avatar screen: a switch for the floating
 * character on the home screen and its size (`character_size_dp`).
 */
class MayaOverlaySettingsCard(private val activity: Activity) {

    val view: View
    private val toggle: MaterialSwitch
    private val sizeLabel: TextView
    private val seek: SeekBar
    private val hint: TextView
    private var updating = false

    init {
        val ctx = activity
        val pad = MayaUi.dp(ctx, 16f)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_maya_card)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                marginStart = MayaUi.dp(ctx, 20f)
                marginEnd = MayaUi.dp(ctx, 20f)
                topMargin = MayaUi.dp(ctx, 4f)
                bottomMargin = MayaUi.dp(ctx, 14f)
            }
        }

        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val character = MayaCharacters.byPrefName(try { JarvisApp.instance.preferences.jarvisActiveCharacter } catch (_: Throwable) { null })
        header.addView(MayaUi.monogram(ctx, character, 40f), LinearLayout.LayoutParams(MayaUi.dp(ctx, 40f), MayaUi.dp(ctx, 40f)))
        val titles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(MayaUi.text(ctx, "Maya character on home screen", 15f, 600))
        titles.addView(
            MayaUi.text(ctx, "A 3D character on Home and over other apps. Drag to move · tap to poke · double-tap to open Jarvis · long-press to hide.", 12f, 400, MayaUi.textMuted(ctx)).apply {
                setLineSpacing(MayaUi.dp(ctx, 1.5f).toFloat(), 1f)
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = MayaUi.dp(ctx, 3f) }
        )
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = MayaUi.dp(ctx, 12f)
            marginEnd = MayaUi.dp(ctx, 8f)
        })
        toggle = MaterialSwitch(ctx)
        header.addView(toggle)
        card.addView(header)

        sizeLabel = MayaUi.text(ctx, "", 12.5f, 600, MayaUi.textSecondary(ctx))
        card.addView(sizeLabel, LinearLayout.LayoutParams(-2, -2).apply { topMargin = MayaUi.dp(ctx, 14f) })
        seek = SeekBar(ctx).apply {
            max = MayaUiPrefs.MAX_CHARACTER_SIZE_DP - MayaUiPrefs.MIN_CHARACTER_SIZE_DP
        }
        card.addView(seek, LinearLayout.LayoutParams(-1, -2).apply { topMargin = MayaUi.dp(ctx, 4f) })

        hint = MayaUi.text(ctx, "Needs \"Display over other apps\" — turning the switch on opens that setting.", 12f, 500, MayaUi.accent(ctx))
        card.addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = MayaUi.dp(ctx, 8f) })

        card.setOnClickListener { toggle.toggle() }
        toggle.setOnCheckedChangeListener { _, on ->
            if (updating) return@setOnCheckedChangeListener
            if (on) MayaOverlayCharacter.enable(activity) else MayaOverlayCharacter.disable(activity)
            refresh()
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val dp = MayaUiPrefs.MIN_CHARACTER_SIZE_DP + progress
                sizeLabel.text = "Size · $dp dp"
                if (fromUser) {
                    MayaUiPrefs.setCharacterSizeDp(activity, dp)
                    MayaOverlayCharacter.refreshSize()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
        view = card
        refresh()
    }

    fun refresh() {
        val granted = OverlayPermission.isGranted(activity)
        val enabled = MayaUiPrefs.isOverlayEnabled(activity)
        if (enabled && granted) MayaOverlayCharacter.restoreIfEnabled(activity)
        updating = true
        try {
            toggle.isChecked = enabled && granted
        } finally {
            updating = false
        }
        val size = MayaUiPrefs.characterSizeDp(activity)
        seek.progress = size - MayaUiPrefs.MIN_CHARACTER_SIZE_DP
        sizeLabel.text = "Size · $size dp"
        hint.visibility = if (granted) View.GONE else View.VISIBLE
    }
}
