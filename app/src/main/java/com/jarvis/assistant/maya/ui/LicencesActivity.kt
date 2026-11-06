package com.jarvis.assistant.maya.ui

import android.graphics.Typeface
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.R
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Licences of the included components": lists every file in `assets/licences/`
 * as an expandable card and shows its full text.
 */
class LicencesActivity : AppCompatActivity() {

    private val loaded = HashMap<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_maya_licences)
        val root = findViewById<View>(R.id.mayaLicencesRoot)
        ThemeManager.applyDynamicBackground(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        findViewById<View>(R.id.btnMayaLicencesBack).setOnClickListener { finish() }
        populate(findViewById(R.id.mayaLicencesList))
    }

    private fun populate(list: LinearLayout) {
        val files = try {
            assets.list(DIR)?.filter { it.isNotBlank() }?.sorted().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        list.addView(
            MayaUi.text(this, "${files.size} components · tap one to read its licence", 12.5f, 500, MayaUi.textMuted(this)),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = MayaUi.dp(this@LicencesActivity, 12f) }
        )
        if (files.isEmpty()) {
            list.addView(MayaUi.text(this, "No licence files are bundled in this build.", 14f, 400, MayaUi.textSecondary(this)))
            return
        }
        files.forEachIndexed { i, name ->
            val card = buildCard(name)
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = MayaUi.dp(this@LicencesActivity, 8f) })
            card.alpha = 0f
            card.translationY = MayaUi.dp(this, 12f).toFloat()
            card.animate().alpha(1f).translationY(0f).setStartDelay(30L * i).setDuration(240L).start()
        }
    }

    private fun buildCard(file: String): View {
        val pad = MayaUi.dp(this, 18f)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_set_card_md) // Dark Bento plate (2026-11-05 bento-all)
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(MayaUi.text(this, prettyName(file), 16f, 600))
        titles.addView(MayaUi.text(this, licenceKind(file), 13f, 400, MayaUi.textMuted(this)),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = MayaUi.dp(this@LicencesActivity, 3f) })
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        val chevron = MayaUi.text(this, "›", 22f, 500, MayaUi.textMuted(this))
        header.addView(chevron)
        card.addView(header)

        val body = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.MONOSPACE
            setTextColor(MayaUi.textSecondary(this@LicencesActivity))
            setTextIsSelectable(true)
            movementMethod = LinkMovementMethod.getInstance()
            visibility = View.GONE
            setLineSpacing(MayaUi.dp(this@LicencesActivity, 2f).toFloat(), 1f)
        }
        card.addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = MayaUi.dp(this@LicencesActivity, 12f) })

        card.setOnClickListener {
            if (body.visibility == View.VISIBLE) {
                body.visibility = View.GONE
                chevron.animate().rotation(0f).setDuration(180L).start()
                return@setOnClickListener
            }
            chevron.animate().rotation(90f).setDuration(180L).start()
            val cached = loaded[file]
            if (cached != null) {
                body.text = cached
                body.visibility = View.VISIBLE
            } else {
                body.text = "Loading…"
                body.visibility = View.VISIBLE
                lifecycleScope.launch {
                    val text = withContext(Dispatchers.IO) {
                        try {
                            assets.open("$DIR/$file").bufferedReader(Charsets.UTF_8).use { it.readText() }
                        } catch (e: Exception) {
                            "Could not read $file: ${e.message}"
                        }
                    }
                    loaded[file] = text
                    body.text = text
                }
            }
        }
        return card
    }

    private fun prettyName(file: String): String {
        val base = file.substringBeforeLast('.')
        return base.split('-', '_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                when (part.lowercase()) {
                    "mit", "cc", "by", "ofl", "tts", "cpp" -> part.uppercase()
                    "onnxruntime" -> "ONNX Runtime"
                    "threejs" -> "three.js"
                    "pockettts" -> "PocketTTS"
                    else -> part.replaceFirstChar { it.uppercase() }
                }
            }
    }

    private fun licenceKind(file: String): String {
        val f = file.lowercase()
        return when {
            "mit" in f -> "MIT License"
            "apache" in f -> "Apache License 2.0"
            "cc-by" in f -> "Creative Commons Attribution 4.0"
            "ofl" in f -> "SIL Open Font License 1.1"
            "third-party" in f -> "Third-party notices"
            "notice" in f -> "Notice"
            else -> "Licence"
        }
    }

    companion object {
        const val DIR = "licences"
    }
}
