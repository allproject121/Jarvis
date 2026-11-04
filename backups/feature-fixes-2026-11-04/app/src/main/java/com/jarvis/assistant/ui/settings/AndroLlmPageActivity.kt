package com.jarvis.assistant.ui.settings

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ui.compose.components.LocalEmbeddedInJarvis
import com.jarvis.assistant.ui.compose.feature.cloud.CloudRoute
import com.jarvis.assistant.ui.compose.feature.developer.DeveloperRoute
import com.jarvis.assistant.ui.compose.feature.models.ModelsRoute
import com.jarvis.assistant.ui.compose.feature.prompts.PromptsRoute
import com.jarvis.assistant.ui.compose.feature.settings.AgentSettingsSection
import com.jarvis.assistant.ui.compose.feature.settings.AutomationSettingsSection
import com.jarvis.assistant.ui.compose.feature.settings.ChatSettingsSection
import com.jarvis.assistant.ui.compose.feature.settings.ComposeUiPrefs
import com.jarvis.assistant.ui.compose.feature.settings.EnginesSettingsSection
import com.jarvis.assistant.ui.compose.feature.settings.McpSection
import com.jarvis.assistant.ui.compose.feature.settings.MemorySettingsSection
import com.jarvis.assistant.ui.compose.feature.settings.ModelsSettingsSection
import com.jarvis.assistant.ui.compose.feature.settings.PermissionsSettingsSection
import com.jarvis.assistant.ui.compose.feature.settings.SettingsViewModel
import com.jarvis.assistant.ui.compose.feature.settings.VoiceSettingsSection
import com.jarvis.assistant.ui.compose.feature.tooldebug.ToolDebugRoute
import com.jarvis.assistant.ui.compose.feature.voice.VoiceRoute
import com.jarvis.assistant.ui.compose.navigation.ComposeRoutes
import com.jarvis.assistant.ui.compose.theme.AndroLLMTheme
import com.jarvis.assistant.ui.compose.theme.ProvideUiScale
import com.jarvis.assistant.ui.compose.theme.ledger
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.util.ThemeManager

/**
 * Hosts one on-device AI page (a full [ComposeRoutes] screen such as the model manager, or a
 * single settings section such as Voice) INSIDE Jarvis: same task, Jarvis theme/background,
 * a Jarvis-style top bar for sections, and never AndroLLM's own bottom navigation
 * ([LocalEmbeddedInJarvis] = true). Back returns to wherever it was opened from (Jarvis
 * Settings or Home).
 */
class AndroLlmPageActivity : AppCompatActivity() {

    private var appliedThemeKey: String = ""
    private var appliedCustomHex: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        val prefs = JarvisApp.instance.preferences
        appliedThemeKey = prefs.appTheme
        appliedCustomHex = prefs.customThemeColor
        super.onCreate(savedInstanceState)

        val route = intent.getStringExtra(EXTRA_ROUTE)
        val section = intent.getStringExtra(EXTRA_SECTION)?.let { key -> Section.entries.firstOrNull { it.name == key } }
        if (route == null && section == null) {
            finish()
            return
        }

        setContent {
            val context = LocalContext.current
            AndroLLMTheme(themeMode = ComposeUiPrefs.themeMode(context)) {
                ProvideUiScale(density = ComposeUiPrefs.density(context), fontSize = ComposeUiPrefs.fontSize(context)) {
                    CompositionLocalProvider(LocalEmbeddedInJarvis provides true) {
                        if (section != null) {
                            SectionPage(section = section, onBack = { finish() }, openRoute = ::openRoute)
                        } else {
                            RoutePage(startRoute = route!!)
                        }
                    }
                }
            }
        }
        findViewById<android.view.View>(android.R.id.content)?.let { ThemeManager.applyDynamicBackground(it) }
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

    private fun openRoute(route: String) {
        startActivity(routeIntent(this, route))
    }

    /** Leaves for one of Jarvis' own tabs (Chat / Settings / Home) instead of AndroLLM's copies. */
    private fun openJarvisTab(tab: Int) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra("TARGET_TAB", tab)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    @Composable
    private fun RoutePage(startRoute: String) {
        val nav = rememberNavController()
        val navigate: (String) -> Unit = { target ->
            when (target) {
                // AndroLLM's own Home/Settings/Chat are merged into Jarvis' tabs — go there.
                ComposeRoutes.CLASSIC_HUD, ComposeRoutes.HOME, ComposeRoutes.SPLASH,
                ComposeRoutes.ONBOARDING, ComposeRoutes.SETUP -> openJarvisTab(MainActivity.TAB_HOME)
                ComposeRoutes.CHAT -> openJarvisTab(MainActivity.TAB_CHAT)
                ComposeRoutes.SETTINGS -> openJarvisTab(MainActivity.TAB_SETTINGS)
                ComposeRoutes.MODELS, ComposeRoutes.VOICE, ComposeRoutes.PROMPTS, ComposeRoutes.DEVELOPER,
                ComposeRoutes.CLOUD, ComposeRoutes.TOOL_DEBUG -> nav.navigate(target) { launchSingleTop = true }
                // Profile has no merged Jarvis page yet: open it as a page of its own.
                else -> openRoute(target)
            }
        }
        val back: () -> Unit = { if (nav.previousBackStackEntry != null) nav.popBackStack() else finish() }

        Box(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
            NavHost(navController = nav, startDestination = startRoute) {
                composable(ComposeRoutes.MODELS) { ModelsRoute(navigate, back) }
                composable(ComposeRoutes.VOICE) { VoiceRoute(navigate, back) }
                composable(ComposeRoutes.PROMPTS) { PromptsRoute(navigate, back) }
                composable(ComposeRoutes.DEVELOPER) { DeveloperRoute(navigate, back) }
                composable(ComposeRoutes.CLOUD) { CloudRoute(navigate, back) }
                composable(ComposeRoutes.TOOL_DEBUG) { ToolDebugRoute(navigate, back) }
                composable(ComposeRoutes.PROFILE) {
                    com.jarvis.assistant.ui.compose.feature.profile.ProfileRoute(navigate, back)
                }
            }
        }
    }

