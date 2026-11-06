# CLAUDE.md — Jarvis Project Work Log

> **Purpose:** Har kaam jo Claude ne is project mein kiya, woh yahan likha jaata hai.  
> **Rule:** Jab bhi koi naya kaam karo, sabse pehle yahan update karo. Dusra AI bhi yahi padh ke project ko samjhe.  
> **Last Updated:** Nov 6, 2026

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

### Session 6 — Nov 4, 2026 (Maya APK → Jarvis full feature + UI integration)

**Kaam:** `maya.apk` (com.getmaya.android 5.2.34) ka static analysis (REA + apna DEX parser + SDK dexdump; JADX run classifier ne block kiya) → har Maya feature Jarvis ke andar merge karna, Maya jaisa UI Jarvis ke apne screens me, 3D character stage ke saath.
**Backup:** `backups/maya-integration-2026-11-04/` (`restore.ps1`, `files-before.txt`).
**Architecture:**
- `automation/dispatcher/MayaToolRegistry.kt` — naya tool registry; `JarvisToolDispatcher.dispatchTool` built-in `when` se PEHLE isko check karta hai aur `getAvailableToolSchemas` me iske schemas append hote hain (isliye Gemini Live, offline brain, Hermes, AndroLLM pipeline sabko naye tools dikhte hain). `MayaToolPacks.installAll` me har pack register hota hai.
- Naye feature packs `com.jarvis.assistant.maya.*` packages me: screen (verb loop/#N elements/watch_for/macros), brain (Advanced Agentic mode, Deepgram, extra providers), connectors (Notion/Linear/Todoist/GitLab/Drive/Vercel/Telegram), business (content/leads/finance/KPI), guardian (Faces, Face Lock, listen mode, God Mode, wipe), memory (rules/episodes/wiki/notices), hub (Termux recipes, PC hub, ESP32 smart home), media (image gen, reels, commentary), ui (Maya theme + character stage).
- Gemini Live files (`GeminiLiveWebSocket.kt`, `JarvisConversationService.kt`) untouched. Live-config parity (affective dialog, proactivity, session resumption, context compression) isliye NAHI hui — protected files.
- Nahi laaya (proprietary Maya server): licence/lease, Pro Max, Hunter TTS server, getmaya.online avatar clips, telemetry ingest, HunterCode billing.

**Result (Nov 4, 18:18):** `gradlew :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL, 394/394 unit tests pass, APK `app/build/outputs/apk/debug/app-debug.apk` (517 MB). 8 tool packs / 281 tools registered, 0 name collisions. Phone par abhi test nahi hua (device connected nahi tha).
**Pending:** phone test of every Maya screen/tool; Home par notices + Advanced Agentic talk-button hand-off; overlay character auto-restore after restart; per-character 3D models.

### Session 7 — Nov 5, 2026 (cleanup: security + work messages removed, duplicates merged, colours/glass)
**Backup:** `backups/cleanup-2026-11-05/restore.ps1` (java, res/layout, res/xml, manifest, tests; themes/colors .bak).
- Security/Guardian/Privacy poora delete (Touch Guard, SOS, Watchman, App Lock, Privacy Shield/Mode, Owner Voice enroll UI, App Identity row, maya/guardian). `VoiceGuardGate`/`VoiceGuardPolicy` sirf stub (protected Gemini Live files inhe call karti hain) — kuch block nahi karte.
- WORK & MESSAGES section delete (WhatsApp/Email/Social Story rows + SocialStoryManager), Maya WhatsApp auto-reply engine + tools delete, maya/business delete. Connectors row → Skills section.
- Merge: Voice Engine dialog → "Advanced engine" (Maya VoiceEngineActivity); PC hub dialog → "Hub & Termux"; Persona dialog → "3D Characters" card; Skills sheet → "Macros & Watches"; duplicate Maya rows hataye. MAYA section → "MEDIA & EXTRAS".
- Colours: low-contrast muted/accent text fixed (fix_contrast); maya_text_muted #9AA4B5. Glass: `styles_glass.xml` overlays + `ThemeManager.applyGlassOverlay` (cards translucent jab Frosted Glass ON).
- Pending (phone chahiye): Home orb ke beech green cheez identify+remove, 3D character Home pe, device par command tests, non-working features delete.

### Session 7b — Nov 5, 2026 (Nova redesign, 21st.dev style)
**Backup:** `backups/ui-redesign-2026-11-05/restore.ps1` (layouts, drawables, values, ThemeManager, home, AppPreferences).
- Naya default theme `nova` (`Theme.JarvisAssistant.Nova`): zinc-950 #09090B, hairline glass cards, violet #A78BFA accent, Inter font, aurora glow blobs `bg_nova_glow_1..3`, glass overlay `JarvisGlassOverlay.Nova`. JarvisApp me one-time migration (`jarvis_ui_migrations/ui_nova_redesign_applied`). Theme picker me "Nova (new look)" card.
- Drawables ka hardcoded cyan (#xx00F0FF) → neutral white glass / ?attr/themeAccent (12 files). Active dock pill = white glass.
- Home: green centre disk (`bg_arc_reactor_rings` centre glow) + arc reactor rings hata diye (GONE).
- Installed + screenshot verified on I2221. Pending: phone unlock karke Settings screenshot, command tests, non-working features delete.

### Session 7c — Nov 5, 2026 (3D character: touch + human idle)
- Browser test (Edge headless, `python -m http.server` on assets): Mora loads, 11 clips bind, tap -> clapping reaction works. Asli problems: (1) Home pe character tap = `handleMicOrOrbClick` bhi (Jarvis on/off) -> ab avatar entities pe tap sirf reaction; plain orb pe tap-to-start rehta hai. (2) idle clip almost static + seedhi kohni -> `mora_character.html` me `updateHumanIdle` layer: saans, weight shift, kohni -0.32 rad (forward, browser me tested), har 6-12 s glance/tilt/smile/look-down, random blink. `?elbow=` / `?act=` URL params tuning ke liye.
- Backup: `backups/ui-redesign-2026-11-05/mora_character.html.bak`, `HomeFragment.touchfix.bak`.
- BUILD/INSTALL pending: dusri chat us waqt phone + project use kar rahi thi.

### Session 7d — Nov 5, 2026 (RedMagic-style touch personality for Mora)
- `mora_character.html`: zones from projected bones (headtop, face, hand, arm, belly, chest, hips, legs, outside). Chest/hips = unwanted: embarrassed -> angry -> sulk (turns her back 9 s, "Hmph! Pehle sorry bolo"), annoyance decays 1/15 s. Angry/Sorrow face morphs, head "no" shake, turn away + step back. Glass speech bubble with Hinglish lines. `?touch=a,b,c` test hook. Browser-verified (Edge headless).
- `Jarvis3DModelBridge.onModelTouched(zone, mood, line)`: Live on -> [SYSTEM EVENT] so Gemini reacts in voice; else PocketTTS speaks the bubble line; 6 s throttle.
- NOT built/installed yet (dusri chat phone/project use kar rahi thi). Backups: `backups/ui-redesign-2026-11-05/*.bak`.

### Session 8 — Nov 5, 2026 (Offline Brain asli mein theek — phone par test kiya)
**Problem (phone par dekha):** offline jawab me `</|im_end|><|im_start|>user …` jaisi fake lines aati thi, voice me 100–200 s lagte the, "Model is not loaded"/"No result" bolta tha, text chat me "torch on karo" kaam nahi karta tha. Phone par active model DeepSeek R1 1.5B tha, Qwen3 4B download hi nahi tha.
**Backup:** `backups/offline-brain-fix-2026-11-05/restore.ps1` (sab badle files + naye files hatata hai).

**Kya badla:**
- `ai/local/ToolCallParser.kt` — turn-leak guard: `findTurnLeak/cutAtTurnLeak/streamVisibleText/cleanLocalModelText`. Model jab khud agla "user" turn likhne lage to wahi kaat dete hain; adhoora `<|im_…` stream me nahi dikhta; lone `</think>` se pehle ka reasoning hatata hai.
- `ai/compat/ModelFamily.kt` + `ChatTemplateRenderer.kt` — DeepSeek ka asli template (`<｜User｜>…<｜Assistant｜><think>`), Llama 3 ko tool-call format bataya (pehle sirf tool list thi), double BOS hataya. Tools + rules prompt me SABSE PEHLE (constant prefix → llama.cpp cache reuse). Purani history se leaked tags saaf karke hi model ko dete hain.
- `ai/provider/LocalPhoneProvider.kt` — ek waqt me ek hi generation (`generationMutex`); model switch chalti request ko nahi todta; `generate().use{}` se break par native task cancel; tool lock chhodne ke BAAD chalta hai; selected model download na ho to best downloaded model khud chunta hai.
- `ai/provider/LocalPhoneProvider.kt` (2nd round) — llama.cpp 2 slots + shared (unified) KV: slot 0 = baat-cheet (tools+rules wala prefix cache me rehta hai → ~10 s jawab), slot 1 = background (memory extraction). Pehle background prompt cache mita deta tha → agla jawab ~3 min. Prompt reading ke liye fast cores (`setThreadsBatch`). Two-slot setup fail ho to purana single-slot load. `warmUpAsync()` asli prompt prefix slot 0 me pehle se padh leta hai; offline voice shuru hone par aur chat me "Local Offline" chunne par chalta hai.
- `ai/router/ModelRouter.kt` — `setMode(LOCAL_OFFLINE)` par warm-up.
- `ai/local/LocalModelManager.kt` — `resolveUsableModelId()` (Qwen3 4B > Qwen2.5 1.5B > Llama 3.2 > SmolLM > Qwen 0.5B > DeepSeek).
- `ai/local/LocalAiEngine.kt` — offline brain me direct commands: `CommandParser` + `CommandExecutor` (voice wala hi) ab text chat / Auto Hybrid failover me bhi. "torch on karo" ~0.1 s.
- `ai/local/JarvisThreadManager.kt` — inference priority 10 → 5. Priority 10 par Android thread ko "background" cgroup (chhote cores) me daal deta tha.
- `ai/local/OfflineVoiceConversationEngine.kt` — naya utterance purane jawab ko cancel karta hai (pehle sab ek saath model par chadhte the); "huh/hmm/ah…" noise ignore; offline voice shuru hote hi model warm-up.
- `automation/phone/PhoneControlManager.kt` — alias package (com.google.android.calculator) install na ho to installed apps me naam se dhoondta hai (vivo calculator khulta hai). Volume: muted ho to pehle unmute, aur asli level batata hai.
- `automation/dispatcher/JarvisToolDispatcher.kt` — `set_volume` same unmute + asli level.
- Test: `app/src/test/.../ai/local/OfflineBrainOutputGuardTest.kt` (8 tests). Poora suite 350/350 pass.
- Debug-only test hook: `app/src/debug/.../OfflineBrainDebugReceiver.kt` (release me nahi aata, sirf adb shell bhej sakta hai):
  `adb shell am broadcast -a com.jarvis.assistant.debug.OFFLINE_BRAIN -p com.jarvis.assistant --es text "'torch on karo'" [--es mode AUTO_HYBRID] [--es model qwen3-4b-instruct-2507]` → logcat tag `OfflineBrainTest`.

**Phone (vivo I2221, SD 8s Gen 3, 7.1 GB RAM) par result, Qwen3 4B:**
| Command | Result | Time |
|---|---|---|
| hello, tum kaun ho? | Hinglish jawab | ~10 s (warm) |
| torch on/band karo, jalao/bujhao | camera service me torch on/off confirm | <0.5 s |
| battery kitni bachi hai? | model ne `get_battery_status` chalaya | ~14 s |
| calculator kholo | com.vivo.calculator khula | <1 s |
| Auto Hybrid (Gemini fail) → India ki capital | "Delhi … Red Fort" | ~10 s |
| volume 40 percent | tool chala, par vivo speaker media volume 0 par lock (adb se bhi nahi badla) → ab sach bolta hai "0%" | — |
- Model `qwen3-4b-instruct-2507.gguf` (sha256 verified) adb se phone me daala aur active kiya.
- Phone par jo APK hai (10:17 build) usme slot/warm-up wala last change NAHI hai. Naya APK (14:06, 350/350 tests pass) bana hai par phone disconnect ho gaya tha — install baaki: `adb install -r -d -t app/build/outputs/apk/debug/app-debug.apk`, phir `OfflineBrainTest` hook se 2 sawaal lagatar pooch ke time dekhna.
- PC: do sessions ek saath build karein to Kotlin daemon (3 GB heap) full ho ke atak jaata hai (endless full GC). Build se theek pehle `gradlew.bat` process check karo.

**Round 3 (Nov 5 shaam, phone USB + wireless par test):**
- `automation/agent/TaskExecutionPolicy.kt` — `open_app`/`open_website` ab accessibility ke bina chalte hain (sirf intent launch; lock/screen check rehta hai). Pehle "youtube kholo" → "Jarvis Accessibility Service is disabled".
- `ai/AiTextSynthesizer.kt` — MultiModelAiRouter ka providerUsed "Failed"/"None" (ensemble mode ka "All configured models failed…") ab failure maana jaata hai → offline brain failover chalta hai. Pehle ye error text jawab ban ke dikhta tha.
- `ai/router/ModelRouter.kt` + `LocalAiEngine.tryDirectCommand()` — torch/calculator jaise commands HAR mode me sabse pehle (provider "Jarvis Command"); Gemini/ChatGPT text ke paas phone tools nahi the.
- Final APK (15:23 build, 350/350 tests) phone par installed. Tested: torch on/off (camera confirm), chat Hinglish, battery tool, calculator, Auto Hybrid offline answer, YouTube khula (model ne open_app chalaya). Warm-up + 2-slot cache ke baad pehla jawab 13 s (thanda phone). Garam phone (thermal 2, big core 0.48 GHz cap) par 80–100 s.
- Wireless adb: vivo screen lock/battery par wireless debugging so jaata hai; port badalta hai (mdns na dikhe to 30000–50000 scan). Charger + "Stay awake" rakho.
- Gemini-fail-with-key wala path is round me phone par test nahi hua (phone ke paas tab internet nahi tha).

**Pending:** Gemini Live phone par fail ho raha hai (isliye offline mode chal raha tha) — protected files, user permission chahiye. Vivo speaker media volume 0 par kyu atka hai, phone settings me dekhna. Voice se asli bolkar test (mic) abhi baaki.

### Session 7e — Nov 5, 2026 (Mora "alive": face follow, mood memory, gestures)
- `mora_character.html`: mood memory in localStorage (`mora_mood_v1`, grudge up to 10 min, survives restarts) + `forgive()`; long press (head = long pat/bliss, hand = holding hands, chest/hips = worse), pinch zoom (saved `mora_zoom`), horizontal swipe turns her (springs back), `setViewer(x,y,present)` gaze + "Aa gaye!" greeting after 20 s away. Test hooks `?viewer=x,y`, `?forgive=1`, `?touch=`. Browser-verified.
- `ui/home/ViewerFaceTracker.kt` (new): Camera2 front cam 320x240 + ML Kit face detection ~5 fps -> setViewer. Opt-in Settings → MEDIA & EXTRAS → "Character looks at you" (`jarvis_avatar_prefs/follow_viewer_face`), CAMERA permission asked on enable.
- `HomeFragment`: tracker start/stop with Home visibility; transcript "sorry/maaf/सॉरी/माफ" -> forgive(); long-press on avatar no longer triggers mic.
- NOT compiled yet — dusri chat ka Gradle daemon BUSY tha.

### Session 7f — Nov 5, 2026 (Mora grounded, side thought-bubble, voice)
- Phone par "hawa me + ulta" ka cause: idle clip pose me sole deck se ~2.7 cm upar tha; aur sulk me `turnTarget = 2.6` (peeth dikhti thi, sir chhupa) → ab 1.0 (side profile, chin away).
- `mora_character.html`: `groundMora()` — ek baar skinned mesh ka lowest point naap ke sole gap calibrate, phir har frame root Y adjust (clamp −8/+4 cm, jumps bache). Static dark foot pads hidden; foot shadows ab pairon ke saath chalti hain.
- Bubble ab sir ke bagal me thought-bubble (2 dots) jo head follow karti hai; `say()` = bubble + voice. Local TTS bole to lips hilte hain (`localTalkUntil`).
- `Jarvis3DModelBridge`: `onModelTouched` returns live/local/throttled (throttle 6 s → 2.5 s), naya `speakLine(line, mood)`, `isVoiceLive()`. Backup: `backups/mora-ground-2026-11-05/`.
- Debug: phone WebView ko `adb forward tcp:9333 localabstract:webview_devtools_remote_<pid>` + CDP `Runtime.evaluate("window.__moraDebug()")`.

### Session 9 — Nov 5, 2026 (JARVIS logo + app icon, Companion mode hataya, Nova design fixes)
**Backup:** `backups/logo-companion-2026-11-05/restore.ps1` (edited files + `deleted/` me hataye gaye files).
- **Logo:** Home header me gradient "Jarvis" text hata ke purana `ic_jarvis_brand_logo` (JARVIS arc logo, theme accent se tinted, tap par bounce + glow). PNG ab `drawable-nodpi/` me (pehle mdpi bucket me tha → ~13 MB decode).
- **App icon:** naya launcher icon = wahi JARVIS logo, dark #09090B background (adaptive + monochrome + legacy PNGs, sab densities).
- **Companion mode removed:** Settings toggle, Secret settings (intimacy), persona dialog card, name-dialog chip, Studio card/chip, Maya theme ka home-switch, hidden Home button, companion prompt hata diye. `MainActivity` me sirf Jarvis Home. Deleted (backup `deleted/`): `JarvisCompanionHomeFragment`, `CharacterSheet`, `MayaIntimateGate`, `MayaOrbView`, `MayaTalkRingsView`, `SecretSettingsBottomSheet` + layouts; `MAYA_INTIMATE` character. Prefs: `experienceMode` hamesha Jarvis, `isSecretSettingsEnabled` hamesha false, purana "Companion Mode" persona → Assistant. One-time migration `jarvis_ui_migrations/companion_mode_removed` (`AppPreferences.migrateAwayFromCompanionMode`). Protected Gemini Live files untouched.
- **Companion se Jarvis me laaya (kaam ka):** Overlay & Avatar → Maya character card me "Look" picker (character look + voice sample; persona/voice nahi badalta).
- **Home/dock fixes:** Mora page ka apna HUD pill hide (native status pill se overlap), Mora ka Nova violet colour, Settings tab ab active dikhta, Updates par Home highlight, resume par dock flash nahi, light themes me active pill dikhta, white accent par mic icon kaala, mic lift kam (upar ka hissa tap nahi hota tha), snackbars dock ke upar, telemetry text sentence case, focus-mode buttons system bars se door.
- **Settings/Chat fixes:** caps HUD text → sentence case + theme colours, MCP list naye style me, bekaar search pill hidden, chat subtitle asli engine dikhata, drawer status bar ke neeche, drawer rows Nova style.
- Build: `assembleDebug testDebugUnitTest` → BUILD SUCCESSFUL, 350/350 tests. APK 15:10. **Phone par install baaki** (wifi adb offline tha).

### Session 10 — Nov 5, 2026 evening (Dark Bento design, settings cleanup, AI pages asli, debug hooks)
**Backup:** `backups/redesign-cleanup-2026-11-05/restore.ps1` (edited files, `deleted/`, added files list).
- **Design (Dribbble "Dark Bento UI Kit" jaisa):** Nova = #090812 page, indigo cards (top lighter gradient + corner glow + hairline), plates #17152E, text #F9F9FA/#BDBBCB/#9391A2, button #6352F5 + white label. Naye attrs: themeAccentFill, themeOnAccent, themePlateBorder, themeGlow1/2, themeSuccess/Warning/Danger/Info (har theme me defined). Contrast script (scratchpad `contrast.py`, `layout_audit.py`): 24 theme styles 19/19 checks pass; light-accent themes (Stealth, WhiteDark, Gold, Emerald, Orange, Dark) par buttons ka label kaala.
- **Settings → Appearance:** "Customize home screen" + "Home HUD & widgets" + "Hide home screen cards" → ek row **"Home screen"** (HomeCardsCustomizationBottomSheet: hide-all, 3D character, HUD size 0.70–1.30x, compact stage, per-card switches).
- **Floating avatar:** Maya overlay character + card hataye; purana `TaskProgressOverlay` avatar, size slider 56–200 dp (`jarvis_overlay_avatar_prefs`), long-press = close; "Always on" mode me app khulte hi wapas (MainActivity.onResume).
- **Backup & restore:** `data/backup/BackupManager` ek engine — SAF se export/restore (prefs, dono memory stores, chats, rules/wiki/notices, macros...), API keys/tokens kabhi export nahi. Settings, Wiki → Backup, `export_backup` tool, Memory page import sab isi engine par.
- **Deleted:** Driving mode (JarvisDrivingModeManager sirf answerCall/declineCall/onIncomingCall ke liye bacha — protected service use karti hai), Live wallpaper (service, xml, rows, Home card, Studio section).
- **Media studio:** chips/buttons wrap, edge-to-edge insets; image size ab asli (center-crop/scale to chosen size, Gemini ko aspect ratio).
- **AI pages:** Agent & Tools toggles ab Jarvis dispatcher par lagte hain (`JarvisDispatcherHooks.preflight`: master switch, per-tool off, confirmation mode; Direct autonomous mode ON ho to confirm nahi poochta), Tool Debug me "Run a tool" (asli dispatcher), Screen automation confirm ab dusre apps ke upar (`ConfirmationOverlay`), AI Feature Access me 6 naye real permission gates, Voice page = asli Jarvis voice (Live voice, wake-word sensitivity, reply voice + test), Developer & Diagnostics me asli model state + Gemini check card (`GeminiSelfTest`).
- **Debug-only adb hooks:** `am start -n com.jarvis.assistant/.debug.DebugOpenActivity --es section VOICE|AGENT|AUTOMATION|PERMISSIONS` / `--es route tool_debug|developer`; `am broadcast -a com.jarvis.assistant.debug.RUN_TOOL -p com.jarvis.assistant --es name get_battery_status --es args '{}'` (tag JarvisToolTest); `am broadcast -a com.jarvis.assistant.debug.GEMINI_TEST -p com.jarvis.assistant --es text "'India ki capital?'" --es mode both` (tag GeminiTest).
- Known: text chat saved Live model (`gemini-3.1-flash-live-preview`) text endpoint ko bhejta hai — GEMINI_TEST se verify karna. ~90 tools jo GeminiLiveWebSocket me hardcoded hain unpe Agent toggles lagu nahi (protected file).

### Session 10b — Nov 5, 2026 night (Gemini text asli fix, ONNX conflict, bento layouts, phone test)
- **Gemini text chat fix:** saved model Live-only hai (`gemini-3.1-flash-live-preview` → generateContent par HTTP 400) aur purane fallbacks retire (`gemini-2.0-flash`/`1.5-flash` → 404), isliye har text sawaal chupke se offline brain par jata tha. Naya `ai/GeminiTextModels.kt`: key ke ListModels se text-capable models (Live/TTS/image/embedding skip), newest Flash pehle, kaam karne wala yaad rakhta hai. MultiModelAiRouter, AiTextSynthesizer, VisionDescribe, VisionLookup, GeminiSelfTest isi par. Phone test: `gemini-3.6-flash` HTTP 200 "Jarvis online", chat path 3 s. Gemini Live: "India ki capital New Delhi hai" 1.4 s.
- **ONNX conflict fix:** PocketTTS ka ORT 1.20 (`jniLibs/arm64-v8a/libonnxruntime.so`) sherpa-onnx ke ORT 1.27 ko override karta tha → sherpa kabhi load nahi hota tha. 1.20 ka SONAME/DT_NEEDED ELF-patch karke `libonnxrt120.so` (scratchpad `elf_rename.py`), `PocketTtsNative.load()` ab `onnxrt120` load karta hai. Phone par dono saath map hue (maps vs APK offsets).
- **Bento layout:** fragment_home.xml (dual-brain wide tile + nested plates, 2x2 stat tiles, square quick actions), fragment_settings.xml (hero profile tile, 2x2 shortcuts, har section card + plate rows), item_jarvis_settings_link_row, MediaStudioActivity (Compose, theme attrs se). Backup `backups/bento-layout-2026-11-05/restore.ps1`.
- **Phone par verify (build 21:35):** 6 AI pages khule (debug launcher), tools via dispatcher (battery/storage), Backup dialog + SAF "Save to" picker, merged Home screen sheet, floating avatar launcher ke upar dikha + off karne par hata, Media studio fit. Jarvis ke logcat logs vivo par is waqt nahi aate — Developer page ka "Gemini check" use karo.

### Session 11 — Nov 6, 2026 (God's Eye phone look + Jarvis se asli connection)
**Backup:** `backups/godseye-mobile-2026-11-06/restore.ps1` (edited files; naye `jarvis-mobile.css/js` hata deta hai).
- **Phone look (portrait < 760 px):** naye `assets/godseye/jarvis-mobile.css` + `jarvis-mobile.js`, `GodsEyeActivity.bootstrappedIndexHtml()` `</head>` se pehle jodta hai (upstream bundle untouched). Globe full screen; neeche chip bar (Layers · Places · CCTV · Scenes · Style · Display · Street · Context, Weather/Imagery jab layer on); chip = us panel ka bottom sheet (panel ka apna collapse button dabata hai). Places/Style = Jarvis ke sheets jo console ke asli buttons dabate hain + search (`fly_to_location`) + "My location". Dock me sirf Jarvis voice pill. User ne kaha "look naya, baaki sab pehle wala": HUD ke charon kone (compact 8.5px: upar classification/REC, neeche MGRS/GSD-NIIRS voice pill ke upar), Power-up keys chip, credits aur console logo (Back+menu ke bagal me) sab dikhte hain, aapas me overlap nahi. Sheet sirf user tap ya Jarvis `set_panel_open` par khulti hai (console khud panel khole to wapas band). Android Back pehle sheet band karta hai. Accent rang Jarvis theme se (`window.__JARVIS_THEME__`), cards hamesha dark. Landscape/tablet = purana desktop layout.
- **Native toolbar:** 5 buttons ki jagah Back + menu (API keys, rotate, my location, hero scene, auto tour, reload), top 10dp.
- **Start view:** plain open → phone ki last known location (60 km), fix na ho to India. Seedha camera flyTo (result card nahi).
- **Phone rendering:** 30 fps, globe SSE 2.0, tile cache 120, 3D tiles SSE 16 (pehle 60 fps / SSE 1.2 / tiles SSE 6 force hota tha). WebGL context lost → page usi view par reload (2 min me max 2).
- **Connection fixes:** `GodsEyeActivity.normalizeAction()` — nested `camera{}` → flat lat/lon, layer naam → asli ids (`subsea-cables`→`telegeography-submarine-cables`, `firms`→`local-firms`, `c,f,s` tokens…), `cctv`→`cameraId`, sirf `location` ho to `fly_to_location`. Ek trace_id ek hi baar chalta hai (pehle 2–3 baar). Startup script ab layers band nahi karta (Jarvis ki layers mitti thi). Background me God's Eye ho to `bringToFront`. Auto-tour ab sahi format (purane `flyTo/selectCamera` fallback bundle me the hi nahi).
- **Jarvis ↔ page:** `GodsEyeActivity.runPageCommand(name,args)` console ka apna runner chalata hai aur result `JarvisVoiceBridge.onCommandResult` se lautata hai. `gods_eye_inspect_globe` ab LIVE (camera, layers+count, user ne kya tap kiya). Naya tool `gods_eye_command` (fly_to_location, track_entity, analyst_query, control_cctv…; list `jarvis-realtime-session.json` se). `open_gods_eye_view` anjaan jagah → console geocode; `layers` arg. Transit me `city`. Radar fallback label sach ("New Delhi (no location fix)").
- **Brains:** dispatcher `getAvailableToolSchemas` me open_gods_eye_view / control_layers / inspect_globe / gods_eye_command (text chat, Hermes, agentic). Offline brain ko sirf `open_gods_eye_view`. Gemini Live ke schemas protected file me hain → Live ko `gods_eye_command` nahi milta, baaki fixes Live par bhi lagte hain (wo bhi GodsEyeMcpClient use karta hai). PromptGenerator me inspect/command ki 2 lines.
- **Phone par verify (build 09:39, vivo I2221):** GPS (19.37, 72.83) par khula; inspect_globe live 0.1 s; `gods_eye_command fly_to_location "India Gate, Delhi"` → 28.614, 77.224; analyst_query flights jawab; `open_gods_eye_view Ukraine + earthquakes` → 48.28, 31.18, layer on; Layers sheet + Back. Pehle build me Delhi 3D tiles ke baad WebGL context lost hua tha (RAM full: game + Mora WebView) → isliye reload guard.
- **Testing tip:** PC par phone-size UI test: assets ko local server se serve karke headless Edge (`--remote-debugging-port`, CDP `Emulation.setDeviceMetricsOverride` 411x878). Phone WebView: `adb forward tcp:9333 localabstract:webview_devtools_remote_<pid>` + websocket `suppress_origin=True`.
- **Baaki / pata hai:** unit tests is session me nahi chale (PC RAM kam); Satellites layer "CelesTrak unreachable" (upstream feed); Live voice se God's Eye commands mic se test nahi kiye.

### Session 9 — Nov 5–6, 2026 (Offline brain: agent loop, audit, quality fixes)
**Backups:** `backups/offline-brain-agent-2026-11-05/` aur `backups/offline-brain-quality-2026-11-06/` (dono me `restore.ps1`).
- Agent loop (`LocalPhoneProvider.streamResponse`): tool → result `<tool_response>` me wapas → model agla tool ya natural jawab (max 4 steps, sirf Qwen). Per-request tools: `ai/local/OfflineToolRouter.kt` (~140 offline tools me se, Hinglish keywords, one-line format). Prompt me date/time.
- Audit (debug-only `OfflineBrainAudit`, `--ez audit true`; tools sirf parse, chalte nahi): A minimal / B 12 tools / C +6 / D +history+memory. Template = GGUF jaisa sahi, `<think>` 0, leaks 0, cache asli (cacheN 505–512). Bugs: toota tool JSON (3/24) → khaali jawab; router noisy ("kitna" → 6 bekaar tools, get_battery duplicate).
- Fixes: `ToolCallParser.parseToolCallLenient` (sirf finished reply par; `{"}}` → {}, `60*24 - 6*60` → 1080), kabhi khaali jawab nahi, router sirf specific name words + core look-alike skip, Qwen sampling top_k 20 / top_p 0.8.
- Phone test (Nov 6, 08:09 build): battery → tool + natural jawab (2 steps), torch (model se) → tool + jawab, Pune mausam → asli data + jawab, 12×15 = 180 bina extra tools, "tum kaun ho" JARVIS. 40–110 s warm, ~210 s cold.
- Garmi: 2–3 lagatar jawab ke baad Thermal 3; God's Eye 3D globe khula ho to ~1.5 core lagatar. Thread benchmark (4/5/6/8) thande phone par abhi baaki. Router: "roshni" → screen tools jud jaate hain (chhota fix baaki).

## 🔲 Pending / Next Steps

- [x] **Phone par test** (P-08): Qwen3 4B phone par, text/failover commands tested (Session 8). Voice mic test baaki.

- [ ] Code Studio manifest entry confirm karna (AndroidManifest mein `<activity>` add hua kya)
- [ ] Edge TTS test karna live device pe
- [ ] Dynamic Island SYSTEM_ALERT_WINDOW permission flow test
- [ ] Auto-Fix loop end-to-end test
- [x] Gods Eye Phase 6: phone layout + WebView budget (Session 11). Baaki: Live voice se God's Eye mic test
- [ ] CampusVoice Google login fix (localhost pe email use karo — done Nov 3)

---

## 💡 How To Continue (Dusre session mein)

1. Is file ko pehle padho (CLAUDE.md)
2. Phir `docs/codex.md` — project structure samajhne ke liye
3. Phir `docs/guide.md` — detailed engineering guide
4. Phir `docs/task.md` — current pending task kya hai
5. **KABHI** `GeminiLiveWebSocket.kt` ya `JarvisConversationService.kt` mat chhuo
6. Build karna ho to sirf: `./gradlew termuxBuildDebug` (Termux pe)
