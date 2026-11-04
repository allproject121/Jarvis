package com.jarvis.assistant.maya.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.jarvis.assistant.ai.provider.AiToolCall
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.dispatcher.JarvisToolDispatcher
import com.jarvis.assistant.automation.dispatcher.MayaToolRegistry
import com.jarvis.assistant.util.ActivityLauncherHelper
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
 * Instagram / Facebook reel posting and story posting: the video/photo is handed to the app's own
 * composer via ACTION_SEND, then the verb-loop (screen pack's run_agent_task, if installed) or the
 * user drives the remaining steps with Maya's exact guidance. Also hosts the YouTube skip-ads watcher.
 */
object SocialReelFlows {

    private const val TAG = "SocialReelFlows"
    const val INSTAGRAM = "com.instagram.android"
    const val FACEBOOK = "com.facebook.katana"


    private val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Runs the screen pack's run_agent_task AFTER the current tool call returns. The dispatcher holds a
     * non-reentrant mutex while this tool executes, so dispatching synchronously from here would deadlock.
     */
    private fun launchAgent(context: Context, tag: String, goal: String) {
        agentScope.launch {
            try {
                delay(2500)
                JarvisToolDispatcher.getInstance(context).dispatchTool(
                    AiToolCall("${tag}_${System.currentTimeMillis()}", "run_agent_task", JSONObject().put("goal", goal))
                )
            } catch (e: Exception) {
                Log.w(TAG, "run_agent_task dispatch failed: ${e.message}")
            }
        }
    }

    private fun installed(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) != null }.getOrDefault(false)

    private fun shareMedia(context: Context, pkg: String, uri: Uri, mime: String, text: String?): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            setPackage(pkg)
            putExtra(Intent.EXTRA_STREAM, uri)
            if (!text.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { /* best effort */ }
        return try {
            ActivityLauncherHelper.startActivitySafely(context, intent)
        } catch (e: Exception) {
            Log.e(TAG, "share to $pkg failed: ${e.message}")
            false
        }
    }

    /**
     * Shares [videoRef] into the reel composer of [platform] ("instagram" | "facebook") and returns
     * the step guidance. If the screen pack's run_agent_task exists it is dispatched with the ported
     * goal so the verb loop continues autonomously (it still asks for 'haan' before Share).
     */
    suspend fun postReel(context: Context, platform: String, videoRef: String?, caption: String?): Pair<Boolean, JSONObject> {
        val isFb = platform.lowercase(Locale.ROOT).startsWith("f")
        val pkg = if (isFb) FACEBOOK else INSTAGRAM
        val label = if (isFb) "Facebook" else "Instagram"
        if (!installed(context, pkg)) return false to JSONObject().put("message", "$label is not installed on this phone.")
        val cap = ReelGuidance.cleanCaption(caption)
        if (cap.isBlank()) return false to JSONObject().put("message", "Caption chahiye pehle — bolo kya likhna hai, phir main reel composer kholta hoon.")
        val resolved = MediaFiles.resolveToUri(context, videoRef?.ifBlank { "video" } ?: "video")
            ?: return false to JSONObject().put("message", "Video nahi mili (${videoRef ?: "latest video"}). Path ya file ka naam batao.")
        val (uri, mime) = resolved
        if (!mime.startsWith("video")) return false to JSONObject().put("message", "That file is not a video ($mime). Reels need a video file.")
        val videoName = MediaFiles.pathForUri(context, uri)?.substringAfterLast('/') ?: uri.lastPathSegment ?: "video"
        if (!shareMedia(context, pkg, uri, mime, null)) {
            return false to JSONObject().put("message", "Could not open $label's share screen.")
        }
        val guidance = if (isFb) ReelGuidance.facebook(cap, videoName) else ReelGuidance.instagram(cap, videoName)
        val result = JSONObject()
            .put("platform", label.lowercase(Locale.ROOT))
            .put("caption", cap)
            .put("video", videoName)
            .put("guidance", guidance)
            .put("posted", false)
        // Hand over to the screen verb-loop when WP "screen" is installed.
        if (MayaToolRegistry.has("run_agent_task")) {
            val goal = ReelGuidance.agentGoal(label, cap, videoName) + "\n\nScreen notes: " + guidance
            launchAgent(context, "reel", goal)
            result.put("agent_dispatched", true)
            result.put("message", "$label reel composer opened with '$videoName'. The screen agent will drive the remaining steps with caption \"$cap\"; " +
                "it tells you the caption and waits for your 'haan' before pressing Share once. Nothing is posted yet.")
            return true to result
        }
        result.put("message", guidance)
        return true to result
    }

    suspend fun postStory(context: Context, imageRef: String?, platform: String?): Pair<Boolean, JSONObject> {
        val plat = platform?.lowercase(Locale.ROOT)?.ifBlank { "instagram" } ?: "instagram"
        val pkg = ReelGuidance.platformPackage(plat)
        if (!installed(context, pkg)) return false to JSONObject().put("message", "${plat.replaceFirstChar { it.uppercase() }} is not installed.")
        val ref = imageRef?.trim().orEmpty().ifBlank { "generated" }
        val resolved = MediaFiles.resolveToUri(context, ref)
            ?: MediaFiles.resolveToUri(context, "last")
            ?: return false to JSONObject().put("message", "Story ke liye image nahi mili. Pehle generate_quote_image se banao ya path do.")
        val (uri, mime) = resolved
        val ok = if (pkg == INSTAGRAM) {
            // Instagram's dedicated story-share intent; falls back to a plain share
            val storyIntent = Intent("com.instagram.share.ADD_TO_STORY").apply {
                setDataAndType(uri, mime)
                setPackage(INSTAGRAM)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try { context.grantUriPermission(INSTAGRAM, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { /* ignore */ }
            (runCatching { ActivityLauncherHelper.startActivitySafely(context, storyIntent) }.getOrDefault(false)) ||
                shareMedia(context, pkg, uri, mime, null)
        } else {
            shareMedia(context, pkg, uri, mime, null)
        }
        if (!ok) return false to JSONObject().put("message", "Could not open the story composer.")
        val guidance = ReelGuidance.story(plat)
        val result = JSONObject().put("platform", plat).put("image", uri.toString()).put("guidance", guidance).put("posted", false)
        if (MayaToolRegistry.has("run_agent_task")) {
            launchAgent(context, "story",
                "Post the photo that is ALREADY loaded in the $plat story composer as a story. Never pick another photo. " +
                    "Tell the user what will be posted and wait for a clear 'haan' before pressing the share/post button, and press it at most once. " +
                    "Done only when the app shows the story uploading or posted. Screen notes: $guidance")
            result.put("agent_dispatched", true)
            result.put("message", "Story composer opened; the screen agent will finish the steps and ask before posting. Nothing is posted yet.")
            return true to result
        }
        result.put("message", guidance)
        return true to result
    }

    // ---------------- YouTube skip-ads watcher ----------------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var skipJob: Job? = null
    @Volatile var skipAdsEnabled: Boolean = false
        private set
    @Volatile private var adsSkipped = 0

    fun setSkipAds(enabled: Boolean): String {
        if (enabled) {
            if (JarvisAccessibilityService.instance == null) return "Accessibility service is off, so I can't tap 'Skip ad'. Enable Jarvis in Settings → Accessibility first."
            if (skipJob?.isActive == true) return "Skip-ads watcher is already running (skipped $adsSkipped so far)."
            skipAdsEnabled = true
            skipJob = scope.launch {
                while (isActive && skipAdsEnabled) {
                    try {
                        val svc = JarvisAccessibilityService.instance
                        val pkg = svc?.getActiveRootNode()?.packageName?.toString().orEmpty()
                        if (svc != null && (pkg.contains("youtube") || pkg.isEmpty())) {
                            if (svc.skipAd()) adsSkipped++
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "skipAd tick failed: ${e.message}")
                    }
                    delay(1500)
                }
            }
            return "Skip-ads watcher is on: I'll tap 'Skip ad' on YouTube whenever it appears."
        }
        skipAdsEnabled = false
        skipJob?.cancel()
        skipJob = null
        return "Skip-ads watcher stopped (skipped $adsSkipped ads this session)."
    }
}
