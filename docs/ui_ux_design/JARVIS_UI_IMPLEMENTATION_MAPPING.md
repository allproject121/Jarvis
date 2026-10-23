# JARVIS × MYRA AI — UI IMPLEMENTATION MAPPING & CODE ARCHITECTURE
**Document ID:** `JARVIS-UI-MAPPING-008`  
**Classification:** Technical Source Code Traceability & Implementation Blueprint  
**Package:** `com.jarvis.assistant`  

---

## 1. Complete UI-to-Code Traceability Matrix

| UI Screen / Feature Area | Kotlin Class & Package | Layout XML & ViewBinding | ViewModel / State Holder | Backend Engine / Service Link | Data Source / Persistence | Primary Event Handlers & Triggers |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Main Dashboard & HUD** | `com.jarvis.assistant.ui.home.HomeFragment` | `fragment_home.xml` (`FragmentHomeBinding`) | `MainViewModel.kt` | `GeminiLiveWebSocket.kt`, `ActionEngine.kt`, `OrbHelper.kt` | `AppPreferences.kt`, `TelemetryDataStore` | `binding.btnVoiceMic.setOnClickListener`, `OrbHelper.setOrbState()` |
| **Curved Floating Dock** | `com.jarvis.assistant.ui.widget.CurvedDockLineView` | `activity_main.xml` (`ActivityMainBinding`) | `MainViewModel.kt` | `MainActivity.kt` (NavController / ViewPager2) | N/A (Canvas Geometry State) | `binding.dockHome.setOnClickListener`, `binding.dockLiveMic.setOnClickListener` |
| **Tactical Chat Stream** | `com.jarvis.assistant.ui.chat.ChatFragment` | `fragment_chat.xml` (`FragmentChatBinding`) | `MainViewModel.kt` | `JarvisChatAdapter.kt`, `GeminiLiveWebSocket.kt` | Room DB (`ChatConversationEntity`, `ChatMessageEntity`) | `binding.btnSendMessage.setOnClickListener`, `binding.btnMagicPrompt.setOnClickListener` |
| **Stark Protocols Menu** | `com.jarvis.assistant.ui.home.StarkMenuBottomSheet` | `bottom_sheet_stark_menu.xml` (`BottomSheetStarkMenuBinding`) | N/A (Direct Intent Dispatcher) | `MainActivity.kt`, `HudSoundManager.kt` | N/A (In-Memory View Sheet) | `binding.tileSuperCore.setOnClickListener`, `binding.tileMatrix.setOnClickListener` |
| **SuperCore OS Hub** | `com.jarvis.assistant.ui.supercore.SuperCoreActivity` | `activity_super_core.xml` (`ActivitySuperCoreBinding`) | N/A (Lifecycle Scope Flow) | `JarvisAccessibilityService.kt`, `JarvisNotificationListener.kt` | `SharedPreferences` (`supercore_prefs`) | `binding.btnSetDefaultAssistant.setOnClickListener`, `binding.switchWhatsAppReader.setOnCheckedChangeListener` |
| **Jarvis Matrix Hub** | `com.jarvis.assistant.ui.matrix.JarvisMatrixActivity` | `activity_jarvis_matrix.xml` (`ActivityJarvisMatrixBinding`) | N/A (Lifecycle Scope Flow) | `TermuxBridge.kt`, `YouTubeCreatorToolkit.kt`, `SocialStoryManager.kt` | `SharedPreferences` (`matrix_agent_prefs`) | `binding.chipFilterAll.setOnClickListener`, `binding.btnRunTermuxBash.setOnClickListener` |
| **Stark HUD Studio** | `com.jarvis.assistant.ui.settings.CustomizationStudioActivity` | `activity_customization_studio.xml` (`ActivityCustomizationStudioBinding`) | N/A (Direct SharedPreferences Binder) | `ThemeManager.kt`, `HudSoundManager.kt`, `FrostedGlassEffect.kt` | `SharedPreferences` (`hud_customization_prefs`) | `binding.tabChipOrb.setOnClickListener`, `binding.btnApplyAndSave.setOnClickListener` |
| **Deep Settings Console** | `com.jarvis.assistant.ui.settings.SettingsFragment` | `fragment_settings.xml` (`FragmentSettingsBinding`) | `MainViewModel.kt` | `ThemeManager.kt`, `AppPreferences.kt` | EncryptedSharedPreferences (`jarvis_secure_prefs`) | `binding.cardProfile.setOnClickListener`, `binding.cardAiModels.setOnClickListener` |
| **God's Eye CCTV HUD** | `com.jarvis.assistant.ui.godseye.GodsEyeActivity` | `activity_gods_eye.xml` (`ActivityGodsEyeBinding`) | N/A (CameraX Lifecycle) | `GodsEyeMcpClient.kt`, `VisualTaskOrchestrator.kt` | Room DB (`GodsEyeSnapshotEntity`) | `binding.btnLockTarget.setOnClickListener`, `binding.btnThermalVision.setOnClickListener` |
| **Episodic Memory Vault** | `com.jarvis.assistant.ui.memory.MemoryActivity` | `activity_memory.xml` (`ActivityMemoryBinding`) | N/A (Coroutine Dispatcher) | `MemoryAdapter.kt`, `ActionEngine.kt` | Room DB (`MemoryDao`, `MemoryEntity`) | `binding.etSearchMemory.addTextChangedListener`, `binding.btnClearAllMemories.setOnClickListener` |
| **Voice Biometrics** | `com.jarvis.assistant.ui.voice.VoiceEnrollmentActivity` | `activity_voice_enrollment.xml` (`ActivityVoiceEnrollmentBinding`) | N/A (AudioRecord Flow) | `SpeakerVerifier.kt`, `VoiceEnrollmentManager.kt` | File System (`/data/user/0/com.jarvis.assistant/files/voice_profiles/`) | `binding.btnRecordSample.setOnClickListener`, `binding.btnCompleteEnrollment.setOnClickListener` |
| **AppLock & Security** | `com.jarvis.assistant.ui.facelock.JarvisAppLockActivity` | `activity_jarvis_app_lock.xml` (`ActivityJarvisAppLockBinding`) | N/A (BiometricPrompt) | `BiometricPrompt`, `CameraManager` (Intruder Snap) | Keystore-backed PIN storage | `binding.btnAuthenticateBiometric.setOnClickListener`, `binding.keypad0.setOnClickListener` |
| **Hands-Free Driving** | `com.jarvis.assistant.ui.settings.HandsFreeActivity` | `activity_hands_free.xml` (`ActivityHandsFreeBinding`) | N/A (Broadcast Receiver) | `ContactCaller.kt`, `AudioManager` | `SharedPreferences` (`driving_sentinel_prefs`) | `binding.switchAutoCarAudio.setOnCheckedChangeListener`, `binding.btnTestVoiceCall.setOnClickListener` |
| **Floating HUD Overlay** | `com.jarvis.assistant.ui.settings.OverlayAvatarActivity` | `activity_overlay_avatar.xml` (`ActivityOverlayAvatarBinding`) | N/A (Window Service) | `JarvisFloatingOverlayService.kt` | `SharedPreferences` (`overlay_prefs`) | `binding.switchEnableOverlay.setOnCheckedChangeListener`, `binding.seekOverlayAlpha.setOnSeekBarChangeListener` |
| **AI Model Connections** | `com.jarvis.assistant.ui.settings.ApiConnectionActivity` | `activity_api_connection.xml` (`ActivityApiConnectionBinding`) | N/A (Network Ping Coroutine)| `LocalHostLlmClient.kt`, `GeminiLiveWebSocket.kt` | EncryptedSharedPreferences (`api_credentials`) | `binding.btnTestOllamaHost.setOnClickListener`, `binding.btnSaveApiKeys.setOnClickListener` |

