package com.jarvis.assistant.maya.guardian

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.automation.dispatcher.MayaToolRegistry
import com.jarvis.assistant.automation.dispatcher.MayaToolRegistry.ToolOutcome
import com.jarvis.assistant.security.guardian.EmergencySosManager
import com.jarvis.assistant.security.guardian.GuardianRole
import com.jarvis.assistant.security.guardian.TouchGuardManager
import com.jarvis.assistant.security.guardian.VoiceGuardPolicy
import com.jarvis.assistant.security.guardian.VoiceGuardian
import com.jarvis.assistant.security.guardian.WatchmanSecurityManager
import com.jarvis.assistant.ui.JarvisUnlockActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WP5 GUARDIAN tools (family "guardian"): faces, face lock, watchman, Touch Guard reports,
 * listen mode, voice roles, God Mode, device lock / wipe. Arming and the siren reuse
 * TouchGuardManager / WatchmanSecurityManager / EmergencySosManager; the built-in `lock`,
 * set_driving_mode and handle_phone_call are not duplicated.
 */
object GuardianToolPack {

    private const val FAMILY = "guardian"
    private fun p(type: String, description: String, enum: List<String>?): JSONObject =
        MayaToolRegistry.prop(type, description, enum)

    fun install(context: Context) {
        val app = context.applicationContext
        try { ListenModeController.resumeIfNeeded(app) } catch (_: Throwable) {}
        registerFaces()
        registerListenAndVoice()
        registerGuard()
        registerDevice()
    }

    private fun reg(
        name: String, description: String, properties: JSONObject = JSONObject(),
        required: List<String> = emptyList(), risky: Boolean = false,
        handler: suspend (Context, JSONObject) -> ToolOutcome
    ) = MayaToolRegistry.register(name, description, properties, required, FAMILY, risky, handler)

