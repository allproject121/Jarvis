# CLAUDE.md — Jarvis Project Work Log

> **Purpose:** Har kaam jo Claude ne is project mein kiya, woh yahan likha jaata hai.  
> **Rule:** Jab bhi koi naya kaam karo, sabse pehle yahan update karo. Dusra AI bhi yahi padh ke project ko samjhe.  
> **Last Updated:** Nov 3, 2026

---

## ⚠️ MANDATORY RULES (Padhna zaroori hai)

1. **Gradle build (PC par):** `gradlew :app:assembleDebug --console=plain` (~5–8 min). JDK path `gradle.properties` mein set hai (`org.gradle.java.home` → jdk-17). Release build nahi chalana. Termux par build karna ho to `termuxBuild` variant.
2. **Git = touch mat karo** — koi commit, push, pull, stash, rebase nahi.
3. **Gemini Live protect karo** — `GeminiLiveWebSocket.kt` aur `JarvisConversationService.kt` ko kabhi mat chhuo.
4. **Concurrent builds nahi** — pehle check karo koi build chal toh nahi raha.
5. **AndroLLM = merge, not addon** — koi alag tab, alag app, alag launcher nahi. Sab Jarvis ke screens mein merge karo.
6. **Kaam batao pehle** — seedha code mat chhuo. Pehle explain karo kya change hoga, user "continue" bole tab karo.
7. **Antigravity (agy) se sikhna** — isko feature source ki tarah use karo, copy-paste ya decompile nahi.

---

## 📋 Project Quick Reference

| Field | Value |
|---|---|
| **Package** | `com.jarvis.assistant` |
| **App Name** | Jarvis |
| **Version** | 1.8.8 (Build 144) |
| **Kotlin** | 2.2.10 |
| **Compose BOM** | 2024.10.00 |
| **Min SDK** | 26 (Android 8.0+) |
| **Target SDK** | 35 |
| **Project Root** | `C:\Users\rosha\Downloads\Project's\Moblie AI\AI Folder\Jarvis-main` |
| **APK Output** | `app/build/outputs/apk/debug/app-debug.apk` |

---

## 📂 Key Files Map

```
app/src/main/java/com/jarvis/assistant/
├── JarvisApp.kt                          — Application class
├── background/JarvisConversationService.kt  — ⛔ DO NOT TOUCH (Gemini Live)
├── network/GeminiLiveWebSocket.kt           — ⛔ DO NOT TOUCH (Gemini Live)
├── ai/
│   ├── AiTextSynthesizer.kt              — Text AI routing (use this for AI calls)
│   ├── MultiModelAiRouter.kt             — Multi-model fallback router
│   └── local/OfflineVoiceConversationEngine.kt
├── automation/dispatcher/JarvisToolDispatcher.kt  — All AI tools/actions
├── codestudio/                           — Code Studio IDE (BYPASS IDE features)
│   ├── CodeStudioActivity.kt
│   ├── ai/AiEditEngine.kt, AutoFixEngine.kt
│   ├── editor/CodeEditor.kt, CodeStudioViewModel.kt
│   ├── generator/ProjectGenerator.kt, OfflineTemplates.kt
│   ├── preview/LivePreviewPanel.kt, PreviewServer.kt
│   └── terminal/TerminalPanel.kt, ShellRunner.kt
├── ui/
│   ├── island/DynamicIslandOverlay.kt    — Dynamic Island feature
│   ├── settings/SettingsFragment.kt      — Settings screen
│   └── home/MainActivity.kt
├── voice/edge/                           — Edge TTS (Maria voice)
│   ├── EdgeTtsClient.kt
│   ├── EdgeTtsSpeaker.kt
│   └── EdgeVoiceCatalog.kt
└── data/model/MariaPersona.kt           — "Maria" Hinglish persona
```

---

## 📝 Kaam Log (Chronological)

### Session 1 — Oct 31, 2026 (AndroLLM Merge)

