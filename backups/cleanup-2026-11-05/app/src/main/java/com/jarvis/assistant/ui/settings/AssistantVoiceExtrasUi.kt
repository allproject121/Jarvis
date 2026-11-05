package com.jarvis.assistant.ui.settings

import android.content.Context
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.model.MariaPersona
import com.jarvis.assistant.data.preferences.JarvisExtraPrefs
import com.jarvis.assistant.ui.island.DynamicIslandController
import com.jarvis.assistant.voice.PocketTtsEngine
import com.jarvis.assistant.voice.edge.EdgeTtsSpeaker
import com.jarvis.assistant.voice.edge.EdgeVoiceCatalog
import com.jarvis.assistant.voice.overlay.OverlayPermission

/**
 * Shared Settings / Customization Studio controls for:
 *  - Jarvis speech engine for NON-Gemini voice paths (Jarvis default or Edge neural)
 *  - Edge voice + speed picker and a test button
 *  - Dynamic Island toggle
 *
 * Gemini Live is never affected - it always speaks with its own audio.
 */
object AssistantVoiceExtrasUi {

    /** One-line summary for a Settings row subtitle. */
    fun speechSummary(context: Context): String {
        return if (JarvisExtraPrefs.isEdgeTtsSelected(context)) {
            val voice = EdgeVoiceCatalog.byId(JarvisExtraPrefs.edgeVoice(context))
            "Edge neural · ${voice?.displayName ?: JarvisExtraPrefs.edgeVoice(context)} (${voice?.language ?: "custom"})"
        } else {
            "Jarvis default (Android TTS / PocketTTS)"
        }
    }

    /** Short badge text. */
    fun speechBadge(context: Context): String =
        if (JarvisExtraPrefs.isEdgeTtsSelected(context)) "EDGE NEURAL" else "JARVIS"

    /** Main chooser: engine / voice / speed / test. [onChanged] refreshes the caller's row. */
    fun showSpeechDialog(context: Context, onChanged: () -> Unit = {}) {
        val edge = JarvisExtraPrefs.isEdgeTtsSelected(context)
        val items = arrayOf(
            "Engine: " + if (edge) "Edge neural voices" else "Jarvis default",
            "Voice: " + EdgeVoiceCatalog.labelFor(JarvisExtraPrefs.edgeVoice(context)),
            "Speed: " + rateLabel(JarvisExtraPrefs.edgeRatePercent(context)),
            "Test voice"
        )
        MaterialAlertDialogBuilder(context)
            .setTitle("Jarvis Speech Voice")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showEngineDialog(context) { onChanged(); showSpeechDialog(context, onChanged) }
                    1 -> showVoiceDialog(context) { onChanged(); showSpeechDialog(context, onChanged) }
                    2 -> showRateDialog(context) { onChanged(); showSpeechDialog(context, onChanged) }
                    3 -> {
                        testVoice(context)
                        showSpeechDialog(context, onChanged)
                    }
                }
            }
            .setNegativeButton("Done", null)
            .show()
    }

    fun showEngineDialog(context: Context, onChanged: () -> Unit = {}) {
        val labels = arrayOf(
            "Jarvis default (Android TTS / PocketTTS)",
            "Edge neural voices (online, falls back to Jarvis)"
        )
        val current = if (JarvisExtraPrefs.isEdgeTtsSelected(context)) 1 else 0
        MaterialAlertDialogBuilder(context)
            .setTitle("Speech engine (offline voice, reminders, AndroLLM voice)")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                JarvisExtraPrefs.setTtsEngine(
                    context,
                    if (which == 1) JarvisExtraPrefs.TTS_ENGINE_EDGE else JarvisExtraPrefs.TTS_ENGINE_JARVIS
                )
                dialog.dismiss()
                Toast.makeText(
                    context,
                    if (which == 1) "Edge neural voice on. Gemini Live keeps its own voice." else "Jarvis default voice on.",
                    Toast.LENGTH_SHORT
                ).show()
                onChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showVoiceDialog(context: Context, onChanged: () -> Unit = {}) {
        val voices = EdgeVoiceCatalog.VOICES
        val labels = voices.map { it.label }.toTypedArray()
        val current = voices.indexOfFirst { it.id.equals(JarvisExtraPrefs.edgeVoice(context), ignoreCase = true) }
        MaterialAlertDialogBuilder(context)
            .setTitle("Edge neural voice")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                JarvisExtraPrefs.setEdgeVoice(context, voices[which].id)
                dialog.dismiss()
                onChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showRateDialog(context: Context, onChanged: () -> Unit = {}) {
        val presets = EdgeVoiceCatalog.RATE_PRESETS
        val labels = presets.map { it.first }.toTypedArray()
        val current = presets.indexOfFirst { it.second == JarvisExtraPrefs.edgeRatePercent(context) }
        MaterialAlertDialogBuilder(context)
            .setTitle("Speaking speed")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                JarvisExtraPrefs.setEdgeRatePercent(context, presets[which].second)
                dialog.dismiss()
                onChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Speaks a sample line with the selected engine; Edge failures fall back to Jarvis TTS. */
    fun testVoice(context: Context) {
        val app = context.applicationContext
        val voice = JarvisExtraPrefs.edgeVoice(app)
        val maria = MariaPersona.isActive(runCatching { JarvisApp.instance.preferences.personality }.getOrNull())
        val sample = when {
            maria -> MariaPersona.randomGreeting()
            voice.startsWith("hi-", ignoreCase = true) -> "Namaste! Main Jarvis ki neural aawaz hoon. Aapki kya madad karoon?"
            else -> "Hello, this is Jarvis speaking with a neural voice. How can I help?"
        }
        if (!JarvisExtraPrefs.isEdgeTtsSelected(app)) {
            PocketTtsEngine.speak(sample, app)
            return
        }
        Toast.makeText(context, "Fetching Edge voice…", Toast.LENGTH_SHORT).show()
        PocketTtsEngine.getInstance(app).stop()
        EdgeTtsSpeaker.speak(
            context = app,
            text = sample,
            flush = true,
            onFailed = {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(app, "Edge voice unavailable - using Jarvis voice", Toast.LENGTH_SHORT).show()
                }
                PocketTtsEngine.getInstance(app).speakJarvisDefault(sample)
            }
        )
    }

    // ── Dynamic Island ──────────────────────────────────────────────────────

    /**
     * Turns the Dynamic Island on/off. Returns the value actually stored (asks for
     * "Display over other apps" when needed; the toggle stays on and the island appears
     * once the permission is granted).
     */
    fun setDynamicIsland(context: Context, enabled: Boolean): Boolean {
        JarvisExtraPrefs.setDynamicIslandEnabled(context, enabled)
        if (enabled && !OverlayPermission.isGranted(context)) {
            Toast.makeText(context, "Allow \"Display over other apps\" for the Dynamic Island", Toast.LENGTH_LONG).show()
            OverlayPermission.openSettings(context)
        } else if (enabled) {
            Toast.makeText(context, "Dynamic Island on - it appears while Jarvis listens, thinks or speaks", Toast.LENGTH_SHORT).show()
        }
        DynamicIslandController.refresh(context)
        return enabled
    }

    private fun rateLabel(rate: Int): String =
        EdgeVoiceCatalog.RATE_PRESETS.firstOrNull { it.second == rate }?.first
            ?: (if (rate >= 0) "+$rate%" else "$rate%")
}
