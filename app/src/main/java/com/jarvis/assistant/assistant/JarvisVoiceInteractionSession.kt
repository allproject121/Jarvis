package com.jarvis.assistant.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.jarvis.assistant.background.JarvisConversationService
import java.io.ByteArrayOutputStream

/**
 * VoiceInteractionSession for Jarvis.
 * Triggered on long-press Home / Power button when Jarvis is the Default Digital Assistant.
 * Automatically captures:
 *  1. Active Screen AssistStructure (visible UI hierarchy and text)
 *  2. Active Web Browser URL (AssistContent)
 *  3. Instant Screen Screenshot Bitmap (without MediaProjection permission prompt)
 */
class JarvisVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    companion object {
        private const val TAG = "JarvisAssistSession"
        private const val MAX_SCREEN_TEXT_LENGTH = 1500
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var hasDispatchedAssistWake = false

    override fun onCreateContentView(): View {
        val root = FrameLayout(context).apply {
            setOnClickListener { hide() }
        }

        val pill = TextView(context).apply {
            text = "⚡ JARVIS • Listening…"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(56, 32, 56, 32)
            background = GradientDrawable().apply {
                cornerRadius = 64f
                setColor(Color.parseColor("#E6090D16"))
                setStroke(2, Color.parseColor("#4053E8F5"))
            }
        }

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 180
        }

        root.addView(pill, params)
        return root
    }

    override fun onShow(bundle: Bundle?, flags: Int) {
        super.onShow(bundle, flags)
        hasDispatchedAssistWake = false
        // Wake up Jarvis conversation after short delay if no assist payload arrives first
        mainHandler.postDelayed({
            dispatchAssistWakeToService(null, null)
        }, 900L)
    }

    override fun onHandleAssist(
        data: Bundle?,
        structure: AssistStructure?,
        content: AssistContent?
    ) {
        mainHandler.removeCallbacksAndMessages(null)

        val extractedText = structure?.let { extractTextFromAssistStructure(it) }
        val webUrl = content?.webUri?.toString()

        Log.i(TAG, "Assist payload captured: textLen=${extractedText?.length ?: 0}, url=$webUrl")
        dispatchAssistWakeToService(extractedText, webUrl)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        if (screenshot == null) return
        try {
            var targetBitmap = screenshot
            if (targetBitmap.width > 1280) {
                val newHeight = (targetBitmap.height.toLong() * 1280 / targetBitmap.width).toInt()
                targetBitmap = Bitmap.createScaledBitmap(targetBitmap, 1280, newHeight, true)
            }
            val stream = ByteArrayOutputStream()
            targetBitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
            val jpegBytes = stream.toByteArray()

            JarvisConversationService.lastAssistantScreenshot = jpegBytes
            JarvisConversationService.lastAssistantScreenshotTime = System.currentTimeMillis()
            Log.i(TAG, "Assist screenshot cached (${jpegBytes.size} bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed caching assist screenshot: ${e.message}")
        }
    }

    private fun dispatchAssistWakeToService(screenText: String?, screenUrl: String?) {
        if (hasDispatchedAssistWake) return
        hasDispatchedAssistWake = true

        val intent = Intent(context, JarvisConversationService::class.java).apply {
            action = JarvisConversationService.ACTION_ASSIST_WAKE
            putExtra(JarvisConversationService.EXTRA_SCREEN_TEXT, screenText)
            putExtra(JarvisConversationService.EXTRA_SCREEN_URL, screenUrl)
        }

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting JarvisConversationService from assist: ${e.message}")
        }

        mainHandler.postDelayed({ hide() }, 1400L)
    }

    private fun extractTextFromAssistStructure(structure: AssistStructure): String? {
        val collected = LinkedHashSet<String>()
        var remainingNodes = 400

        fun traverse(node: AssistStructure.ViewNode) {
            if (remainingNodes <= 0) return
            remainingNodes--

            if (node.visibility == View.VISIBLE) {
                node.text?.toString()?.trim()?.let {
                    if (it.isNotEmpty() && it.length < 500) collected.add(it)
                }
                node.contentDescription?.toString()?.trim()?.let {
                    if (it.isNotEmpty() && it.length < 500) collected.add(it)
                }
            }

            for (i in 0 until node.childCount) {
                node.getChildAt(i)?.let { traverse(it) }
            }
        }

        for (i in 0 until structure.windowNodeCount) {
            structure.getWindowNodeAt(i).rootViewNode?.let { traverse(it) }
        }

        if (collected.isEmpty()) return null
        val full = collected.joinToString(" | ")
        return if (full.length <= MAX_SCREEN_TEXT_LENGTH) full else full.take(MAX_SCREEN_TEXT_LENGTH) + "…"
    }

    override fun onBackPressed() {
        hide()
    }

    override fun onHide() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onHide()
    }
}