    enum class Section(val title: String, val subtitle: String) {
        ENGINES("AI ENGINES", "LiteRT-LM on-device engine & cloud gateway"),
        MODELS("MODEL DEFAULTS", "Default local model, storage & download behaviour"),
        CHAT("CHAT & ATTACHMENTS", "Chat behaviour, attachments & export"),
        MEMORY("MEMORY", "Long-term memory, knowledge graph & inspector"),
        VOICE("VOICE", "Wake word, ASR, TTS, AndroLLM voice assistant & normalization"),
        AGENT("AGENT & TOOLS", "Tool graph, planner & Tool Debug"),
        AUTOMATION("SCREEN AUTOMATION", "Accessibility automation & tool confirmations"),
        PERMISSIONS("AI FEATURE ACCESS", "Permissions used by on-device AI tools"),
        MCP("MCP SERVERS", "Model Context Protocol servers, live state & imported tools")
    }

    companion object {
        const val EXTRA_ROUTE = "androllm_page_route"
        const val EXTRA_SECTION = "androllm_page_section"

        /** A full page (e.g. [ComposeRoutes.MODELS]) shown inside Jarvis. */
        fun routeIntent(context: Context, route: String): Intent =
            Intent(context, AndroLlmPageActivity::class.java).putExtra(EXTRA_ROUTE, route)

        /** One settings section shown as its own Jarvis page. */
        fun sectionIntent(context: Context, section: Section): Intent =
            Intent(context, AndroLlmPageActivity::class.java).putExtra(EXTRA_SECTION, section.name)
    }
}

@Composable
private fun SectionPage(
    section: AndroLlmPageActivity.Section,
    onBack: () -> Unit,
    openRoute: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        JarvisPageTopBar(title = section.title, subtitle = section.subtitle, onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = section.name) {
                when (section) {
                    AndroLlmPageActivity.Section.ENGINES -> EnginesSettingsSection()
                    AndroLlmPageActivity.Section.MODELS -> ModelsSettingsSection()
                    AndroLlmPageActivity.Section.CHAT -> ChatSettingsSection()
                    AndroLlmPageActivity.Section.MEMORY -> MemorySettingsSection()
                    AndroLlmPageActivity.Section.VOICE -> VoiceSettingsSection()
                    AndroLlmPageActivity.Section.AGENT ->
                        AgentSettingsSection(onOpenToolDebug = { openRoute(ComposeRoutes.TOOL_DEBUG) })
                    AndroLlmPageActivity.Section.AUTOMATION -> AutomationSettingsSection()
                    AndroLlmPageActivity.Section.PERMISSIONS -> PermissionsSettingsSection()
                    AndroLlmPageActivity.Section.MCP -> McpPageContent()
                }
            }
        }
    }
}

/** Jarvis' MCP manager, full view (live per-server state, reconnect, add/remove). */
@Composable
private fun McpPageContent() {
    val app = LocalContext.current.applicationContext as Application
    val viewModel: SettingsViewModel = viewModel { SettingsViewModel(app) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val toolCount by viewModel.mcpToolCount.collectAsStateWithLifecycle()
    McpSection(
        servers = state.mcpServers,
        toolCount = toolCount,
        bridgeEnabled = state.mcpBridgeEnabled,
        onBridgeToggle = { viewModel.setMcpBridge(it) },
        onAdd = { name, url, token -> viewModel.addMcpServer(name, url, token) },
        onRemove = { viewModel.removeMcpServer(it) },
        onToggle = { server, enabled -> viewModel.setMcpServerEnabled(server, enabled) }
    )
}

/** Mirrors Jarvis Settings' header: back button, bold tracked title, muted subtitle. */
@Composable
private fun JarvisPageTopBar(title: String, subtitle: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.ledger.deskPaper
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.ledger.deskPaper,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = MaterialTheme.ledger.deskInk,
                fontSize = 12.sp
            )
        }
    }
}
