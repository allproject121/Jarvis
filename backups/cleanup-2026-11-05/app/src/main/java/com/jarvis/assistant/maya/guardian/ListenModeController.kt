package com.jarvis.assistant.maya.guardian

import android.content.Context
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.audio.OwnerVoiceManager
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.security.guardian.GuardianRole
import com.jarvis.assistant.security.guardian.VoiceGuardPolicy
import com.jarvis.assistant.security.guardian.VoiceGuardian
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

/**
 * Listen mode + God Mode runtime.
 *
 * - [shouldBlockTool] is consulted by VoiceGuardGate for EVERY tool call (dispatcher + Gemini
 *   Live handlers), so listen mode applies everywhere.
 * - [onSpeakerIdentified] is the public hook any VOICE check can call (role boss counts as a
 *   genuine owner verification, so the basic face matcher never calls it). Owner-voice matches
 *   from JarvisConversationService already arrive through VoiceGuardPolicy.markOwnerVerified.
 * - God Mode keeps risky tools confirmation-free via VoiceGuardPolicy.markAutonomousGrant (it does
 *   NOT fake a genuine owner verification) and can only be switched off after a genuine owner
 *   verification (voice) — or from the Faces screen behind the phone's own biometric/PIN.
 */
object ListenModeController {

    private const val TAG = "ListenMode"
    /** How long an onSpeakerIdentified() result describes "who is speaking now". */
    private const val SPEAKER_WINDOW_MS = 45_000L
    private const val GOD_MODE_TICK_MS = 15_000L
    private const val LOCK_COOLDOWN_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var godJob: Job? = null

    @Volatile private var warnedSpeakerKey: String? = null
    @Volatile private var warnedAtMs: Long = 0L
    @Volatile private var lastLockAtMs: Long = 0L

    const val GOD_MODE_NEEDS_VOICE =
        "God Mode needs your voice enrolled first, because your voice is the only way it can ever be switched off. Set it up in Settings → Voice enrollment, then come back."
    const val GOD_MODE_OFF_REFUSED =
        "God mode on hai — sirf boss ki awaaz se hi band hoga. Awaaz saaf nahi aayi, thoda paas aakar dobara bolo. (Or switch it off in Faces → God Mode with your phone PIN/biometric.)"

    private fun prefs(context: Context) = GuardianPrefs(context)

    fun mode(context: Context): ListenMode = ListenMode.fromKey(prefs(context).listenModeKey) ?: ListenMode.EVERYONE

    fun setMode(context: Context, mode: ListenMode) {
        val p = prefs(context)
        p.listenModeKey = mode.key
        p.listenOwnerSeenSinceArm = false
        warnedSpeakerKey = null
    }

    /**
     * Public hook: a voice (or face) check identified the current speaker. [name]/[role] null =
     * someone who is NOT enrolled. Role accepts "boss/owner/family/friend/guest/blocked".
     */
    fun onSpeakerIdentified(name: String?, role: String?) {
        val gr = when (role?.trim()?.lowercase(Locale.ROOT)) {
            "boss", "owner" -> GuardianRole.BOSS
            "family" -> GuardianRole.FAMILY
            "friend" -> GuardianRole.FRIEND
            "guest" -> GuardianRole.GUEST
            "blocked" -> GuardianRole.BLOCKED
            else -> null
        }
        VoiceGuardian.noteSpeakerIdentified(name, gr)
        if (gr == GuardianRole.BOSS) {
            VoiceGuardPolicy.markOwnerVerified()
            try { prefs(JarvisApp.instance).listenOwnerSeenSinceArm = true } catch (_: Throwable) {}
            warnedSpeakerKey = null
        }
    }

    /** Best current knowledge of who is speaking. */
    fun currentSpeaker(nowMs: Long = System.currentTimeMillis()): SpeakerKind {
        if (VoiceGuardPolicy.isOwnerGenuinelyVerified(nowMs)) return SpeakerKind.OWNER
        val at = VoiceGuardian.lastIdentifiedAtMs
        if (at > 0L && nowMs - at in 0..SPEAKER_WINDOW_MS) {
            return when (VoiceGuardian.lastIdentifiedRole) {
                null -> SpeakerKind.STRANGER
                GuardianRole.BOSS -> SpeakerKind.OWNER
                GuardianRole.FAMILY -> SpeakerKind.FAMILY
                GuardianRole.BLOCKED -> SpeakerKind.BLOCKED
                else -> SpeakerKind.OTHER_KNOWN
            }
        }
        return SpeakerKind.UNVERIFIED
    }

