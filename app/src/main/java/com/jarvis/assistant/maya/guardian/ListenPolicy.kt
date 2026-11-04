package com.jarvis.assistant.maya.guardian

import java.util.Locale

/** Maya "Listen mode — whose voice reaches Maya". */
enum class ListenMode(val key: String, val label: String, val summary: String) {
    EVERYONE("everyone", "Everyone", "Anyone nearby can talk to Jarvis."),
    OWNER_ONLY("owner_only", "Owner only", "Only your voice can run sensitive actions; others get one polite warning."),
    OWNER_AND_FAMILY("owner_and_family", "Owner + family", "You and people saved as family can run sensitive actions."),
    NOBODY_UNTIL_OWNER("nobody_until_owner", "Nobody until owner", "Jarvis ignores every command until your voice is recognised.");

    companion object {
        fun fromKey(raw: String?): ListenMode? {
            val k = raw?.trim()?.lowercase(Locale.ROOT)?.replace(' ', '_')?.replace('-', '_')?.replace("+", "_and_")?.replace(Regex("_+"), "_") ?: return null
            return when (k) {
                "everyone", "all", "sab" -> EVERYONE
                "owner_only", "owner", "only_owner", "boss_only" -> OWNER_ONLY
                "owner_and_family", "owner_family", "family", "trusted", "owner_+_family" -> OWNER_AND_FAMILY
                "nobody_until_owner", "nobody", "none" -> NOBODY_UNTIL_OWNER
                else -> values().firstOrNull { it.key == k }
            }
        }
    }
}

/** Who is speaking right now, as far as Jarvis knows. */
enum class SpeakerKind {
    /** Owner voice verified recently (genuine OwnerVoiceManager match). */
    OWNER,
    FAMILY,
    /** Known but not trusted: friend / guest. */
    OTHER_KNOWN,
    BLOCKED,
    /** Voice was checked and is NOT any enrolled person. */
    STRANGER,
    /** Nothing identified the speaker recently (no voiceprint check ran). */
    UNVERIFIED;

    companion object {
        /** Maps a voice/face role string ("boss", "family", ...) to a kind; null role = stranger. */
        fun fromRole(role: String?): SpeakerKind = when (role?.trim()?.lowercase(Locale.ROOT)) {
            null, "", "unknown", "stranger" -> STRANGER
            "boss", "owner" -> OWNER
            "family" -> FAMILY
            "blocked" -> BLOCKED
            else -> OTHER_KNOWN
        }
    }
}

data class ListenDecision(
    val block: Boolean,
    /** Lock the phone (only ever when a non-owner tried something sensitive). */
    val lock: Boolean,
    /** Speak the polite one-time warning now. */
    val warn: Boolean,
    val reason: String
) {
    companion object {
        val ALLOW = ListenDecision(false, false, false, "allowed")
    }
}

/**
 * Pure listen-mode decision table (unit tested).
 *
 * Maya's rule: while a non-owner voice speaks, sensitive tools are blocked and one polite
 * warning is given; the phone is locked ONLY when that voice actually tries a sensitive action
 * ("sirf awaaz sunne par kabhi lock mat karo"). An UNVERIFIED speaker (no voiceprint check ran)
 * is never locked out — sensitive tools are just refused with an explanation.
 */
object ListenPolicy {

    const val WARNING_TEXT =
        "Maaf kijiye, main sirf apne boss ki awaaz par sensitive kaam karti hoon. Aap baat kar sakte hain, par message, call ya phone control nahi hoga."
    const val WARNING_TEXT_EN =
        "Sorry, I only take sensitive commands from my owner's voice. You can chat, but messages, calls and phone control are off."
    const val NOBODY_TEXT =
        "Listen mode is 'nobody until owner' — I'm waiting for the owner's voice before doing anything."

