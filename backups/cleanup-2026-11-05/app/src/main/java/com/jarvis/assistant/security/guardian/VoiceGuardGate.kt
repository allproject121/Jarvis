package com.jarvis.assistant.security.guardian

import android.content.Context
import android.util.Log
import com.jarvis.assistant.JarvisApp
import org.json.JSONObject

/**
 * The one Voice Guard check every tool path runs (JarvisToolDispatcher and the Gemini Live
 * handlers in JarvisConversationService), so both enforce exactly the same rule.
 */
object VoiceGuardGate {

    private const val TAG = "VoiceGuardGate"

    /**
     * Returns a block message when Voice Guard is armed, [toolName] is risky, the owner's voice
     * wasn't verified in the last 60 s, and the user doesn't approve the confirmation prompt;
     * null = allowed.
     */
    suspend fun check(context: Context, toolName: String, args: JSONObject): String? {
        // Maya Listen mode (maya.guardian): non-owner voices can't run sensitive tools.
        val listenBlock = try {
            com.jarvis.assistant.maya.guardian.ListenModeController.shouldBlockTool(context, toolName, args)
        } catch (e: Throwable) {
            Log.w(TAG, "Listen mode check failed for '$toolName': ${e.message}")
            null
        }
        if (listenBlock != null) return listenBlock
        val guardian = try {
            (context.applicationContext as? JarvisApp)?.voiceGuardian
        } catch (_: UninitializedPropertyAccessException) {
            null
        } ?: return null
        val needsOwner = try { guardian.requiresOwnerFor(toolName, args) } catch (e: Exception) {
            Log.w(TAG, "Voice Guard check failed for '$toolName': ${e.message}")
            false
        }
        if (!needsOwner) return null
        if (VoiceGuardPolicy.isOwnerRecentlyVerified()) return null
        val question = VoiceGuardPolicy.confirmationQuestion(toolName)
        val approved = try {
            com.jarvis.assistant.automation.agent.ToolConfirmationManager.awaitApproval(
                com.jarvis.assistant.automation.agent.PendingAction(
                    id = "voice_guard_${toolName}_${System.currentTimeMillis()}",
                    toolName = toolName,
                    description = question,
                    speakableQuestion = question
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Voice Guard confirmation failed for '$toolName': ${e.message}")
            false
        }
        return if (approved) null else VoiceGuardPolicy.BLOCKED_MESSAGE
    }
}
