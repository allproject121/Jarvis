# Codex.md — Jarvis Project Master Index

> **Purpose:** Poore Jarvis project ka ek jagah reference. Koi bhi feature dhundhna ho, yahan se start karo.  
> **Last Updated:** Nov 3, 2026  
> **App Version:** 1.8.8 (Build 144) | Package: `com.jarvis.assistant`

---

## 📁 Project Structure

```
Jarvis-main/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── assets/
│   │   ├── godseye/          — Gods Eye offline web bundle
│   │   ├── playbooks/        — AI playbook .md files
│   │   └── spoonknife/       — Spoonknife module
│   ├── java/com/jarvis/assistant/
│   │   └── [see Module Map below]
│   └── res/
│       ├── layout/           — XML layouts
│       ├── values/           — colors, strings, themes
│       └── xml/network_security_config.xml
├── docs/                     — All documentation (YOU ARE HERE)
│   ├── CLAUDE.md             — Work log (kya kiya)
│   ├── codex.md              — Master index (yahi file)
│   ├── gev.md                — Gods Eye merged docs
│   ├── antigravity.md        — Antigravity/AntiGem reference
│   ├── prompt.md             — User prompts history
│   ├── task.md               — Task tracker
│   ├── guides/guide.md       — Detailed engineering guide
│   ├── guides/RESUME.md      — Resume instructions (kahan se chhoda)
│   └── [feature folders]
├── gods-eye-view-main/       — Gods Eye web source (JS/Cesium)
├── research_repos/           — Reference apps (AntiGem, Anima, etc.)
└── README.md
```

---

## 🧠 Module Map — `com.jarvis.assistant.*`

### Core AI

| Package | File | Kaam |
|---|---|---|
| `ai` | `AiTextSynthesizer.kt` | Main AI call router — Gemini REST → OpenAI → Ollama |
| `ai` | `MultiModelAiRouter.kt` | Multi-model fallback + retry |
| `ai.local` | `OfflineVoiceConversationEngine.kt` | Offline model (GGUF) |
| `ai.router` | `ModelRouter.kt` | Conversational query routing |
| `background` | `JarvisConversationService.kt` | ⛔ Gemini Live foreground service (DO NOT TOUCH) |
| `network` | `GeminiLiveWebSocket.kt` | ⛔ Gemini Live WebSocket (DO NOT TOUCH) |

### Automation & Tools

| Package | File | Kaam |
|---|---|---|
| `automation.dispatcher` | `JarvisToolDispatcher.kt` | Saare AI tools yahan register hain |
| `automation.accessibility` | `JarvisAccessibilityService.kt` | Android UI automation |
| `automation.dynamic` | `DynamicScriptExecutor.kt` | Dynamic script execution |
| `automation.dynamic` | `DynamicTool.kt`, `DynamicToolRegistry.kt` | Runtime tool system |
| `automation.dynamic` | `SelfHealingAgent.kt` | Auto-recovery agent |
| `automation.macro` | `MacroEngine.kt` | Macro recording/playback |
| `automation.tools` | `JarvisWebInfoTools.kt` | get_news, get_public_ip, wikipedia_search |

### Code Studio (BYPASS IDE Features)

| Package | File | Kaam |
|---|---|---|
| `codestudio` | `CodeStudioActivity.kt` | IDE main activity |
| `codestudio` | `CodeStudioShared.kt` | Shared contract (paths, port) |
| `codestudio` | `CodeStudioTools.kt` | Code Studio MCP tools |
| `codestudio.ai` | `AiEditEngine.kt` | Surgical AI code edits |
| `codestudio.ai` | `AutoFixEngine.kt` | Auto-fix loop |
| `codestudio.ai` | `CodeStudioAi.kt` | AI calls for code studio |
| `codestudio.ai` | `CodeValidator.kt` | Code validation |
| `codestudio.editor` | `CodeEditor.kt` | Syntax-highlighted editor |
| `codestudio.editor` | `CodeStudioViewModel.kt` | Editor state |
| `codestudio.editor` | `SyntaxHighlighter.kt` | Multi-language syntax |
| `codestudio.generator` | `ProjectGenerator.kt` | AI project generator |
| `codestudio.generator` | `OfflineTemplates.kt` | Offline starter templates |
| `codestudio.generator` | `ProjectFileFormat.kt` | File format parser |
| `codestudio.preview` | `LivePreviewPanel.kt` | Live preview Composable |
| `codestudio.preview` | `PreviewServer.kt` | Local HTTP server (port 8080) |
| `codestudio.terminal` | `TerminalPanel.kt` | Interactive terminal Composable |
| `codestudio.terminal` | `TerminalSession.kt` | PTY session manager |
| `codestudio.terminal` | `ShellRunner.kt` | Shell command runner + ShellResult |
| `codestudio.ui` | `CodeStudioScreen.kt` | Full Compose UI |
| `codestudio.workspace` | `CodeStudioWorkspace.kt` | Project workspace ops |