    /** Device/admin tools Maya treats as sensitive on top of VoiceGuardPolicy's risky list. */
    val SENSITIVE_TOOLS: Set<String> = setOf(
        "wipe_device", "factory_reset", "unlock_phone", "god_mode", "set_listen_mode", "set_voice_role",
        "enroll_voice", "remove_voice", "enroll_face", "forget_face", "set_face_lock", "set_watchman",
        "guard_stand_down", "disarm_guard", "set_touch_guard", "disable_battery_optimization",
        "open_app", "launch_app", "uninstall_app", "set_wifi", "set_bluetooth", "toggle_wifi",
        "toggle_bluetooth", "set_mobile_data", "set_airplane_mode", "share_file", "whatsapp_send_file"
    )

    /** Tools that must keep working for strangers (status / harmless / the lock itself). */
    val ALWAYS_ALLOWED: Set<String> = setOf(
        "get_listen_mode", "whose_voice", "get_lock_state", "lock_device", "get_touch_guard_status",
        "get_time", "get_weather"
    )

    fun isSensitive(toolName: String, riskyByVoiceGuard: Boolean): Boolean {
        val n = toolName.trim().lowercase(Locale.ROOT)
        return riskyByVoiceGuard || n in SENSITIVE_TOOLS
    }

    /**
     * @param sensitive the tool is risky/sensitive (see [isSensitive])
     * @param ownerSeen nobody_until_owner: owner was recognised since the mode was set
     * @param alreadyWarned the one-time warning was already given to this speaker/session
     */
    fun decide(
        mode: ListenMode,
        toolName: String,
        speaker: SpeakerKind,
        sensitive: Boolean,
        ownerSeen: Boolean,
        alreadyWarned: Boolean
    ): ListenDecision {
        val n = toolName.trim().lowercase(Locale.ROOT)
        if (n in ALWAYS_ALLOWED) return ListenDecision.ALLOW
        if (speaker == SpeakerKind.OWNER) return ListenDecision.ALLOW

        // Blocked people never run sensitive tools, whatever the mode.
        if (speaker == SpeakerKind.BLOCKED && sensitive) {
            return ListenDecision(true, true, !alreadyWarned, "blocked person tried a sensitive action")
        }

        val trusted = when (mode) {
            ListenMode.EVERYONE -> true
            ListenMode.OWNER_ONLY -> false
            ListenMode.OWNER_AND_FAMILY -> speaker == SpeakerKind.FAMILY
            ListenMode.NOBODY_UNTIL_OWNER -> ownerSeen && speaker == SpeakerKind.FAMILY
        }
        if (trusted) return ListenDecision.ALLOW

        if (mode == ListenMode.NOBODY_UNTIL_OWNER && !ownerSeen) {
            // Nothing runs until the owner is heard; still only lock on a sensitive attempt by a
            // confirmed non-owner.
            val lock = sensitive && speaker != SpeakerKind.UNVERIFIED
            return ListenDecision(true, lock, !alreadyWarned, "waiting for the owner's voice")
        }

        if (!sensitive) return ListenDecision.ALLOW

        // Sensitive action by someone who isn't trusted in this mode.
        val confirmedNonOwner = speaker != SpeakerKind.UNVERIFIED
        return ListenDecision(
            block = true,
            lock = confirmedNonOwner,
            warn = !alreadyWarned,
            reason = if (confirmedNonOwner) "non-owner voice tried a sensitive action" else "owner voice not verified"
        )
    }
}

/** Pure refusal logic for wipe_device (unit tested). */
object WipeGuard {
    const val CONFIRM_PHRASE = "WIPE MY PHONE NOW"

    data class Verdict(val allowed: Boolean, val reason: String)

    fun evaluate(adminActive: Boolean, confirmPhrase: String?, ownerGenuinelyVerified: Boolean): Verdict = when {
        !adminActive -> Verdict(false,
            "Refused: Jarvis is not a device administrator. Enable it in Faces → Device admin first (it needs the 'erase all data' policy).")
        confirmPhrase?.trim() != CONFIRM_PHRASE -> Verdict(false,
            "Refused: to erase the phone the owner must say the exact phrase \"$CONFIRM_PHRASE\".")
        !ownerGenuinelyVerified -> Verdict(false,
            "Refused: the owner's voice was not verified in the last minute. Speak again so I can recognise you, then repeat the phrase.")
        else -> Verdict(true, "All checks passed — factory reset starting.")
    }
}
