package com.jarvis.assistant.androllm.agent.confirm

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.jarvis.assistant.automation.agent.PendingAction
import com.jarvis.assistant.automation.agent.ToolConfirmationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shows [ToolConfirmationManager] requests ("Allow call_contact?", a risky ui_run tap, ...)
 * as a small Allow / Deny card ABOVE OTHER APPS.
 *
 * Why: the in-app dialog ([com.jarvis.assistant.ui.compose.feature.settings.ToolConfirmationPrompt])
 * is only visible while a Jarvis screen is open. Screen automation runs inside OTHER apps
 * (WhatsApp, YouTube, ...), so there the dialog was never seen, the request timed out after
 * 15 s and the step was silently declined. This card appears whenever no Jarvis confirmation
 * dialog is on screen, and needs Jarvis' "Display over other apps" access (the same one the
 * Dynamic Island / avatar overlay use). Without that access nothing changes (in-app dialog only).
 */
object ConfirmationOverlay {

    private const val TAG = "ConfirmationOverlay"

    private val started = AtomicBoolean(false)

    /** Number of resumed screens that render the in-app confirmation dialog. */
    private val resumedHosts = AtomicInteger(0)

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile private var shownView: View? = null
    @Volatile private var shownId: String? = null
    private lateinit var appContext: Context

    /** A Jarvis screen that shows the in-app dialog became visible. */
    fun hostResumed() {
        resumedHosts.incrementAndGet()
        mainScope.launch { hide() }
    }

    /** That screen went to the background. */
    fun hostPaused() {
        if (resumedHosts.decrementAndGet() < 0) resumedHosts.set(0)
        mainScope.launch { ToolConfirmationManager.pending.value?.let { show(it) } }
    }

    /** Starts watching confirmation requests (idempotent, cheap). */
    fun ensureStarted(context: Context) {
        if (!started.compareAndSet(false, true)) return
        appContext = context.applicationContext
        mainScope.launch {
            ToolConfirmationManager.pending.collect { action ->
                if (action == null) hide() else show(action)
            }
        }
    }

    /** True when Jarvis may draw the card over other apps. */
    fun canShow(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    private fun show(action: PendingAction) {
        if (!::appContext.isInitialized) return
        if (resumedHosts.get() > 0) return // the in-app dialog is visible
        if (shownId == action.id && shownView != null) return
        hide()
        if (!canShow(appContext)) {
            Log.w(TAG, "No 'display over other apps' access - '${action.toolName}' can only be confirmed inside Jarvis")
            return
        }
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val view = buildCard(action)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = dp(96)
        }
        try {
            wm.addView(view, params)
            shownView = view
            shownId = action.id
        } catch (e: Exception) {
            Log.w(TAG, "Could not show confirmation overlay: ${e.message}")
        }
    }

    private fun hide() {
        val view = shownView ?: return
        shownView = null
        shownId = null
        runCatching {
            (appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.removeView(view)
        }
    }

    private fun buildCard(action: PendingAction): View {
        val ctx = appContext
        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#F2111114"))
                setStroke(dp(1), Color.parseColor("#33A78BFA"))
            }
            elevation = dp(8).toFloat()
        }
        card.addView(TextView(ctx).apply {
            text = "Jarvis needs your OK"
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        card.addView(TextView(ctx).apply {
            text = action.description
            setTextColor(Color.parseColor("#D4D4D8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(6), 0, dp(8))
        })
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(Button(ctx).apply {
            text = "Deny"
            isAllCaps = false
            setTextColor(Color.parseColor("#D4D4D8"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                ToolConfirmationManager.submitDecision(action.id, false)
                hide()
            }
        })
        buttons.addView(Button(ctx).apply {
            text = "Allow"
            isAllCaps = false
            setTextColor(Color.parseColor("#09090B"))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#A78BFA"))
            }
            setOnClickListener {
                ToolConfirmationManager.submitDecision(action.id, true)
                hide()
            }
        })
        card.addView(buttons)
        outer.addView(card)
        return outer
    }

    private fun dp(value: Int): Int =
        (value * appContext.resources.displayMetrics.density).toInt()
}