### AndroLLM Integration

| Package | File | Kaam |
|---|---|---|
| `androllm` | `AndroLlmShared.kt` | Shared constants |
| `androllm.memory` | `AndroMemoryPipeline.kt` | AI memory pipeline |
| `androllm.voice.assistant` | `VoiceAssistant.kt` | Voice assistant features |

### Voice & TTS

| Package | File | Kaam |
|---|---|---|
| `voice.edge` | `EdgeTtsClient.kt` | Microsoft Edge TTS WebSocket client |
| `voice.edge` | `EdgeTtsSpeaker.kt` | Edge TTS playback |
| `voice.edge` | `EdgeVoiceCatalog.kt` | Voice list (Hindi, English) |
| `voice` | `PocketTtsEngine.kt` | Offline TTS fallback |

### UI & Screens

| Package | File | Kaam |
|---|---|---|
| `ui.home` | `MainActivity.kt` | Main activity |
| `ui.home` | `HomeFragment.kt` | Home screen |
| `ui.home` | `LocalAiDashboard.kt` | Local AI dashboard |
| `ui.home` | `StarkMenuBottomSheet.kt` | Stark menu |
| `ui.chat` | `ChatFragment.kt` | Chat screen (mode chips, AI modes) |
| `ui.settings` | `SettingsFragment.kt` | Settings screen |
| `ui.settings` | `CustomizationStudioActivity.kt` | Customization studio |
| `ui.island` | `DynamicIslandOverlay.kt` | Dynamic Island capsule |
| `ui.island` | `DynamicIslandController.kt` | Island state controller |
| `ui.matrix` | `JarvisMatrixActivity.kt` | Matrix view |
| `ui.memory` | `MemoryActivity.kt` | Memory viewer |
| `ui.agent` | `TaskProgressOverlay.kt` | Agent task progress |
| `ui.bypasside` | `BypassIdeActivity.kt` | Legacy bypass IDE activity |

### Data & Preferences

| Package | File | Kaam |
|---|---|---|
| `data.preferences` | `AppPreferences.kt` | Main preferences (SharedPreferences) |
| `data.preferences` | `JarvisExtraPrefs.kt` | Extra preference fields |
| `data.model` | `MariaPersona.kt` | Maria Hinglish persona data |
| `data.model` | `GeminiLiveModels.kt` | Gemini Live model configs |
| `data.repository` | `ChatRepository.kt` | Chat history repository |

### God's Eye View

| Package/File | Kaam |
|---|---|
| `ui.godseye.GodsEyeActivity.kt` | Gods Eye WebView host |
| `mcp.GodsEyeMcpClient.kt` | Jarvis ↔ Gods Eye bridge |
| `assets/godseye/` | Offline Cesium bundle |
| `docs/gev.md` | Merged Gods Eye documentation |

### Other Features

| Package | File | Kaam |
|---|---|---|
| `connectors` | `AppConnectorsManager.kt` | App connector system |
| `connectors` | `UniversalAppExecutor.kt` | Universal app actions |
| `vision` | `VisualTaskOrchestrator.kt` | Screen capture + vision AI |
| `util` | `ThemeManager.kt` | Theme management (applyTheme) |
| `util` | `HudAnimationHelper.kt` | HUD animations |
| `util` | `PromptGenerator.kt` | AI prompt templates |
| `security` | `*` | Security features |
| `finance` | `*` | Finance features |
| `social` | `*` | Social features |

