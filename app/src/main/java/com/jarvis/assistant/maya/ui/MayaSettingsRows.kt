package com.jarvis.assistant.maya.ui

import android.content.Intent
import android.view.View
import android.widget.CompoundButton
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.jarvis.assistant.R
import com.jarvis.assistant.util.HudSoundManager
import com.jarvis.assistant.voice.overlay.OverlayPermission

/**
 * Binds the "MAYA" group in fragment_settings.xml: the eight Maya screens (opened
 * reflectively through [MayaScreens]), Characters, Character on Home, battery-saver
 * override, overlay character and the licences screen.
 */
class MayaSettingsRows(private val fragment: Fragment) {

    private var root: View? = null
    private var updating = false

    fun bind(view: View) {
        root = view
        val ctx = fragment.requireContext()

        click(R.id.rowMayaMediaStudio) { MayaScreens.open(ctx, MayaScreens.MEDIA_STUDIO, "Media studio isn't available in this build yet") }
        click(R.id.rowMayaLicences) { fragment.startActivity(Intent(ctx, LicencesActivity::class.java)) }

        switchRow(R.id.rowAvatarLooksAtYou, R.id.switchAvatarLooksAtYou) { on ->
            val act = fragment.activity
            if (on && act != null && androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(act, arrayOf(android.Manifest.permission.CAMERA), 4711)
            }
            com.jarvis.assistant.ui.home.ViewerFaceTracker.setEnabled(ctx, on)
            Toast.makeText(ctx, if (on) "Character will look at you (front camera, nothing is saved)" else "Face follow off", Toast.LENGTH_SHORT).show()
        }

        refresh()
    }

    /** Re-read state (call from onResume / onHiddenChanged). */
    fun refresh() {
        val v = root ?: return
        val ctx = fragment.context ?: return
        updating = true
        try {
            v.findViewById<CompoundButton>(R.id.switchAvatarLooksAtYou)?.isChecked = com.jarvis.assistant.ui.home.ViewerFaceTracker.isEnabled(ctx)
            // Maya overlay character removed (2026-11-05): the floating avatar is TaskProgressOverlay again.
        } finally {
            updating = false
        }
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