**Kaam:** AndroLLM features ko Jarvis ke existing screens mein merge karna.  
**Kya hua:**
- User ne 4 baar correct kiya ki AndroLLM alag app/tab nahi chahiye, sirf features chahiye
- Final understanding: har AndroLLM feature ko matching Jarvis screen mein extend karo
- AndroLLM UI pieces Jarvis theme mein style kiye
- AI Hub tab nahi banaya — Home, Chat, Settings mein hi features add kiye
- Customization Studio mein AndroLLM options add kiye
- Memory pipeline (`AndroMemoryPipeline.kt`) integrate kiya
- Voice assistant features (`VoiceAssistant.kt`) Jarvis ke non-Gemini TTS ke saath

**Files changed:**
- `ui/chat/ChatFragment.kt` — mode chips extended
- `data/preferences/AppPreferences.kt` — naye preference fields
- `ui/settings/SettingsFragment.kt` — AndroLLM settings rows added
- `androllm/AndroLlmShared.kt`, `memory/AndroMemoryPipeline.kt`

---

### Session 2 — Nov 1, 2026 (BYPASS IDE / Code Studio)

**Kaam:** Dost ke "BYPASS IDE" app ko challenge ke roop mein Jarvis mein implement karna.  
**APK analyzed:** `C:\Users\rosha\Downloads\Project's\Moblie AI\ZIP File's\BYPASS IDE.apk`  
**Kya hua (3 parallel agents):**

**Agent 1 — Code Studio IDE:**
- `CodeStudioActivity.kt` + full `codestudio/` package
- Projects, file tree, tabbed editor (syntax highlighting HTML/CSS/JS/Kotlin/Python/JSON/XML/Markdown)
- Find & Replace, line numbers
- "Ask AI to edit" bar — surgical search/replace edits (code rehwrite nahi, sirf diff)
- Auto-Fix loop: error → AI patch → apply → validate → retry (max N cycles)
- AI calls: `AiTextSynthesizer.synthesizeText()` use karta hai (Gemini Live nahi)

**Agent 2 — Generator + Live Preview + Terminal:**
- `ProjectGenerator.kt` — AI se website/webapp/HTML5 game banaata hai
- `OfflineTemplates.kt` — bina AI ke bhi starter templates (offline)
- `LivePreviewPanel.kt` + `PreviewServer.kt` — phone pe 127.0.0.1:8080 pe live preview
- `TerminalPanel.kt` + `ShellRunner.kt` + `TerminalSession.kt`
  - Interactive shell, history, Ctrl+C, clear
  - Root toggle (sirf rooted phone par, su check karta hai)
  - Khatarnak commands pe warning (rm -rf /, mkfs, dd)
- JarvisToolDispatcher mein naye tools: `run_shell_command`, `generate_project`, `start_preview`

**Agent 3 — Maria Voice + Edge TTS + Dynamic Island + AI Modes:**
- `EdgeTtsClient.kt` — Microsoft Edge read-aloud neural voices (WebSocket protocol)
  - Hindi voices: hi-IN-SwaraNeural, en-IN-NeerjaNeural
  - Fallback: Jarvis ka existing TTS
- `MariaPersona.kt` — "Maria" Hinglish female persona
  - Greeting: "Aapki aawaz sun rahi hoon..."
  - Preferred voice: hi-IN-SwaraNeural
  - Existing persona system mein add kiya
- `DynamicIslandOverlay.kt` + `DynamicIslandController.kt`
  - Top-center floating capsule (listening/thinking/speaking status)
  - JarvisConversationService flows ko READ-ONLY padhta hai (modify nahi karta)
  - Settings mein toggle (default: OFF)
- `JarvisWebInfoTools.kt` — get_news (RSS: BBC/NDTV/HT/ToI), get_public_ip, wikipedia_search
- ChatFragment mein AI modes: Consultation / Autonomous / Full-Stack preset prompts

**Entry points added:**
- Chat mein "Code" chip long-press → Code Studio khulta hai
- Settings → Code Studio row
- Home dashboard quick action
- AndroidManifest mein activity registered (main agent ne bataya, user ne add kiya)

---

### Session 3 — Nov 3, 2026 (Documentation & Organization)

