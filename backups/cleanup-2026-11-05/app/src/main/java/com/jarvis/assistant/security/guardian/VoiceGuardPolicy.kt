package com.jarvis.assistant.security.guardian

import org.json.JSONObject
import java.util.Locale

/**
 * Pure rules for Voice Guard enforcement in JarvisToolDispatcher:
 * which tools are "risky" (they talk to people, spend money, run code or delete things),
 * and whether the owner's voice was verified recently enough to skip the confirmation prompt.
 */
object VoiceGuardPolicy {

    const val BLOCKED_MESSAGE = "Voice Guard is armed: only the owner can do that"

    /** How long a successful owner-voice verification lets risky tools run without a prompt. */
    const val OWNER_VERIFICATION_WINDOW_MS = 60_000L

    /** Exact tool names that need the owner while Voice Guard is armed. */
    val RISKY_TOOLS: Set<String> = setOf(
        // Calls & messages
        "send_sms", "make_call", "handle_phone_call", "call_contact", "whatsapp_call",
        "send_whatsapp_message", "reply_to_message", "send_email", "send_webhook",
        // Acting inside other apps (payments, deletes, uploads...)
        "execute_connected_app_action",
        // Code / shell execution and self-modifying tools
        "run_shell_command", "create_dynamic_tool", "edit_dynamic_tool"
    )

    /** Name prefixes that are always risky (delete_memory, delete_dynamic_tool, termux_*, pay_*...). */
    private val RISKY_PREFIXES = listOf("delete_", "termux_", "pay_", "upi_", "transfer_")

    fun isRiskyTool(name: String, args: JSONObject?): Boolean {
        val n = name.trim().lowercase(Locale.ROOT)
        if (n in RISKY_TOOLS) return true
        if (RISKY_PREFIXES.any { n.startsWith(it) }) return true
        if (n.contains("payment")) return true
        // Driving mode is harmless unless it starts auto-rejecting calls with an SMS.
        if (n == "set_driving_mode") {
            val mode = args?.optString("call_mode", "")?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return mode == "auto_reject_sms"
        }
        return false
    }

    /**
     * Last time the owner's voice was positively verified. JarvisConversationService calls
     * [markOwnerVerified] whenever its owner-voice match (or owner lease) succeeds; within the
     * window risky tools skip the prompt.
     */
    @Volatile
    var lastOwnerVerifiedAtMs: Long = 0L
        private set

    fun markOwnerVerified(nowMs: Long = System.currentTimeMillis()) {
        lastOwnerVerifiedAtMs = nowMs
        lastGenuineOwnerVerifiedAtMs = nowMs
    }

    /**
     * Last time the owner was verified by a real check (voice match / owner lease), as opposed to
     * [markAutonomousGrant] from God Mode. God Mode switch-off and wipe_device require this one.
     */
    @Volatile
    var lastGenuineOwnerVerifiedAtMs: Long = 0L
        private set

    /**
     * God Mode (maya.guardian.ListenModeController): keeps risky tools confirmation-free without
     * pretending the owner's voice was heard — [lastGenuineOwnerVerifiedAtMs] is untouched.
     */
    fun markAutonomousGrant(nowMs: Long = System.currentTimeMillis()) {
        lastOwnerVerifiedAtMs = nowMs
    }

    fun isOwnerGenuinelyVerified(
        nowMs: Long = System.currentTimeMillis(),
        windowMs: Long = OWNER_VERIFICATION_WINDOW_MS
    ): Boolean = isOwnerRecentlyVerified(nowMs, lastGenuineOwnerVerifiedAtMs, windowMs)

    fun isOwnerRecentlyVerified(
        nowMs: Long = System.currentTimeMillis(),
        lastVerifiedMs: Long = lastOwnerVerifiedAtMs,
        windowMs: Long = OWNER_VERIFICATION_WINDOW_MS
    ): Boolean = lastVerifiedMs > 0L && nowMs >= lastVerifiedMs && nowMs - lastVerifiedMs <= windowMs

    fun confirmationQuestion(toolName: String): String = "Voice Guard is armed — allow $toolName?"
}