    /**
     * Returns a block message (spoken back by the model) or null = allowed. Locks the phone only
     * when a confirmed non-owner tries a sensitive action.
     */
    fun shouldBlockTool(context: Context, toolName: String, args: JSONObject?): String? {
        val p = prefs(context)
        val mode = mode(context)
        if (mode == ListenMode.EVERYONE && currentSpeaker() != SpeakerKind.BLOCKED) return null
        if (VoiceGuardPolicy.isOwnerGenuinelyVerified()) {
            p.listenOwnerSeenSinceArm = true
            return null
        }
        val speaker = currentSpeaker()
        val sensitive = ListenPolicy.isSensitive(toolName, VoiceGuardPolicy.isRiskyTool(toolName, args))
        val speakerKey = "${speaker.name}:${VoiceGuardian.lastIdentifiedSpeaker ?: "?"}"
        val alreadyWarned = warnedSpeakerKey == speakerKey && System.currentTimeMillis() - warnedAtMs < 10 * 60_000L
        val d = ListenPolicy.decide(mode, toolName, speaker, sensitive, p.listenOwnerSeenSinceArm, alreadyWarned)
        if (!d.block) return null

        Log.w(TAG, "Listen mode ${mode.key} blocked '$toolName' (${d.reason}, speaker=$speaker)")
        val sb = StringBuilder()
        if (d.warn) {
            warnedSpeakerKey = speakerKey
            warnedAtMs = System.currentTimeMillis()
            sb.append(if (mode == ListenMode.NOBODY_UNTIL_OWNER && !p.listenOwnerSeenSinceArm) ListenPolicy.NOBODY_TEXT else ListenPolicy.WARNING_TEXT)
            sb.append(' ')
        }
        sb.append("Listen mode is '${mode.label}': '$toolName' needs the owner's voice (${d.reason}).")
        if (d.lock) {
            val now = System.currentTimeMillis()
            if (now - lastLockAtMs > LOCK_COOLDOWN_MS) {
                lastLockAtMs = now
                val r = DevicePolicyAdmin.lockNow(context)
                sb.append(if (r.ok) " Phone locked." else " (${r.message})")
                injectEvent("Listen mode: anjaan aawaz ne '$toolName' chalane ki koshish ki — phone lock kar diya.")
            }
        }
        if (!d.warn && !d.lock) sb.append(" Do not retry.")
        return sb.toString()
    }

    // ---------------- God Mode ----------------

    fun isGodMode(context: Context): Boolean = prefs(context).godMode

    /** Owner voiceprint saved (OwnerVoiceManager) and a BOSS role exists in Voice Guardian. */
    fun hasEnrolledOwnerVoice(context: Context): Boolean {
        val app = try { JarvisApp.instance } catch (_: Throwable) { return false }
        val voiceprint = try { OwnerVoiceManager.isEnrolled(app.preferences) } catch (_: Throwable) { false }
        val bossRole = try { app.voiceGuardian.listVoices().any { it.role == GuardianRole.BOSS } } catch (_: Throwable) { false }
        return voiceprint && bossRole
    }

    data class GodModeResult(val ok: Boolean, val message: String)

    /** [viaTrustedUi] = the Faces screen already verified biometric/PIN. */
    fun setGodMode(context: Context, enabled: Boolean, viaTrustedUi: Boolean = false): GodModeResult {
        val p = prefs(context)
        if (enabled) {
            if (!hasEnrolledOwnerVoice(context)) return GodModeResult(false, GOD_MODE_NEEDS_VOICE)
            p.godMode = true
            startGodModeTicker(context)
            return GodModeResult(true,
                "GOD MODE on — I'll act without asking for confirmations. It switches off only with your voice (or your phone PIN in Faces → God Mode).")
        }
        if (!p.godMode) return GodModeResult(true, "God Mode is already off.")
        if (!viaTrustedUi && !VoiceGuardPolicy.isOwnerGenuinelyVerified()) return GodModeResult(false, GOD_MODE_OFF_REFUSED)
        p.godMode = false
        stopGodModeTicker()
        return GodModeResult(true, "God Mode off — risky actions will ask for confirmation again.")
    }

    /** Called at pack install so a persisted God Mode survives process restarts. */
    fun resumeIfNeeded(context: Context) {
        if (isGodMode(context)) startGodModeTicker(context)
    }

    @Synchronized
    private fun startGodModeTicker(context: Context) {
        if (godJob?.isActive == true) return
        val app = context.applicationContext
        godJob = scope.launch {
            while (isActive) {
                if (!GuardianPrefs(app).godMode) break
                VoiceGuardPolicy.markAutonomousGrant()
                delay(GOD_MODE_TICK_MS)
            }
        }
    }

    @Synchronized
    private fun stopGodModeTicker() {
        godJob?.cancel()
        godJob = null
    }

    // ---------------- helpers ----------------

    /** "[SYSTEM EVENT] ..." into the live session when it is on. */
    fun injectEvent(text: String) {
        try {
            if (JarvisConversationService.isSessionOn.value) {
                JarvisConversationService.sendTextMessage("[SYSTEM EVENT] $text")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "inject failed: ${e.message}")
        }
    }

    /** whose_voice answer. */
    fun describeLastSpeaker(nowMs: Long = System.currentTimeMillis()): String {
        if (VoiceGuardPolicy.isOwnerGenuinelyVerified(nowMs)) {
            val secs = (nowMs - VoiceGuardPolicy.lastGenuineOwnerVerifiedAtMs) / 1000
            return "Pakka: owner (boss) — your voice matched the enrolled voiceprint ${secs}s ago."
        }
        val at = VoiceGuardian.lastIdentifiedAtMs
        if (at > 0L) {
            val secs = (nowMs - at) / 1000
            val who = VoiceGuardian.lastIdentifiedSpeaker
            val role = VoiceGuardian.lastIdentifiedRole?.name?.lowercase(Locale.ROOT)
            return if (who == null) "Last voice I checked (${secs}s ago) was not anyone enrolled — an unknown voice."
            else "Last identified speaker: $who ($role), ${secs}s ago."
        }
        return "I haven't identified any voice yet. Jarvis only checks the owner's voiceprint (Settings → Voice enrollment); other people's voices are saved as name + role only, so I can't tell them apart by voice."
    }
}