---

## 2. Key Lifecycle & Data Flow Orchestration

### 2.1 The Master State Stream (`MainViewModel.kt`)
The UI states across `MainActivity`, `HomeFragment`, and `ChatFragment` are coordinated through a single reactive `StateFlow` pipeline:

```kotlin
sealed class AssistantUiState {
    object Idle : AssistantUiState()
    data class Listening(val audioRms: Float) : AssistantUiState()
    data class Thinking(val prompt: String) : AssistantUiState()
    data class Speaking(val audioBuffer: ByteArray, val tokenChunk: String) : AssistantUiState()
    data class ToolExecuting(val toolName: String, val params: Map<String, Any?>) : AssistantUiState()
    data class Error(val message: String, val recoveryAction: (() -> Unit)?) : AssistantUiState()
}
```

Whenever the `AssistantUiState` updates:
1. `HomeFragment` passes the state to `OrbHelper.kt`, which triggers the WebGL shader speed, color, and halo adjustments.
2. `CurvedDockLineView` adjusts the elevated center microphone button glow and breathing animation.
3. `ChatFragment` appends or updates the active streaming chat turn bubble.

### 2.2 Global Theme Broadcast (`ThemeManager.kt`)
When changes are applied in `CustomizationStudioActivity`:
1. `ThemeManager.setCurrentTheme(context, newThemePreset)` persists the selection to `SharedPreferences`.
2. A broadcast intent `com.jarvis.assistant.THEME_CHANGED` is transmitted.
3. All registered activities (`MainActivity`, `SuperCoreActivity`, `JarvisMatrixActivity`, `SettingsActivity`) receive the broadcast and execute smooth window transition recreation without dropping active coroutines or background tasks.

---
*Verified against active Kotlin source files in `com.jarvis.assistant`.*
