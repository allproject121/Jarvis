package com.jarvis.assistant.ui.compose

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.jarvis.assistant.ui.compose.feature.chat.ChatRoute
import com.jarvis.assistant.ui.compose.feature.cloud.CloudRoute
import com.jarvis.assistant.ui.compose.feature.developer.DeveloperRoute
import com.jarvis.assistant.ui.compose.feature.home.HomeRoute
import com.jarvis.assistant.ui.compose.feature.models.ModelsRoute
import com.jarvis.assistant.ui.compose.feature.onboarding.OnboardingRoute
import com.jarvis.assistant.ui.compose.feature.profile.ProfileRoute
import com.jarvis.assistant.ui.compose.feature.prompts.PromptsRoute
import com.jarvis.assistant.ui.compose.feature.settings.ComposeUiPrefs
import com.jarvis.assistant.ui.compose.feature.settings.SettingsRoute
import com.jarvis.assistant.ui.compose.feature.setup.SetupRoute
import com.jarvis.assistant.ui.compose.feature.splash.SplashRoute
import com.jarvis.assistant.ui.compose.feature.voice.VoiceRoute
import com.jarvis.assistant.ui.compose.navigation.ComposeRoutes
import com.jarvis.assistant.ui.compose.theme.AndroLLMTheme
import com.jarvis.assistant.ui.compose.theme.ProvideUiScale
import com.jarvis.assistant.ui.home.MainActivity

/**
 * AndroLLM-style Compose UI embedded as a tab inside Jarvis' MainActivity
 * (same Activity, same task — no separate launcher entry or window).
 *
 * Back handling: the Compose NavHost gets its own [OnBackPressedDispatcher]. While this tab is
 * visible, system Back is forwarded to it; once the NavHost is at its root, Back falls through to
 * MainActivity, which returns to the Jarvis Home tab.
 */
class AndroLlmFragment : Fragment() {

    private val innerDispatcher = OnBackPressedDispatcher()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val owner = object : OnBackPressedDispatcherOwner {
            override val onBackPressedDispatcher: OnBackPressedDispatcher get() = innerDispatcher
            override val lifecycle: Lifecycle get() = viewLifecycleOwner.lifecycle
        }
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val start = arguments?.getString(ARG_START_ROUTE)
                    ?: if (com.jarvis.assistant.androllm.AndroLlmUiStyle.showSplash(requireContext())) ComposeRoutes.SPLASH
                    else ComposeRoutes.HOME
                val embedded = arguments?.getBoolean(ARG_EMBEDDED) ?: false
                CompositionLocalProvider(
                    LocalOnBackPressedDispatcherOwner provides owner,
                    com.jarvis.assistant.ui.compose.components.LocalEmbeddedInJarvis provides embedded
                ) {
                    JarvisStudioApp(startRoute = start, embedded = embedded, onExit = ::exitToJarvisHome)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activityDispatcher = requireActivity().onBackPressedDispatcher
        activityDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!isHidden && innerDispatcher.hasEnabledCallbacks()) {
                    innerDispatcher.onBackPressed()
                } else {
                    // Let MainActivity handle it (switches back to the Jarvis Home tab).
                    isEnabled = false
                    activityDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private fun exitToJarvisHome() {
        (activity as? MainActivity)?.selectTab(MainActivity.TAB_HOME)
    }

    companion object {
        const val TAG = "TAB_ANDROLLM"
        private const val ARG_START_ROUTE = "start_route"
        private const val ARG_EMBEDDED = "embedded"

        /**
         * An AndroLLM screen shown inside one of Jarvis' own tabs (chosen in Customization):
         * starts directly at [startRoute], uses Jarvis' dock instead of AndroLLM's.
         */
        fun embedded(startRoute: String) = AndroLlmFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_START_ROUTE, startRoute)
                putBoolean(ARG_EMBEDDED, true)
            }
        }
    }
}

@Composable
private fun JarvisStudioApp(startRoute: String, embedded: Boolean, onExit: () -> Unit) {
    val context = LocalContext.current
    val nav = rememberNavController()
    // Re-read UI prefs on every navigation so theme/density changes made in Settings apply live.
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val themeMode = remember(route) { ComposeUiPrefs.themeMode(context) }
    val density = remember(route) { ComposeUiPrefs.density(context) }
    val fontSize = remember(route) { ComposeUiPrefs.fontSize(context) }

    AndroLLMTheme(themeMode = themeMode) {
        ProvideUiScale(density = density, fontSize = fontSize) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                // Embedded screens keep clear of Jarvis' floating dock.
                val hostModifier = if (embedded) Modifier.padding(bottom = 104.dp) else Modifier
                androidx.compose.foundation.layout.Box(modifier = hostModifier) {
                    StudioNavHost(nav, startRoute, onExit)
                }

            }
        }
    }
}

@Composable
private fun StudioNavHost(nav: NavHostController, startRoute: String, onExit: () -> Unit) {
    val navigate: (String) -> Unit = { route ->
        when (route) {
            ComposeRoutes.CLASSIC_HUD -> onExit()
            // Entry-flow screens must not stay on the back stack.
            ComposeRoutes.HOME -> nav.navigate(route) {
                popUpTo(nav.graph.id) { inclusive = true }
                launchSingleTop = true
            }
            ComposeRoutes.ONBOARDING, ComposeRoutes.SETUP -> nav.navigate(route) {
                nav.currentDestination?.route?.let { current -> popUpTo(current) { inclusive = true } }
                launchSingleTop = true
            }
            else -> nav.navigate(route) { launchSingleTop = true }
        }
    }
    // At the root of the AndroLLM graph, "back" leaves to the Jarvis Home tab.
    val back: () -> Unit = {
        if (nav.previousBackStackEntry != null) nav.popBackStack() else onExit()
    }

    NavHost(navController = nav, startDestination = startRoute) {
        composable(ComposeRoutes.SPLASH) { SplashRoute(navigate, back) }
        composable(ComposeRoutes.ONBOARDING) { OnboardingRoute(navigate, back) }
        composable(ComposeRoutes.SETUP) { SetupRoute(navigate, back) }
        composable(ComposeRoutes.HOME) { HomeRoute(navigate, back) }
        composable(ComposeRoutes.CHAT) { ChatRoute(navigate, back) }
        composable(ComposeRoutes.MODELS) { ModelsRoute(navigate, back) }
        composable(ComposeRoutes.SETTINGS) { SettingsRoute(navigate, back) }
        composable(ComposeRoutes.VOICE) { VoiceRoute(navigate, back) }
        composable(ComposeRoutes.PROMPTS) { PromptsRoute(navigate, back) }
        composable(ComposeRoutes.DEVELOPER) { DeveloperRoute(navigate, back) }
        composable(ComposeRoutes.CLOUD) { CloudRoute(navigate, back) }
        composable(ComposeRoutes.PROFILE) { ProfileRoute(navigate, back) }
        composable(ComposeRoutes.TOOL_DEBUG) {
            com.jarvis.assistant.ui.compose.feature.tooldebug.ToolDebugRoute(navigate, back)
        }
    }
}
