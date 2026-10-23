package com.jarvis.assistant

import android.app.Application
import com.jarvis.assistant.security.SecurityManager
import com.jarvis.assistant.audio.VoskModelManager
import com.jarvis.assistant.automation.command.CommandExecutor
import com.jarvis.assistant.automation.contacts.ContactResolver
import com.jarvis.assistant.automation.phone.PhoneControlManager
import com.jarvis.assistant.automation.whatsapp.WhatsAppAssistant
import com.jarvis.assistant.automation.youtube.YouTubeAutomationManager
import com.jarvis.assistant.data.memory.MemoryDatabase
import com.jarvis.assistant.data.memory.MemoryManager
import com.jarvis.assistant.data.memory.MemoryRepository
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.data.preferences.YouTubePreferences
import com.jarvis.assistant.data.repository.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class JarvisApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var preferences: AppPreferences
        private set

    lateinit var chatRepository: ChatRepository
        private set

    lateinit var memoryDatabase: MemoryDatabase
        private set

    lateinit var memoryRepository: MemoryRepository
        private set

    lateinit var memoryManager: MemoryManager
        private set

    lateinit var youTubePreferences: YouTubePreferences
        private set

    lateinit var youTubeAutomationManager: YouTubeAutomationManager
        private set

    lateinit var contactResolver: ContactResolver
        private set

    lateinit var phoneControlManager: PhoneControlManager
        private set

    lateinit var whatsAppAssistant: WhatsAppAssistant
        private set

    lateinit var commandExecutor: CommandExecutor
        private set

    lateinit var vectorMemoryEngine: com.jarvis.assistant.ai.memory.VectorMemoryEngine
        private set

    lateinit var localAiEngine: com.jarvis.assistant.ai.local.LocalAiEngine
        private set

    lateinit var modelRouter: com.jarvis.assistant.ai.router.ModelRouter
        private set

    lateinit var offlineVoiceEngine: com.jarvis.assistant.ai.local.OfflineVoiceConversationEngine
        private set

    lateinit var macroRepository: com.jarvis.assistant.automation.macro.MacroRepository
        private set

    lateinit var macroEngine: com.jarvis.assistant.automation.macro.MacroEngine
        private set

    lateinit var termuxBridge: com.jarvis.assistant.automation.termux.TermuxBridge
        private set

    lateinit var voiceGuardian: com.jarvis.assistant.security.guardian.VoiceGuardian
        private set

    lateinit var watchmanSecurity: com.jarvis.assistant.security.guardian.WatchmanSecurityManager
        private set

    lateinit var financeClient: com.jarvis.assistant.finance.YahooFinanceClient
        private set

    lateinit var gitHubConnector: com.jarvis.assistant.connectors.GitHubConnector
        private set

    lateinit var seoAuditEngine: com.jarvis.assistant.marketing.SeoAuditEngine
        private set

    lateinit var directAiImageGenerator: com.jarvis.assistant.ai.DirectAiImageGenerator
        private set

    lateinit var smartHomeManager: com.jarvis.assistant.automation.smarthome.SmartHomeManager
        private set

    lateinit var mem0ToolBridge: com.jarvis.assistant.ai.memory.Mem0ToolBridge
        private set

    lateinit var museExperienceEngine: com.jarvis.assistant.ai.learning.MuseExperienceEngine
        private set

    lateinit var drivingModeManager: com.jarvis.assistant.core.JarvisDrivingModeManager
        private set

    override fun onCreate() {
        super.onCreate()
        SecurityManager.verify(this)
        instance = this
        preferences = AppPreferences(this)
        chatRepository = ChatRepository(preferences)

        // Memory System
        memoryDatabase = MemoryDatabase.getInstance(this)
        memoryRepository = MemoryRepository(memoryDatabase.memoryDao())
        memoryManager = MemoryManager(memoryRepository, preferences, chatRepository, appScope)

        // Unified Local AI, Semantic Memory & Academic Vault
        vectorMemoryEngine = com.jarvis.assistant.ai.memory.VectorMemoryEngine.getInstance(this, appScope)
        localAiEngine = com.jarvis.assistant.ai.local.LocalAiEngine.getInstance(this, appScope)
        modelRouter = com.jarvis.assistant.ai.router.ModelRouter.getInstance(this, appScope)
        offlineVoiceEngine = com.jarvis.assistant.ai.local.OfflineVoiceConversationEngine.getInstance(this, appScope)

        // Pre-sync existing Room database memories into vector cache and seed Academic Vault
        com.jarvis.assistant.ai.memory.AcademicKnowledgeVault.seedVaultAsync(
            context = this,
            repository = memoryRepository,
            vectorEngine = vectorMemoryEngine,
            scope = appScope
        )

        // Mem0 Persistent Memory & MUSE Self-Evolving Agent Core
        mem0ToolBridge = com.jarvis.assistant.ai.memory.Mem0ToolBridge.getInstance(this)
        museExperienceEngine = com.jarvis.assistant.ai.learning.MuseExperienceEngine.getInstance(this, appScope)
        drivingModeManager = com.jarvis.assistant.core.JarvisDrivingModeManager.getInstance(this)


        // Automation Systems
        youTubePreferences = YouTubePreferences(this)
        youTubeAutomationManager = YouTubeAutomationManager(this, youTubePreferences)
        contactResolver = ContactResolver(this)
        phoneControlManager = PhoneControlManager(this, contactResolver)
        whatsAppAssistant = WhatsAppAssistant(this, contactResolver)

        // JARVIS Protocols & Macro Engines
        macroRepository = com.jarvis.assistant.automation.macro.MacroRepository(this)
        macroEngine = com.jarvis.assistant.automation.macro.MacroEngine(this, macroRepository)
        termuxBridge = com.jarvis.assistant.automation.termux.TermuxBridge(this)
        voiceGuardian = com.jarvis.assistant.security.guardian.VoiceGuardian(this)
        watchmanSecurity = com.jarvis.assistant.security.guardian.WatchmanSecurityManager(this)
        financeClient = com.jarvis.assistant.finance.YahooFinanceClient(this)
        gitHubConnector = com.jarvis.assistant.connectors.GitHubConnector(this)
        seoAuditEngine = com.jarvis.assistant.marketing.SeoAuditEngine()
        directAiImageGenerator = com.jarvis.assistant.ai.DirectAiImageGenerator(this)
        smartHomeManager = com.jarvis.assistant.automation.smarthome.SmartHomeManager(this)

        // Command Engine
        commandExecutor = CommandExecutor(
            phoneManager = phoneControlManager,
            youtubeManager = youTubeAutomationManager,
            whatsappAssistant = whatsAppAssistant,
            memoryManager = memoryManager,
            macroEngine = macroEngine,
            termuxBridge = termuxBridge,
            voiceGuardian = voiceGuardian,
            watchmanSecurity = watchmanSecurity,
            financeClient = financeClient,
            gitHubConnector = gitHubConnector,
            seoAuditEngine = seoAuditEngine,
            directAiImageGenerator = directAiImageGenerator
        )

        VoskModelManager.init(this, CoroutineScope(Dispatchers.Default))

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var startedActivities = 0
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {
                com.jarvis.assistant.util.ThemeManager.applyTheme(activity)
            }
            override fun onActivityStarted(activity: android.app.Activity) {
                startedActivities++
                if (startedActivities == 1) _isAppForeground.value = true
            }
            override fun onActivityResumed(activity: android.app.Activity) {
                currentResumedActivity = activity
                com.jarvis.assistant.util.ThemeManager.applyTheme(activity)
            }
            override fun onActivityPaused(activity: android.app.Activity) {
                if (currentResumedActivity === activity) {
                    currentResumedActivity = null
                }
            }
            override fun onActivityStopped(activity: android.app.Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) _isAppForeground.value = false
            }
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {
                if (currentResumedActivity === activity) {
                    currentResumedActivity = null
                }
            }
        })
    }

    var currentResumedActivity: android.app.Activity? = null
        private set

    fun moveAppToBack(): Boolean {
        val act = currentResumedActivity ?: return false
        return try {
            act.moveTaskToBack(true)
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private val _isAppForeground = MutableStateFlow(false)
        val isAppForeground: StateFlow<Boolean> = _isAppForeground.asStateFlow()

        lateinit var instance: JarvisApp
            private set
    }
}