    private fun store(ctx: Context) = FaceStore(ctx.applicationContext.filesDir)
    private fun prefs(ctx: Context) = GuardianPrefs(ctx)
    private fun fmt(ms: Long) = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(ms))

    private fun openFaces(ctx: Context, enrollName: String? = null, role: String? = null) {
        try {
            ctx.startActivity(Intent(ctx, FacesActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (enrollName != null) putExtra(FacesActivity.EXTRA_ENROLL_NAME, enrollName)
                if (role != null) putExtra(FacesActivity.EXTRA_ENROLL_ROLE, role)
            })
        } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- faces
    private fun registerFaces() {
        reg("enroll_face",
            "Save a person's face print (numbers, not a photo; kept only on this phone) from the FRONT camera so Jarvis can recognise them. " +
                "Only save someone who is present and agrees. role: boss (owner) | family | friend | guest | blocked. " +
                "If the camera can't be used from the background, the Faces screen opens to finish enrolment.",
            MayaToolRegistry.props("name" to p("string", "Person's name", null), "role" to p("string", "boss|family|friend|guest|blocked", FaceStore.ROLES)),
            listOf("name"), risky = true
        ) { ctx, a ->
            val name = a.optString("name").trim()
            if (name.isEmpty()) return@reg ToolOutcome.fail("Tell me the person's name.")
            val role = a.optString("role", "").ifBlank { if (store(ctx).owners().isEmpty()) FaceStore.ROLE_BOSS else FaceStore.ROLE_GUEST }
            val msg = FaceEnrollment.enrollFromCamera(ctx, name, role)
            if (msg.startsWith("Saved")) ToolOutcome.ok(msg)
            else {
                openFaces(ctx, name, role)
                ToolOutcome.fail("$msg I opened Faces → Add person so you can finish it there.")
            }
        }

        reg("forget_face", "Delete a saved person's face print and sample images.",
            MayaToolRegistry.props("name" to p("string", "Person's name", null)), listOf("name"), risky = true
        ) { ctx, a ->
            val name = a.optString("name").trim()
            if (store(ctx).remove(name)) ToolOutcome.ok("Forgot $name's face.") else ToolOutcome.fail("No saved face named '$name'.")
        }

        reg("whose_face",
            "Who is in front of the FRONT camera right now ('mujhe pehchano', 'main kaun hoon'). Only names marked pakka are recognised; never guess a stranger's name."
        ) { ctx, _ ->
            val frame = FaceCamera.captureStillSuspend(ctx, front = true)
                ?: return@reg ToolOutcome.fail("Camera unavailable (permission missing or Jarvis is in the background).")
            val found = FaceEngine.get(ctx).identify(frame, store(ctx), prefs(ctx).faceThreshold)
            if (found.isEmpty()) return@reg ToolOutcome.ok("I don't see a face — look at the front camera.")
            val names = found.map { f -> f.person?.let { "${it.name} (${it.role}, pakka)" } ?: "unknown" }
            val ownerNote = if (store(ctx).owners().isEmpty()) " Pehle apna chehra save karo — warna har chehra anjaan lagega." else ""
            ToolOutcome.ok("I see: ${names.joinToString()}.$ownerNote", JSONObject().put("faces", JSONArray(names)))
        }

        reg("who_is_in_photo", "Recognise saved people in a photo (file path or content:// uri).",
            MayaToolRegistry.props("path" to p("string", "Photo file path or content uri", null)), listOf("path")
        ) { ctx, a -> ToolOutcome.ok(PhotoFaces.whoIsIn(ctx, a.optString("path"))) }

        reg("photos_of_person",
            "List gallery photos where a saved person was recognised (uses the index built by Faces → Find people in your photos).",
            MayaToolRegistry.props("name" to p("string", "Person's name", null), "limit" to p("integer", "Max results (default 10)", null)),
            listOf("name")
        ) { ctx, a ->
            val name = a.optString("name").trim()
            val idx = store(ctx).readGalleryIndex()
            if (idx.scannedAt == 0L) {
                if (GalleryFaceIndexer.hasPermission(ctx)) GalleryFaceIndexer.enqueue(ctx) else openFaces(ctx)
                return@reg ToolOutcome.fail("Your photos haven't been indexed yet. " +
                    if (GalleryFaceIndexer.hasPermission(ctx)) "I started the scan — ask again in a minute." else "Open Faces → Find people in your photos to allow it.")
            }
            val hits = store(ctx).photosOf(name).take(a.optInt("limit", 10).coerceIn(1, 50))
            if (hits.isEmpty()) return@reg ToolOutcome.ok("No photos of $name found in the ${idx.scannedCount} newest images.")
            ToolOutcome.ok("Photos of $name: ${hits.size} (newest ${fmt(hits.first().takenAt)}).",
                JSONObject().put("photos", JSONArray(hits.map { it.uri })))
        }

        reg("unknown_faces",
            "Intruder photos taken by Watchman / Touch Guard / failed Face Lock checks, plus gallery photos with unrecognised faces."
        ) { ctx, _ ->
            val intruders = WatchmanSecurityManager(ctx).listIntruderPhotos()
            val unknownIncidents = TouchGuardIncidents.of(ctx).list().filter { it.verdict == WatchmanFaceMonitor.Verdict.UNKNOWN.name }
            val gallery = store(ctx).photosWithUnknownFaces()
            val msg = "${intruders.size} intruder photo(s)" +
                (intruders.firstOrNull()?.let { ", latest ${fmt(it.lastModified())}" } ?: "") +
                "; ${unknownIncidents.size} incident(s) with an unknown face; ${gallery.size} gallery photo(s) with unknown faces."
            ToolOutcome.ok(msg, JSONObject().put("intruder_photos", JSONArray(intruders.take(10).map { it.absolutePath })))
        }

        reg("list_faces", "List saved people (faces) with their roles and sample counts.") { ctx, _ ->
            val people = store(ctx).listPeople()
            if (people.isEmpty()) return@reg ToolOutcome.ok("No faces saved yet. Say 'enroll my face' or open Faces → Add person.")
            ToolOutcome.ok(people.joinToString("; ") { "${it.name} (${it.role}, ${it.vectors.size} samples)" })
        }

        reg("set_face_lock",
            "Turn Face Lock on/off: the Jarvis app lock tries the owner's face for 5 s before the phone's fingerprint/PIN prompt. Basic on-device matcher — convenience, not bank-grade.",
            MayaToolRegistry.props("enabled" to p("boolean", "true = on", null)), listOf("enabled"), risky = true
        ) { ctx, a ->
            val on = a.optBoolean("enabled", true)
            if (on && store(ctx).owners().none { it.vectors.isNotEmpty() })
                return@reg ToolOutcome.fail("Save your own face first (role boss) — say 'enroll my face'.")
            prefs(ctx).faceLockEnabled = on
            ToolOutcome.ok(if (on) "Face Lock on." else "Face Lock off.")
        }
    }

    // ---------------------------------------------------------------- listen mode + voices
    private fun registerListenAndVoice() {
        reg("set_listen_mode",
            "Whose voice can make Jarvis do sensitive things. everyone | owner_only | owner_and_family | nobody_until_owner. " +
                "Strangers get one polite warning; the phone locks only if they try a sensitive action.",
            MayaToolRegistry.props("mode" to p("string", "Listen mode", ListenMode.values().map { it.key })), listOf("mode"), risky = true
        ) { ctx, a ->
            val mode = ListenMode.fromKey(a.optString("mode")) ?: return@reg ToolOutcome.fail("Unknown mode. Use everyone, owner_only, owner_and_family or nobody_until_owner.")
            ListenModeController.setMode(ctx, mode)
            ToolOutcome.ok("Listen mode: ${mode.label}. ${mode.summary}")
        }

        reg("get_listen_mode", "Current listen mode and who was last identified.") { ctx, _ ->
            val m = ListenModeController.mode(ctx)
            ToolOutcome.ok("Listen mode: ${m.label} — ${m.summary} ${ListenModeController.describeLastSpeaker()}",
                JSONObject().put("mode", m.key).put("god_mode", prefs(ctx).godMode))
        }

        reg("set_voice_role",
            "Change the role of a saved voice (boss|family|friend|guest|blocked). Creates the name+role entry if missing (no voice sample is recorded by this tool).",
            MayaToolRegistry.props("name" to p("string", "Person's name", null), "role" to p("string", "Role", FaceStore.ROLES)),
            listOf("name", "role"), risky = true
        ) { _, a ->
            val name = a.optString("name").trim()
            val role = when (FaceStore.normaliseRole(a.optString("role"))) {
                FaceStore.ROLE_BOSS -> GuardianRole.BOSS
                FaceStore.ROLE_FAMILY -> GuardianRole.FAMILY
                FaceStore.ROLE_FRIEND -> GuardianRole.FRIEND
                FaceStore.ROLE_BLOCKED -> GuardianRole.BLOCKED
                else -> GuardianRole.GUEST
            }
            val vg = JarvisApp.instance.voiceGuardian
            if (vg.setVoiceRole(name, role)) ToolOutcome.ok("$name is now ${role.name.lowercase(Locale.ROOT)}.")
            else {
                vg.enrollVoice(name, role)
                ToolOutcome.ok("Added $name as ${role.name.lowercase(Locale.ROOT)}. ${VoiceGuardian.ENROLL_REPLY_NOTE}")
            }
        }

        reg("whose_voice", "Whose voice was last recognised ('meri awaaz pehchano').") { _, _ ->
            ToolOutcome.ok(ListenModeController.describeLastSpeaker())
        }

        reg("god_mode",
            "GOD MODE: full autonomy — risky actions run without confirmation prompts. Needs the owner's voice enrolled first; " +
                "can only be switched OFF by the owner's verified voice (or in Faces with the phone PIN). Pass true ONLY when the user explicitly asks for god mode.",
            MayaToolRegistry.props("enabled" to p("boolean", "true = on", null)), listOf("enabled"), risky = true
        ) { ctx, a ->
            val r = ListenModeController.setGodMode(ctx, a.optBoolean("enabled", true))
            if (r.ok) ToolOutcome.ok(r.message) else ToolOutcome.fail(r.message)
        }
    }

    // ---------------------------------------------------------------- touch guard / watchman
    private fun registerGuard() {
        reg("get_touch_guard_status", "Is Touch Guard armed, since when, watchman mode, and how it can be switched off.") { ctx, _ ->
            val armed = TouchGuardManager.isArmed()
            val since = TouchGuardManager.armedAtMs
            val pr = prefs(ctx)
            val count = if (armed && since > 0) TouchGuardIncidents.of(ctx).since(since).size else 0
            val msg = if (armed) "Touch Guard is ARMED${if (since > 0) " since ${fmt(since)}" else ""} (${if (TouchGuardManager.isStealth()) "silent photo" else "photo + siren"}), $count incident(s) so far. " +
                (if (pr.godMode) "God Mode is on — only your voice switches it off." else "Say 'guard stand down' or use the notification's Disarm button.")
            else "Touch Guard is off. Watchman mode: ${pr.watchmanMode}."
            ToolOutcome.ok(msg, JSONObject().put("armed", armed).put("watchman_mode", pr.watchmanMode).put("god_mode", pr.godMode))
        }

        reg("touch_guard_report",
            "Did anyone touch the phone while Touch Guard was watching — count, last time, photos, recognised faces. Answer even when zero.",
            MayaToolRegistry.props("since_arm" to p("boolean", "Only incidents since the last arm (default true)", null))
        ) { ctx, a ->
            val since = if (a.optBoolean("since_arm", true)) TouchGuardManager.armedAtMs else 0L
            ToolOutcome.ok(TouchGuardIncidents.of(ctx).report(since))
        }

        reg("set_watchman",
            "Watchman: off | photo (silent intruder selfie when the phone is moved) | photo_siren (selfie + siren). Arms/disarms the existing Touch Guard; known boss/family faces make it stand down.",
            MayaToolRegistry.props("mode" to p("string", "Watchman mode", GuardianPrefs.WATCHMAN_MODES)), listOf("mode"), risky = true
        ) { ctx, a ->
            val mode = a.optString("mode").trim().lowercase(Locale.ROOT)
            if (mode !in GuardianPrefs.WATCHMAN_MODES) return@reg ToolOutcome.fail("Use off, photo or photo_siren.")
            if (mode == GuardianPrefs.WATCHMAN_OFF && prefs(ctx).godMode && !VoiceGuardPolicy.isOwnerGenuinelyVerified())
                return@reg ToolOutcome.fail(ListenModeController.GOD_MODE_OFF_REFUSED)
            val ownerHint = if (mode != GuardianPrefs.WATCHMAN_OFF && store(ctx).owners().isEmpty())
                " Pehle apna chehra save karo — warna watchman har chehre ko anjaan samjhega, tumhe bhi." else ""
            withContext(Dispatchers.Main) {
                prefs(ctx).watchmanMode = mode
                if (TouchGuardManager.isArmed()) TouchGuardManager.disarm(ctx)
                if (mode != GuardianPrefs.WATCHMAN_OFF) TouchGuardManager.arm(ctx, stealth = mode == GuardianPrefs.WATCHMAN_PHOTO)
            }
            ToolOutcome.ok(if (mode == GuardianPrefs.WATCHMAN_OFF) "Watchman off, Touch Guard disarmed."
                else "Watchman on ($mode): moving the phone takes a front-camera photo${if (mode == GuardianPrefs.WATCHMAN_PHOTO_SIREN) " and sounds the siren" else ""}.$ownerHint")
        }

        reg("guard_stand_down", "Owner says stand down: stop the siren, stop SOS siren, disarm Touch Guard / Watchman.", risky = true) { ctx, _ ->
            if (prefs(ctx).godMode && !VoiceGuardPolicy.isOwnerGenuinelyVerified())
                return@reg ToolOutcome.fail("God mode on hai. Ye boss ki awaaz nahi hai (ya pehchani nahi gayi) — stand down nahi karungi.")
            withContext(Dispatchers.Main) {
                TouchGuardManager.stopAlarm()
                if (EmergencySosManager.isSosActive()) EmergencySosManager.stopSos()
                if (TouchGuardManager.isArmed()) TouchGuardManager.disarm(ctx)
                prefs(ctx).watchmanMode = GuardianPrefs.WATCHMAN_OFF
            }
            ToolOutcome.ok("Standing down — siren off, Touch Guard disarmed. " + TouchGuardIncidents.of(ctx).report(TouchGuardManager.armedAtMs))
        }
    }

    // ---------------------------------------------------------------- device
    private fun registerDevice() {
        reg("lock_device", "Lock the phone screen now (device admin lockNow, falls back to the accessibility lock action).") { ctx, _ ->
            val r = DevicePolicyAdmin.lockNow(ctx)
            if (r.ok) ToolOutcome.ok(r.message, JSONObject().put("method", r.method)) else ToolOutcome.fail(r.message)
        }

        reg("get_lock_state", "Is the phone locked / secure / screen on, and is Jarvis device admin active.") { ctx, _ ->
            val s = DevicePolicyAdmin.lockState(ctx)
            val json = JSONObject().apply { s.forEach { (k, v) -> put(k, v) } }
            ToolOutcome.ok("Locked: ${s["keyguard_locked"]}, secure: ${s["device_secure"]}, screen on: ${s["screen_on"]}, device admin: ${s["device_admin_active"]}.", json)
        }

        reg("wipe_device",
            "FACTORY RESET the phone (erases everything). Only works when Jarvis is device admin, confirm_phrase is exactly 'WIPE MY PHONE NOW' and the owner's voice was verified in the last minute. Otherwise refuses.",
            MayaToolRegistry.props("confirm_phrase" to p("string", "Must be exactly: WIPE MY PHONE NOW", null)),
            listOf("confirm_phrase"), risky = true
        ) { ctx, a ->
            val v = WipeGuard.evaluate(DevicePolicyAdmin.isActive(ctx), a.optString("confirm_phrase"), VoiceGuardPolicy.isOwnerGenuinelyVerified())
            if (!v.allowed) return@reg ToolOutcome.fail(v.reason)
            ToolOutcome.ok(DevicePolicyAdmin.wipe(ctx))
        }

        reg("unlock_phone",
            "Wake the screen and bring up the unlock prompt over the lock screen. It cannot bypass the PIN/pattern/biometric — the user still has to unlock.",
            risky = true
        ) { ctx, _ ->
            try {
                ctx.startActivity(Intent(ctx, JarvisUnlockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ToolOutcome.ok("Screen on and unlock prompt requested. Android doesn't let apps bypass your PIN/pattern — unlock it yourself.")
            } catch (e: Exception) {
                ToolOutcome.fail("Couldn't wake the screen: ${e.message}")
            }
        }

        reg("disable_battery_optimization", "Ask Android to stop battery-optimising Jarvis so guards and the voice service keep running.") { ctx, _ ->
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true)
                return@reg ToolOutcome.ok("Battery optimisation is already off for Jarvis.")
            try {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ToolOutcome.ok("Opened the battery prompt — tap Allow.")
            } catch (e: Exception) {
                try {
                    ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    ToolOutcome.ok("Opened battery optimisation settings — choose Jarvis → Don't optimise.")
                } catch (e2: Exception) {
                    ToolOutcome.fail("Couldn't open battery settings: ${e2.message}")
                }
            }
        }
    }
}
