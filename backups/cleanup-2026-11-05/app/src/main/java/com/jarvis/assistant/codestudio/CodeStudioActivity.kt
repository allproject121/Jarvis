package com.jarvis.assistant.codestudio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.LocalContext
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.codestudio.editor.CodeStudioViewModel
import com.jarvis.assistant.codestudio.ui.CodeStudioScreen
import com.jarvis.assistant.ui.compose.feature.settings.ComposeUiPrefs
import com.jarvis.assistant.ui.compose.theme.AndroLLMTheme
import com.jarvis.assistant.ui.compose.theme.ProvideUiScale
import com.jarvis.assistant.util.ThemeManager

/**
 * Jarvis Code Studio — AI coding IDE (projects, file tree, tabbed editor with highlighting,
 * find & replace, surgical AI edits, Auto-Fix, live preview, terminal, console, AI history).
 * Runs in Jarvis' task with the active Jarvis theme.
 */
class CodeStudioActivity : AppCompatActivity() {

    private val vm: CodeStudioViewModel by viewModels()
    private var appliedThemeKey = ""
    private var appliedCustomHex = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        val prefs = JarvisApp.instance.preferences
        appliedThemeKey = prefs.appTheme
        appliedCustomHex = prefs.customThemeColor
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            val context = LocalContext.current
            AndroLLMTheme(themeMode = ComposeUiPrefs.themeMode(context)) {
                ProvideUiScale(density = ComposeUiPrefs.density(context), fontSize = ComposeUiPrefs.fontSize(context)) {
                    CodeStudioScreen(vm = vm, onExit = { vm.saveAll(); finish() })
                }
            }
        }
        findViewById<android.view.View>(android.R.id.content)?.let { ThemeManager.applyDynamicBackground(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val project = intent?.getStringExtra(EXTRA_PROJECT)?.takeIf { it.isNotBlank() } ?: return
        val file = intent.getStringExtra(EXTRA_FILE)?.takeIf { it.isNotBlank() }
        vm.openProjectByName(project, file)
    }

    override fun onPause() {
        vm.saveAll()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        val prefs = JarvisApp.instance.preferences
        if (appliedThemeKey != prefs.appTheme || appliedCustomHex != prefs.customThemeColor) {
            appliedThemeKey = prefs.appTheme
            appliedCustomHex = prefs.customThemeColor
            recreate()
        }
    }

    companion object {
        const val EXTRA_PROJECT = "code_studio_project"
        const val EXTRA_FILE = "code_studio_file"

        fun intent(context: Context, projectName: String? = null, filePath: String? = null): Intent =
            Intent(context, CodeStudioActivity::class.java).apply {
                projectName?.let { putExtra(EXTRA_PROJECT, it) }
                filePath?.let { putExtra(EXTRA_FILE, it) }
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        /** Opens Code Studio; optionally a project (by name under the projects root) and a project-relative file. */
        fun open(context: Context, projectName: String? = null, filePath: String? = null) {
            context.startActivity(intent(context, projectName, filePath))
        }
    }
}