**Kaam:** Project documentation organize karna.  
**Kya hua:**
- `gev.md` — 15 Gods Eye docs ko ek file mein merge kiya (182k chars)
- `CLAUDE.md` — yahi file banayi (kaam log)
- `codex.md` — poore project ka master index
- `antigravity.md` — Antigravity/AntiGem feature reference
- `prompt.md` — saare user prompts numbered
- `task.md` — task tracking file

**Files created:**
- `docs/gev.md` ✅
- `docs/CLAUDE.md` ✅ (yahi)
- `docs/codex.md` ✅
- `docs/antigravity.md` ✅
- `docs/prompt.md` ✅
- `docs/task.md` ✅

---

### Session 4 — Nov 3, 2026 (Offline Brain: Gemini fail → Qwen3 4B)

**Kaam:** Gemini kisi bhi wajah se fail ho (galat/expired key, quota, server error, net) to Jarvis offline model se baat kare aur phone ke kaam bhi kare.
**Backup:** `backups/offline-brain-2026-11-03/` (original files + `restore.ps1`). Kuch toote to `powershell -ExecutionPolicy Bypass -File backups/offline-brain-2026-11-03/restore.ps1`.
**Rollback switch:** Settings → Engines → "Offline Brain when Gemini fails" (default ON). OFF = purana behaviour.

**Kya badla:**
- `ai/local/LocalAiConfig.kt` — naya model `qwen3-4b-instruct-2507` (Qwen3 4B Instruct 2507, Q4_K_M, 2.33 GB, sha256 verified) ab default offline model. Purane users ko Models screen se download+select karna hoga.
- `ai/local/ToolCallParser.kt` — **bug fix:** Qwen stop list mein `</tool_call>` hai, isliye closing tag kat jaata tha aur tool call parse nahi hota tha. Ab bina closing tag bhi parse hota hai (balanced-brace JSON). `<think>…</think>` strip hota hai; thinking ke andar likha tool call kabhi nahi chalta.
- `ai/provider/LocalPhoneProvider.kt` — stream khatam hone ke baad ek aur parse pass; tool dispatch `runOfflineTool()` mein nikala.
- `ai/router/ModelRouter.kt` — `geminiTextOrLocal()`: Gemini se khaali/fail jawab aaye to sentinel se pakad ke `tryLocalHostOrOffline`. 5 min cooldown (`markGeminiFailed()`), taaki har sawaal Gemini ka wait na kare. Key blank hone par bhi offline chalta hai.
- `background/JarvisConversationService.kt` (sirf 3 jagah) — `onError`: flag ON ho to bad key / fatal error par bhi `engageOfflineLocalAiMode()`. Watcher: 5 min backoff, aur rejected key tab tak retry nahi jab tak user key badle (`geminiBlockedApiKey`). `GeminiLiveWebSocket.kt` untouched.
- `androllm/AndroLlmShared.kt` — flag `OFFLINE_BRAIN_FAILOVER`. `ui/compose/feature/settings/SectionEngines.kt` — toggle card.
- Tests: `app/src/test/.../ToolCallParserFailoverTest.kt` (6 tests). `gradlew :app:testDebugUnitTest --tests "com.jarvis.assistant.ai.local.*"` → PASS.

**Flow ab:**
```
Voice (Gemini Live) fail → onError → engageOfflineLocalAiMode → OfflineVoiceConversationEngine
   → ModelRouter.routeConversationalQuery → [Ollama PC agar mile] → LocalPhoneProvider (Qwen3 4B GGUF)
   → tools: <tool_call>{...} → ToolCallParser → JarvisToolDispatcher
Text chat → ModelRouter GEMINI_LIVE/AUTO_HYBRID → geminiTextOrLocal → fail → same local path
```

---

### Session 5 — Nov 3–4, 2026 (AndroLLM merge + "sirf naam ke" features theek)

