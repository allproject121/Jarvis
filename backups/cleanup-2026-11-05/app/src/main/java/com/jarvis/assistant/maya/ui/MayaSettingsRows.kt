package com.jarvis.assistant.maya.ui

import android.content.Intent
import android.view.View
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.util.HudSoundManager
import com.jarvis.assistant.voice.overlay.OverlayPermission

/**
 * Binds the "MAYA" group in fragment_settings.xml: the eight Maya screens (opened
 * reflectively through [MayaScreens]), Characters, Character on Home, battery-saver
 * override, overlay character, live wallpaper and the licences screen.
 */
class MayaSettingsRows(private val fragment: Fragment) {

    private var root: View? = null
    private var updating = false

    fun bind(view: View) {
        root = view
        val ctx = fragment.requireContext()

        click(R.id.rowMayaCharacters) {
            CharacterSheet.newInstance().show(fragment.childFragmentManager, CharacterSheet.TAG)
        }
        click(R.id.rowMayaVoiceEngine) { MayaScreens.open(ctx, MayaScreens.VOICE_ENGINE, "Voice engine isn't available in this build yet") }
        click(R.id.rowMayaConnectors) {
            if (!MayaScreens.exists(MayaScreens.CONNECTORS)) {
                // Existing Jarvis connectors sheet until the Maya screen ships
                com.jarvis.assistant.connectors.AppConnectorsBottomSheet.newInstance()
                    .show(fragment.childFragmentManager, com.jarvis.assistant.connectors.AppConnectorsBottomSheet.TAG)
            } else {
                MayaScreens.open(ctx, MayaScreens.CONNECTORS, "Connectors aren't available in this build yet")
            }
        }
        click(R.id.rowMayaBusiness) { MayaScreens.open(ctx, MayaScreens.BUSINESS, "Business tools aren't available in this build yet") }
        click(R.id.rowMayaFaces) { MayaScreens.open(ctx, MayaScreens.FACES, "Faces & Guardian isn't available in this build yet") }
        click(R.id.rowMayaMemory) {
            if (!MayaScreens.exists(MayaScreens.WIKI)) {
                fragment.startActivity(Intent(ctx, com.jarvis.assistant.ui.memory.MemoryActivity::class.java))
            } else {
                MayaScreens.open(ctx, MayaScreens.WIKI, "Memory & data isn't available in this build yet")
            }
        }
        click(R.id.rowMayaMacros) { MayaScreens.open(ctx, MayaScreens.MACROS, "Macros & watches aren't available in this build yet") }
        click(R.id.rowMayaHub) { MayaScreens.open(ctx, MayaScreens.HUB, "The Hub isn't available in this build yet") }
        click(R.id.rowMayaMediaStudio) { MayaScreens.open(ctx, MayaScreens.MEDIA_STUDIO, "Media studio isn't available in this build yet") }
        click(R.id.rowMayaLicences) { fragment.startActivity(Intent(ctx, LicencesActivity::class.java)) }
        click(R.id.rowMayaLiveWallpaper) { onWallpaperRow() }

        switchRow(R.id.rowMayaCharacterOnHome, R.id.switchMayaCharacterOnHome) { on ->
            MayaUiPrefs.setCharacterOnHome(ctx, on)
            Toast.makeText(ctx, if (on) "Home shows the 3D character" else "Home shows the orb", Toast.LENGTH_SHORT).show()
        }
        switchRow(R.id.rowMayaShowOnBatterySaver, R.id.switchMayaShowOnBatterySaver) { on ->
            MayaUiPrefs.setShowDespiteBatterySaver(ctx, on)
            if (on && MayaUiPrefs.isBatterySaverOn()) {
                Toast.makeText(ctx, "She stays on battery saver — it costs more battery", Toast.LENGTH_SHORT).show()
            }
        }
        switchRow(R.id.rowMayaOverlayCharacter, R.id.switchMayaOverlayCharacter) { on ->
            if (on) MayaOverlayCharacter.enable(ctx) else MayaOverlayCharacter.disable(ctx)
        }

        fragment.childFragmentManager.setFragmentResultListener(CharacterSheet.REQUEST_KEY, fragment.viewLifecycleOwner) { _, _ ->
            refresh()
        }
        refresh()
    }

    /** Re-read state (call from onResume / onHiddenChanged). */
    fun refresh() {
        val v = root ?: return
        val ctx = fragment.context ?: return
        updating = true
        try {
            v.findViewById<CompoundButton>(R.id.switchMayaCharacterOnHome)?.isChecked = MayaUiPrefs.isCharacterOnHome(ctx)
            v.findViewById<CompoundButton>(R.id.switchMayaShowOnBatterySaver)?.isChecked = MayaUiPrefs.showDespiteBatterySaver(ctx)
            if (MayaUiPrefs.isOverlayEnabled(ctx) && OverlayPermission.isGranted(ctx)) MayaOverlayCharacter.restoreIfEnabled(ctx)
            v.findViewById<CompoundButton>(R.id.switchMayaOverlayCharacter)?.isChecked =
                MayaUiPrefs.isOverlayEnabled(ctx) && OverlayPermission.isGranted(ctx)
        } finally {
            updating = false
        }
        val prefs = try { JarvisApp.instance.preferences } catch (_: Throwable) { null }
        v.findViewById<TextView>(R.id.tvMayaActiveCharacter)?.text = MayaCharacters.byPrefName(prefs?.jarvisActiveCharacter).title
        v.findViewById<TextView>(R.id.tvMayaWallpaperState)?.text = when {
            MayaLiveWallpaper.isMayaMode() && MayaLiveWallpaper.isJarvisWallpaperActive(ctx) -> "On"
            MayaLiveWallpaper.isMayaMode() -> "Not set"
            else -> "Off"
        }
    }

    private fun onWallpaperRow() {
        val activity = fragment.activity ?: return
        if (MayaLiveWallpaper.isMayaMode() && MayaLiveWallpaper.isJarvisWallpaperActive(activity)) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("Maya live wallpaper is on")
                .setMessage("Your wallpaper shows the Maya stage and follows the conversation live.")
                .setPositiveButton("Keep") { _, _ -> }
                .setNegativeButton("Switch back") { _, _ ->
                    MayaLiveWallpaper.stopUsing(activity)
                    refresh()
                }
                .show()
        } else {
            MayaLiveWallpaper.use(activity)
        }
        refresh()
    }

    private fun click(id: Int, action: () -> Unit) {
        root?.findViewById<View>(id)?.setOnClickListener {
            HudSoundManager.playTapSound()
            action()
        }
    }

    private fun switchRow(rowId: Int, switchId: Int, onChange: (Boolean) -> Unit) {
        val r = root ?: return
        val sw = r.findViewById<CompoundButton>(switchId) ?: return
        r.findViewById<View>(rowId)?.setOnClickListener { sw.toggle() }
        sw.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            onChange(checked)
        }
    }
}
