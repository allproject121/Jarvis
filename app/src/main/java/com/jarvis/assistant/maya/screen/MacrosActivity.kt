package com.jarvis.assistant.maya.screen

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.automation.macro.Macro
import com.jarvis.assistant.automation.macro.MacroExecutionResult
import com.jarvis.assistant.automation.macro.MacroRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Macros & Watches" — Jarvis-themed screen for the Maya screen pack: Automation lab (type a
 * goal, run the verb loop, live step log), training status, built-in + learned macros
 * (run / steps / delete), active watches (cancel / add), event announcements and screen
 * awareness.
 */
class MacrosActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var dynamic: LinearLayout
    private lateinit var logView: TextView
    private lateinit var labStatus: TextView
    private lateinit var goalInput: EditText
    private lateinit var stepsInput: EditText
    private lateinit var hideSwitch: SwitchCompat

    private var cBg = 0
    private var cCard = 0
    private var cBorder = 0
    private var cText = 0
    private var cText2 = 0
    private var cAccent = 0
    private var cMuted = 0
    private var cOnAccent = 0

    private val expanded = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        try { com.jarvis.assistant.util.ThemeManager.applyTheme(this) } catch (_: Throwable) {}
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_maya_macros)
        val root = findViewById<View>(R.id.mayaMacrosRoot)
        try { com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(root) } catch (_: Throwable) {}
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            insets
        }
        resolveColors()
        findViewById<ImageButton>(R.id.mayaMacrosBack).setOnClickListener { finish() }
        container = findViewById(R.id.mayaMacrosContainer)
        ScreenToolPack.ensureStarted(applicationContext)

        buildLabCard()
        dynamic = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(dynamic, matchWrap())

        lifecycleScope.launch {
            VerbLoopExecutor.log.collect { lines ->
                logView.text = if (lines.isEmpty()) "(step log yahan dikhega)" else lines.takeLast(40).joinToString("\n")
            }
        }
        lifecycleScope.launch {
            VerbLoopExecutor.currentGoal.collect { g ->
                labStatus.text = if (g != null) "Chal raha hai: $g" else labStatus.text.toString().ifBlank { "Idle" }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    // ------------------------------------------------------------------ theme helpers

    private fun attrColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        if (!theme.resolveAttribute(attr, tv, true)) return fallback
        return try {
            when {
                tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT -> tv.data
                tv.resourceId != 0 -> ContextCompat.getColor(this, tv.resourceId)
                else -> fallback
            }
        } catch (_: Exception) { fallback }
    }

    private fun resolveColors() {
        cBg = attrColor(R.attr.themeBg, 0xFF0B0E14.toInt())
        cCard = attrColor(R.attr.themeCardBg, 0xFF151A23.toInt())
        cBorder = attrColor(R.attr.themeCardBorder, 0x33FFFFFF)
        cText = attrColor(R.attr.themeTextPrimary, 0xFFF1F5F9.toInt())
        cText2 = attrColor(R.attr.themeTextSecondary, 0xB3F1F5F9.toInt())
        cAccent = attrColor(R.attr.themeAccent, 0xFF38BDF8.toInt())
        cMuted = attrColor(R.attr.themeTextMuted, cText2)
        cOnAccent = attrColor(R.attr.themeOnAccent, if (isLight(cAccent)) 0xFF0B0E14.toInt() else 0xFFFFFFFF.toInt())
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun matchWrap(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = top }

    private fun card(title: String, subtitle: String? = null): LinearLayout {
        // Dark Bento card (2026-11-05 bento-all): themed 24dp card, 18-19sp semibold title, muted 13sp subtitle.
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            setBackgroundResource(R.drawable.bg_set_card)
        }
        c.addView(text(title, 18f, cText, bold = true))
        if (!subtitle.isNullOrBlank()) c.addView(text(subtitle, 13f, cMuted).apply { setPadding(0, dp(2), 0, dp(10)) })
        return c
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        typeface = try {
            val base = androidx.core.content.res.ResourcesCompat.getFont(this@MacrosActivity, R.font.inter)
            if (bold && android.os.Build.VERSION.SDK_INT >= 28) Typeface.create(base, 600, false)
            else if (bold) Typeface.create(base, Typeface.BOLD) else base
        } catch (_: Throwable) { if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
    }

    private fun button(label: String, filled: Boolean = true, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        minHeight = dp(38)
        minimumHeight = dp(38)
        minWidth = 0
        minimumWidth = 0
        stateListAnimator = null
        setPadding(dp(16), 0, dp(16), 0)
        // Bento pills: primary = accent fill + on-accent label, secondary = plate + hairline.
        backgroundTintList = null
        if (filled) {
            setBackgroundResource(R.drawable.bg_jarvis_pill_button)
            setTextColor(cOnAccent)
        } else {
            setBackgroundResource(R.drawable.bg_set_chip)
            setTextColor(cText)
        }
        setOnClickListener { onClick() }
    }

    private fun isLight(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b) > 160
    }

    private fun input(hint: String, multiLine: Boolean = false): EditText = EditText(this).apply {
        this.hint = hint
        setTextColor(cText)
        setHintTextColor(cText2)
        textSize = 14f
        setBackgroundResource(R.drawable.bg_bento_x_input)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        inputType = if (multiLine) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        if (multiLine) { minLines = 2; maxLines = 5; gravity = Gravity.TOP or Gravity.START }
    }

    private fun switchRow(label: String, checked: Boolean, sub: String? = null, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(label, 14f, cText))
        if (!sub.isNullOrBlank()) texts.addView(text(sub, 11f, cText2))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = SwitchCompat(this).apply {
            isChecked = checked
            thumbTintList = ColorStateList.valueOf(cAccent)
            setOnCheckedChangeListener { _, isOn -> onChange(isOn) }
        }
        row.addView(sw)
        return row
    }

    private fun buttonRow(vararg buttons: Button): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        buttons.forEachIndexed { i, b ->
            row.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) marginStart = dp(8)
            })
        }
        return row
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun repo(): MacroRepository = try { JarvisApp.instance.macroRepository } catch (_: Throwable) { MacroRepository(applicationContext) }

    // ------------------------------------------------------------------ automation lab (static)

    private fun buildLabCard() {
        val c = card("Automation lab", "Goal likho — ${ScreenElementSerializer.assistantName()} khud screen padh ke tap / type / scroll karega (Maya verb loop). Steps optional, ek line me ek.")
        goalInput = input("Goal, jaise: WhatsApp kholo aur Amit ko 'Hi' bhejo", multiLine = true).apply {
            setText(ScreenPrefs.lastLabGoal(this@MacrosActivity))
        }
        stepsInput = input("Steps (optional)", multiLine = true)
        c.addView(goalInput, matchWrap(dp(6)))
        c.addView(stepsInput, matchWrap(dp(4)))
        hideSwitch = SwitchCompat(this).apply {
            text = "Chalate waqt ye screen chhupa do"
            setTextColor(cText2)
            isChecked = true
            thumbTintList = ColorStateList.valueOf(cAccent)
        }
        c.addView(hideSwitch, matchWrap(dp(4)))
        val run = button("Run") { startLab() }
        val stop = button("Stop", filled = false) {
            VerbLoopExecutor.cancel()
            toast("Cancel bhej diya")
        }
        val clear = button("Clear log", filled = false) { logView.text = "" }
        c.addView(buttonRow(run, stop, clear))
        labStatus = text("Idle", 12f, cAccent, bold = true).apply { setPadding(0, dp(8), 0, dp(4)) }
        c.addView(labStatus)
        logView = text("(step log yahan dikhega)", 11f, cText2).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.bg_jarvis_row_item)
        }
        c.addView(logView, matchWrap(dp(4)))
        container.addView(c, matchWrap(dp(8)))
    }

    private fun startLab() {
        val goal = goalInput.text?.toString()?.trim().orEmpty()
        if (goal.isEmpty()) { toast("Pehle goal likho"); return }
        if (!ScreenElementSerializer.isServiceOn()) {
            toast(ScreenElementSerializer.SERVICE_OFF)
            openAccessibilitySettings()
            return
        }
        if (VerbLoopExecutor.running) { toast("Ek task pehle se chal raha hai"); return }
        ScreenPrefs.setLastLabGoal(this, goal)
        val steps = VerbLoopExecutor.splitSteps(stepsInput.text?.toString())
        val appCtx = applicationContext
        labStatus.text = "Shuru…"
        ScreenRuntime.scope.launch {
            val out = VerbLoopExecutor.run(appCtx, goal, steps)
            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) labStatus.text = "${out.status}: ${out.summary}"
            }
        }
        if (hideSwitch.isChecked) moveTaskToBack(true)
    }

    private fun openAccessibilitySettings() {
        try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ dynamic sections

    private fun render() {
        if (!::dynamic.isInitialized) return
        dynamic.removeAllViews()
        dynamic.addView(statusCard(), matchWrap(dp(12)))
        dynamic.addView(macrosCard(), matchWrap(dp(12)))
        dynamic.addView(watchesCard(), matchWrap(dp(12)))
        dynamic.addView(eventsCard(), matchWrap(dp(12)))
    }

    private fun statusCard(): View {
        val c = card("Screen access")
        val on = ScreenElementSerializer.isServiceOn()
        c.addView(text(if (on) "Accessibility service: ON" else ScreenElementSerializer.SERVICE_OFF, 13f, if (on) cAccent else attrColor(R.attr.themeDanger, 0xFFF87171.toInt()), bold = true))
        if (!on) c.addView(buttonRow(button("Accessibility settings kholo") { openAccessibilitySettings() }))
        c.addView(switchRow("Screen awareness", ScreenPrefs.screenAwareness(this),
            "Live baat-cheet me app badalne pe chupchaap '[SCREEN] Ab saamne: <app>' (max har 20 s)") { ScreenPrefs.setScreenAwareness(this, it) })
        if (MacroRecorder.isRecording()) {
            c.addView(text("● Training chal rahi hai: '${MacroRecorder.recordingName()}' — ${MacroRecorder.steps().size} steps", 13f, cAccent, bold = true).apply { setPadding(0, dp(8), 0, dp(2)) })
            val steps = MacroRecorder.format(MacroRecorder.steps())
            if (steps.isNotBlank()) c.addView(text(steps, 12f, cText2))
            c.addView(buttonRow(
                button("Save") {
                    val (_, msg) = MacroRecorder.save(this, null, null)
                    toast(msg.lineSequence().first()); render()
                },
                button("Undo last", filled = false) { toast(MacroRecorder.undoLast()); render() },
                button("Discard", filled = false) {
                    AlertDialog.Builder(this).setTitle("Training discard karein?")
                        .setMessage("${MacroRecorder.steps().size} steps chale jayenge.")
                        .setPositiveButton("Haan") { _, _ -> MacroRecorder.cancel(true); render() }
                        .setNegativeButton("Nahi", null).show()
                }
            ))
        } else {
            val name = input("Naya routine naam (jaise 'good morning')")
            c.addView(name, matchWrap(dp(8)))
            c.addView(buttonRow(button("Record (main khud karke dikhaunga)") {
                val n = name.text?.toString()?.trim().orEmpty()
                if (n.isEmpty()) { toast("Naam likho"); return@button }
                val (ok, msg) = MacroRecorder.start(this, n, emptyList())
                toast(msg.lineSequence().first())
                if (ok) moveTaskToBack(true) else render()
            }))
        }
        return c
    }

    private fun macrosCard(): View {
        val r = repo()
        val all = r.getAllMacros()
        val c = card("Routines (macros)", "Built-in + seekhe hue. Voice se: 'X wala kaam karo' (play_macro).")
        if (all.isEmpty()) c.addView(text("Abhi koi routine nahi.", 13f, cText2))
        for (m in all.sortedBy { if (r.isBuiltinMacro(it.name)) 1 else 0 }) {
            val builtin = r.isBuiltinMacro(m.name)
            // Bento inner plate per routine (8dp gaps instead of loose rows).
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setBackgroundResource(R.drawable.bg_jarvis_row_item)
            }
            row.addView(text(m.name + if (builtin) "  · built-in" else "  · learned", 14f, cText, bold = true))
            val meta = m.intent.take(90) + (if (m.params.isNotEmpty()) "\nParams: ${m.params.joinToString()}" else "") + "  (${m.steps.size} steps)"
            row.addView(text(meta, 11f, cText2))
            if (expanded.contains(m.name)) row.addView(text(MacroRecorder.format(m.steps), 12f, cText2).apply { setPadding(dp(8), dp(4), 0, dp(4)) })
            val buttons = ArrayList<Button>()
            buttons.add(button("Run") { runMacro(m) })
            buttons.add(button(if (expanded.contains(m.name)) "Hide steps" else "Steps", filled = false) {
                if (!expanded.remove(m.name)) expanded.add(m.name)
                render()
            })
            if (!builtin) buttons.add(button("Delete", filled = false) {
                AlertDialog.Builder(this).setTitle("'${m.name}' delete karein?")
                    .setPositiveButton("Delete") { _, _ -> r.deleteCustomMacro(m.name); render() }
                    .setNegativeButton("Nahi", null).show()
            })
            row.addView(buttonRow(*buttons.toTypedArray()), matchWrap(dp(6)))
            c.addView(row, matchWrap(dp(8)))
        }
        return c
    }

    private fun runMacro(m: Macro) {
        if (m.steps.any { it.tool != "wait" } && !ScreenElementSerializer.isServiceOn()) {
            toast(ScreenElementSerializer.SERVICE_OFF); openAccessibilitySettings(); return
        }
        if (m.params.isEmpty()) { launchMacro(m, emptyMap()); return }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val fields = m.params.map { p -> input(p).also { box.addView(it, matchWrap(dp(4))) } }
        AlertDialog.Builder(this).setTitle(m.name).setView(box)
            .setPositiveButton("Run") { _, _ ->
                val values = HashMap<String, String>()
                m.params.forEachIndexed { i, p -> values[p] = fields[i].text?.toString().orEmpty() }
                launchMacro(m, values)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun launchMacro(m: Macro, params: Map<String, String>) {
        val appCtx = applicationContext
        toast("'${m.name}' chal raha hai…")
        ScreenRuntime.scope.launch {
            ScreenRuntime.prepareUiActions()
            val engine = try { JarvisApp.instance.macroEngine } catch (_: Throwable) { null }
            val res = try { engine?.executeMacro(m, params) } catch (e: Exception) { MacroExecutionResult.Failure(e.message ?: "error", -1) }
            val msg = when (res) {
                is MacroExecutionResult.Success -> "'${m.name}' ho gaya"
                is MacroExecutionResult.Failure -> "'${m.name}' atak gaya: ${res.error}"
                null -> "Macro engine ready nahi hai"
            }
            withContext(Dispatchers.Main) { Toast.makeText(appCtx, msg, Toast.LENGTH_LONG).show() }
        }
        moveTaskToBack(true)
    }

    private fun watchesCard(): View {
        val c = card("Watches", "'X ho to batana' — ek baar fire hote hain, restart ke baad bhi yaad rehte hain.")
        val list = WatcherEngine.list()
        if (list.isEmpty()) c.addView(text("Koi watch active nahi.", 13f, cText2))
        for (w in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(10), dp(10))
                setBackgroundResource(R.drawable.bg_jarvis_row_item)
            }
            row.addView(text(w.describe(), 13f, cText), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            row.addView(button("Cancel", filled = false) { WatcherEngine.cancel(w.id.toString()); render() })
            c.addView(row, matchWrap(dp(8)))
        }
        // quick add
        val kinds = WatchCodec.KINDS
        var selected = kinds.first()
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MacrosActivity, android.R.layout.simple_spinner_dropdown_item, kinds)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selected = kinds[position]
                    (view as? TextView)?.setTextColor(cText)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        val value = input("Value (shabd / app / 'below 20' / 'in')")
        val reason = input("Kya batana hai (optional)")
        c.addView(text("Naya watch", 12f, cText2, bold = true).apply { setPadding(0, dp(10), 0, 0) })
        c.addView(spinner, matchWrap(dp(2)))
        c.addView(value, matchWrap(dp(2)))
        c.addView(reason, matchWrap(dp(2)))
        c.addView(buttonRow(button("Add watch") {
            val (w, msg) = WatcherEngine.add(this, selected, value.text?.toString().orEmpty(), reason.text?.toString().orEmpty())
            toast(msg)
            if (w != null) render()
        }))
        return c
    }

    private fun eventsCard(): View {
        val c = card("Phone event announcements", "Charger, battery, headphone, Bluetooth, Wi-Fi, silent, airplane — ${ScreenElementSerializer.assistantName()} khud bolega (live session me ya notification + awaaz).")
        c.addView(switchRow("Announcements ON", ScreenPrefs.eventsMaster(this)) {
            ScreenPrefs.setEventsMaster(this, it)
            WatcherEngine.start(applicationContext)
        })
        for (spec in EventTriggers.ALL) {
            c.addView(switchRow(spec.label, ScreenPrefs.isEventEnabled(this, spec.id), "“${spec.lines.first()}”") { on ->
                ScreenPrefs.setEventEnabled(this, spec.id, on)
                if (on && !ScreenPrefs.eventsMaster(this)) { ScreenPrefs.setEventsMaster(this, true); render() }
            })
        }
        val pctLabel = text("Battery low threshold: ${ScreenPrefs.batteryLowPercent(this)}%", 13f, cText).apply { setPadding(0, dp(8), 0, 0) }
        c.addView(pctLabel)
        val seek = SeekBar(this).apply {
            max = 60
            progress = (ScreenPrefs.batteryLowPercent(this@MacrosActivity) - 5).coerceIn(0, 60)
            progressTintList = ColorStateList.valueOf(cAccent)
            thumbTintList = ColorStateList.valueOf(cAccent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    pctLabel.text = "Battery low threshold: ${value + 5}%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    ScreenPrefs.setBatteryLowPercent(this@MacrosActivity, (sb?.progress ?: 15) + 5)
                }
            })
        }
        c.addView(seek, matchWrap(dp(2)))
        return c
    }
}