**A. AndroLLM duplicate merge** (backup: `backups/androllm-merge-2026-11-03/restore.ps1`)
- Ek-ek file bani: `ModelMetadataRegistry`, `DownloadValidation` (androllm/engine/support), `ModelFamily` + `ChatTemplateRenderer` (ai/compat), `EngineCrashGuard` (ai/diagnostics), `ToolConfirmationManager` (automation/agent), `ToolLoopGuard` (automation/guard), `CloudUsageMeter` (ai/cloud).
- Memory: `AndroMemoryPipeline` → `data/memory/MemoryManager.kt`, `AndroMemoryStore` → `data/memory/MemoryRepository.kt`. **DB schema unchanged** (dono DB waise hi).
- Chat: `ChatRepository` ab `androllm_chat.db` ka façade (prefs mein bhi dual-write, rollback ke liye). "Mirror chats" / "Import history" toggles hata diye.
- Dead files deleted: `ai/cloud/PromptCache.kt`, `ai/diagnostics/EnginePerformanceMonitor.kt`, `automation/guard/ToolOutputValidator.kt`, `AppLauncherTool.kt` (`launch_app` = `open_app` duplicate).
- Defaults ON: Agent pipeline (`AGENT_PIPELINE`), Memory extraction (3 fail → 10 min pause).

**B. Feature fixes** (backup: `backups/feature-fixes-2026-11-04/restore.ps1`)
- Batch 1: tools sach bolte hain (flashlight, wifi, weather, clipboard, connected apps, YouTube search). Driving mode: persist, sirf car disconnect par off, auto-reject + SMS, `set_driving_mode` tool + voice phrase.
- Batch 2: Mem0/Auto-reply confirm/Battery saver wired; Screen Intelligence, Telegram&Signal, MUSE, MCP Bridge, SMTP, Holo Scanlines, Join YouTube, Sign-in rows hataye. Bypass IDE fake hisse hataye (scan/status asli). Chat Persona chip + mic asli. SOS asli GPS. Self-Healing → Code Studio.
- Batch 3: asli App Lock (`ui/facelock/AppLockStore/Enforcer/Picker`, accessibility se enforce). Voice lock status. Touch Guard foreground service (`GuardianForegroundService`). Driving call settings UI.
- Batch 4: naye tools `get_battery_status`, `whatsapp_read_messages`, `search_contacts`, `get_scheduled_tasks`, `set_dnd`, `start_focus_timer`, `get_storage_stats`; `press_key` schema; calendar time; dynamic tools AI ko dikhte; Gods Eye offline routing; 8 voice commands; VoiceGuardian enforcement (armed → risky tools par confirmation).
- Manifest: `ACCESS_NOTIFICATION_POLICY`, `SET_ALARM`, `FOREGROUND_SERVICE_SPECIAL_USE/CAMERA`, Guardian service, App lock activity `taskAffinity=""` + `exported=false`.
- Tests: 207/207 pass. APK built.

**⚠️ Protected file mein baaki (user permission chahiye):** `JarvisConversationService.kt` ke Gemini Live handlers (flashlight, search_youtube, phone call, send_whatsapp…) abhi purane hain; VoiceGuard Live par lagu nahi; `VoiceGuardPolicy.markOwnerVerified()` service se call nahi hota (isliye armed Voice Guard har risky tool par confirm poochhta hai); wake-word sensitivity hard-coded.

---

## 🔲 Pending / Next Steps

- [ ] **Phone par test** (P-08 in task.md): Qwen3 4B download, galat key daal ke voice start, "torch on karo" bolna.

- [ ] Code Studio manifest entry confirm karna (AndroidManifest mein `<activity>` add hua kya)
- [ ] Edge TTS test karna live device pe
- [ ] Dynamic Island SYSTEM_ALERT_WINDOW permission flow test
- [ ] Auto-Fix loop end-to-end test
- [ ] Gods Eye Phase 6: Mobile touch gestures & WebView optimization (se `RESUME.md`)
- [ ] CampusVoice Google login fix (localhost pe email use karo — done Nov 3)

---

## 💡 How To Continue (Dusre session mein)

1. Is file ko pehle padho (CLAUDE.md)
2. Phir `docs/codex.md` — project structure samajhne ke liye
3. Phir `docs/guide.md` — detailed engineering guide
4. Phir `docs/task.md` — current pending task kya hai
5. **KABHI** `GeminiLiveWebSocket.kt` ya `JarvisConversationService.kt` mat chhuo
6. Build karna ho to sirf: `./gradlew termuxBuildDebug` (Termux pe)