---

## 🎯 Feature → Code Mapping

| Feature | Entry Point | Key Files |
|---|---|---|
| **God's Eye 3D Globe** | GodsEyeActivity | `assets/godseye/`, `GodsEyeMcpClient.kt` |
| **Code Studio IDE** | CodeStudioActivity | `codestudio/` package |
| **Live Preview** | CodeStudioScreen | `preview/LivePreviewPanel.kt`, `PreviewServer.kt` |
| **Terminal** | CodeStudioScreen | `terminal/TerminalPanel.kt` |
| **AI Code Generator** | CodeStudio "New with AI" | `generator/ProjectGenerator.kt` |
| **Maria Persona** | Settings → Persona | `data/model/MariaPersona.kt` |
| **Edge TTS** | Settings → Voice | `voice/edge/EdgeTtsClient.kt` |
| **Dynamic Island** | Settings → Customize | `ui/island/DynamicIslandOverlay.kt` |
| **AI Modes (Chat)** | ChatFragment chips | `ui/chat/ChatFragment.kt` |
| **AI Tools (News etc.)** | JarvisToolDispatcher | `automation/tools/JarvisWebInfoTools.kt` |
| **Gemini Live Voice** | ⛔ PROTECTED | `background/JarvisConversationService.kt` |
| **Accessibility Automation** | JarvisAccessibilityService | `automation/accessibility/` |
| **Screen Capture Vision** | VisualTaskOrchestrator | `vision/VisualTaskOrchestrator.kt` |

---

## 📚 Documentation Index

| File | Kya hai |
|---|---|
| `docs/CLAUDE.md` | Work log — kya kiya kab kiya |
| `docs/codex.md` | Master index — yahi file |
| `docs/gev.md` | Gods Eye — saare docs merged |
| `docs/antigravity.md` | Antigravity agent reference |
| `docs/prompt.md` | User prompts history (numbered) |
| `docs/task.md` | Current tasks / todo |
| `docs/guides/guide.md` | Detailed engineering guide |
| `docs/guides/RESUME.md` | Resume instructions |
| `docs/phase_reports/PHASE_PROGRESS.md` | Phase completion status |
| `docs/ui_ux_design/JARVIS_MASTER_DESIGN_SYSTEM.md` | Design system |
| `docs/ui_ux_design/JARVIS_MASTER_NAVIGATION_TREE.md` | Navigation structure |
| `fix they problem.md` | Known issues list |
| `gods eye problem.md` | Gods Eye known issues |

---

## ⚡ Common Tasks Quick Reference

### AI call karna (Code mein)
```kotlin
// Text AI (preferred)
AiTextSynthesizer.synthesizeText(context, prompt, callback)
// OR
ModelRouter.routeConversationalQuery(prompt)
// NEVER: GeminiLiveWebSocket ya JarvisConversationService
```

### Naya Tool add karna (JarvisToolDispatcher)
1. Schema add karo (JSON descriptor)
2. `when` case add karo handler mein
3. Tool test karo

### Naya Setting add karna
1. `AppPreferences.kt` mein field add karo
2. `SettingsFragment.kt` ya `CustomizationStudioActivity.kt` mein row add karo
3. Feature ko preference se wire karo

### Theme apply karna
```kotlin
ThemeManager.applyTheme(this) // Activity ke onCreate mein, setContent se pehle
// Compose mein:
AndroLLMTheme { ProvideUiScale { ... } }
```

### Build karna (Termux pe)
```bash
cd ~/storage/document/Jarvis  # ya Jarvis root folder
./gradlew termuxDebug  # standard build NAHI, sirf termuxBuild
```

---

## 🔗 Research Repos (Reference Only — Copy nahi karna)

| Repo | Location | Purpose |
|---|---|---|
| **AntiGem** | `research_repos/antigem/` | Antigravity agent + Claude Code on Android |
| **Anima** | `research_repos/Anima/` | 3D avatar animation |
| **Anime AI** | `research_repos/anime-ai/` | Anime avatar + Gemini Live |
| **Anime Avatar** | `research_repos/anime-avatar/` | 3D avatar system |
| **God's Eye** | `gods-eye-view-main/` | Original web 3D globe |
