package com.jarvis.assistant.maya.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Opens the Maya settings screens owned by the other work packages by class
 * name, so this package compiles whether or not they exist yet.
 */
object MayaScreens {
    const val VOICE_ENGINE = "com.jarvis.assistant.maya.brain.VoiceEngineActivity"
    const val CONNECTORS = "com.jarvis.assistant.maya.connectors.ConnectorsActivity"
    const val BUSINESS = "com.jarvis.assistant.maya.business.BusinessActivity"
    const val FACES = "com.jarvis.assistant.maya.guardian.FacesActivity"
    const val WIKI = "com.jarvis.assistant.maya.memory.WikiActivity"
    const val MACROS = "com.jarvis.assistant.maya.screen.MacrosActivity"
    const val HUB = "com.jarvis.assistant.maya.hub.HubActivity"
    const val MEDIA_STUDIO = "com.jarvis.assistant.maya.media.MediaStudioActivity"

    /** Screens that already exist in Jarvis and are reached the same way. */
    const val CODE_STUDIO = "com.jarvis.assistant.codestudio.CodeStudioActivity"
    const val GODS_EYE = "com.jarvis.assistant.ui.godseye.GodsEyeActivity"
    const val LICENCES = "com.jarvis.assistant.maya.ui.LicencesActivity"

    /** True when [fqcn] is compiled into this build. */
    fun exists(fqcn: String): Boolean = try {
        Class.forName(fqcn)
        true
    } catch (_: Throwable) {
        false
    }

    /**
     * Start [fqcn] if it exists, otherwise show [fallbackToast].
     * @return true when the activity was launched.
     */
    fun open(ctx: Context, fqcn: String, fallbackToast: String): Boolean {
        return try {
            val cls = Class.forName(fqcn)
            val intent = Intent(ctx, cls)
            if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            true
        } catch (_: ClassNotFoundException) {
            Toast.makeText(ctx, fallbackToast, Toast.LENGTH_SHORT).show()
            false
        } catch (t: Throwable) {
            Toast.makeText(ctx, "Could not open this screen: ${t.message ?: t.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
            false
        }
    }
}
