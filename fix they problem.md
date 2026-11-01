# FIX THEY PROBLEM — Offline Brain Audit (Real Status + Bugs)

**Date:** 31 Oct 2026 • **Checked by:** Live code read of `ai/local`, `ai/provider`, `ai/router` + HTTP URL checks
**Scope:** Offline brain only (LocalAiEngine, LocalPhoneProvider, LocalModelManager, ModelRouter, AiFallbackController, OfflineVoiceConversationEngine, ToolCallParser, JarvisToolDispatcher)

---

## 1. WORK OR NOT? — Final Verdict (Short Answer)

| Docs (old audit MD) say | Real code says |
|---|---|
| "Fake inference, no native runtime" | ✅ **WRONG now** — `LocalPhoneProvider.kt` uses **real llama.cpp** (`net.ladenthin:llama-android:5.1.0`, `LlamaModel`) |
| "Downloads fail, invalid URLs" | ✅ **3 of 4 URLs fixed & live (HTTP 200)**, resume + GGUF header validation added ❌ **1 URL still broken (401)** |
| "No offline conversational AI" | ✅ Full offline loop exists: Vosk ASR → commands → RAG → local LLM → TTS |

**Verdict: Offline brain is REAL and mostly works — IF you download the weights. But 10 bugs found, 3 are critical (B1, B2, B4).**

The old MD docs (`docs/hybrid_ai/HYBRID_AI_CURRENT_CODE_AUDIT.md`) are **outdated** — they describe the pre-rewrite state. Update or delete them to avoid confusion.

---

## 2. CONFIRMED BUGS (Found & How to Fix)

### 🔴 B1 — Hermes model download URL is DEAD (HTTP 401) — VERIFIED LIVE
- **File:** `LocalAiConfig.kt` line ~55
- **Problem:** `https://huggingface.co/NousResearch/Hermes-2-Pro-Qwen-1.5B-GGUF/...` returns **401 Unauthorized** (repo is gated). I tested all 4 URLs with curl: Qwen 0.5B ✅ 200, Qwen 1.5B ✅ 200, SmolLM2 ✅ 200, **Hermes ❌ 401**.
- **User impact:** Select Hermes → download fails instantly with "HTTP error 401".
- **Fix:** Remove Hermes from `AVAILABLE_MODELS`, or replace with an ungated mirror (e.g. `bartowski/Hermes-2-Pro-Qwen-1.5B-GGUF`) after verifying with `curl -I`.

### 🔴 B2 — TTS speaks raw tool-call JSON; JSON junk saved to chat history
- **File:** `LocalPhoneProvider.kt` `streamResponse()` (~line 160-185)
- **Problem:** Every token is emitted (`emit(AiStreamChunk(token))`) **BEFORE** tool-call detection runs. When the model outputs `{"name": "set_flashlight", ...}` the user **hears the JSON spoken aloud**, and `OfflineVoiceConversationEngine` persists it into `recentTurns` + chat repository.
- **Fix (2 lines of logic):**
  1. While accumulating, hold emission: if `tokenAccumulator` starts looking like a tool call (contains start tag), stop emitting tokens; only emit `toolResult` message.
  2. In `OfflineVoiceConversationEngine` after stream completes: `finalReply = ToolCallParser.cleanConversationalText(finalReply)` before saving/speaking.

### 🔴 B3 — Double-tap "Download" corrupts the model file
- **File:** `LocalModelManager.downloadModel()` + `SettingsFragment` line ~739
- **Problem:** No in-progress guard. Tapping Start Download twice (or re-entering Settings) launches 2 coroutines writing the **same `.download.tmp`** → corrupted GGUF.
- **Fix:** Add a `private val activeDownloads = MutableSet<String>()`; if `modelId` in set → return early; remove in `finally`.

### 🔴 B4 — `LocalAiConfig` is cosmetic (nothing applies) + model-ID mismatch
- **File:** `LocalPhoneProvider.initialize()` / `LocalAiEngine.initialize()`
- **Problems (verified in code):**
  1. Threads hardcoded `4`, `nPredict` hardcoded `384`, temp/topK/topP hardcoded `0.7/40/0.9` — `LocalAiConfig` fields are **never read** by the provider.
  2. **Context window (`contextWindowLimit`) is NEVER set** on `ModelParameters`. The prompt = system + RAG memory + learned corrections + habit + MUSE + **24 tool schemas** + 4 history turns. If llama.cpp default context is small, the prompt is **silently truncated** → model ignores tools/memory, answers nonsense. This is the classic "offline model feels dumb" cause.
  3. `LocalAiEngine.initialize(config)` checks readiness of `config.modelId`, but the provider loads `activeModelId` — different IDs → wrong error message shown to user.
- **Fix:** Pass config into `initialize(config)`, apply `.setThreads(config.threads)`, `.withNPredict(config.maxTokens)`, and set context length explicitly (check jllama 5.1.0 `ModelParameters` setter — see §4 unverified). Standardize on `activeModelId` everywhere.

### 🟠 B5 — Default model 0.5B is too weak for 24-tool JSON calling
- **File:** `LocalAiConfig.DEFAULT_MODEL_ID = MODEL_QWEN_0_5B`; dispatcher has **24 tool schemas** (set_flashlight, open_app, whatsapp, accessibility, smart home, task manager…).
- **Problem:** A 0.5B Q4 model will rarely emit valid tool JSON → offline actions feel broken ("torch on" works only because of the regex command layer, not the LLM).
- **Fix:** Default to `MODEL_QWEN_1_5B` (best Hindi/Hinglish + tool calling per your own docs), and/or disable tool schemas when 0.5B is active. Also consider trimming to ~8 most-used tools for offline prompts.

### 🟠 B6 — `AiFallbackController` is DEAD CODE (never wired)
- **Verified:** `grep -rn "AiFallbackController"` across `app/src/main/java` → **zero usages** outside its own file.
- **Problem:** The README/feature list claims "Zero-Downtime Fallback Controller". Reality: failover lives inside `ModelRouter` (ChatGPT/Gemini → LocalHost Ollama → Offline). Two competing sources of truth = drift. Bonus latent bug: its `onAvailable()` would flip a **user-forced Offline mode back to Gemini** when network returns, if anyone ever wires it.
- **Fix:** Either delete `AiFallbackController.kt` or wire it and remove router-internal fallback. Don't keep both.

### 🟠 B7 — Default Ollama model tag looks invalid
- **File:** `ModelRouter.kt:45` + `AppPreferences.kt:53` → `DEFAULT_LOCAL_MODEL = "qwen3.8:27b"`
- **Problem:** No standard Ollama tag named `qwen3.8:27b` exists (real tags look like `qwen3:8b`). Local Host mode will 404 → always silently falls to offline. Also "27b" on a phone-bridged host is a strange default.
- **Fix:** Use a real, small default (e.g. `qwen3:8b` or `llama3.1:8b`) and document `adb reverse tcp:11434 tcp:11434`.

### 🟡 B8 — Sentence-split TTS chops on every "."
- **File:** `OfflineVoiceConversationEngine.handleOfflineUtterance()` (~line 690)
- **Problem:** Speaks on ANY ".", so "3.14", "Mr.", "approx." become 3 separate utterances → robotic, choppy voice. Also amplifies B2 (JSON punctuation triggers speech mid-junk).
- **Fix:** Split on sentence-ending pattern `(?<=[.!?])\s+(?=[A-Z])` or minimum 80-char chunks, instead of raw `contains(".")`.

### 🟡 B9 — Offline brain can run only ONE tool per reply, then stops
- **File:** `LocalPhoneProvider.streamResponse()` — `break` after first tool dispatch.
- **Fix (bigger task):** Feed tool result back into the model and continue generation (agent loop) — at least allow 2 sequential tool calls. Low priority; document as known limitation.

### 🟡 B10 — Minor issues (5-min fixes)
1. `LocalPhoneProvider.isReady()` reads the GGUF file header on EVERY call (file I/O; may hit main thread) → cache result.
2. Download completion notification is never cleared (`cancel(NOTIF_ID)` missing).
3. `LocalAiEngine` never sets `Error` state on generation exceptions (stays `Ready`).
4. `enableGpuAcceleration`, `topP` in `LocalAiConfig` — unused, remove or wire.
5. `downloadSizeBytes` hardcoded — if HF file changes, `isModelReady` (75% threshold) may mis-verify → read `Content-Length` instead.

---

## 3. Priority Fix Order (Minimum Effort → Maximum Impact)

1. **B3** download guard (10 min) — prevents silent corruption, biggest "offline feels broken" cause.
2. **B2** JSON-junk TTS/history filter (20 min) — biggest *audible* quality win.
3. **B1** remove/replace Hermes URL (5 min).
4. **B4** wire LocalAiConfig + set context length (30 min) — biggest *intelligence* win.
5. **B5** switch default model to Qwen 1.5B (5 min).
6. **B6/B7** delete dead controller, fix Ollama tag (10 min).
7. B8/B9/B10 — polish.

---

## 4. WHAT I COULD NOT VERIFY (Missing / Un-checked — check next)

| Item | Why unverified | How to check |
|---|---|---|
| jllama 5.1.0 exact API (`ModelParameters` context setter name, `LlamaModel.getLlamaCppBuildInfo()`, `InferenceParameters.of()`) | Gradle cache for the AAR not found locally on this machine | Build once (`gradlew :app:assembleDebug`) — compiler will confirm; browse the jar in `~/.gradle/caches` |
| Actual runtime crash/quality on device (RAM, thermal, tokens/sec) | Requires physical device run with weights | Test on your iQOO Z7 Pro; watch `adb logcat -s LocalPhoneProvider LocalModelManager` |
| Vosk ASR accuracy for Hindi/Hinglish offline | Depends on bundled `model-en-us` assets (English-only model seen in assets!) → **likely weak Hindi offline STT; consider adding `vosk-model-small-hi`** | Speak Hindi with Wi-Fi off and check transcript |
| Whether `AiTextSynthesizer` (Gemini REST fallback) is truly offline-safe | Out of offline-brain scope | Separate audit |
| Hermes alternate mirror URL validity | Needs online check of e.g. `bartowski` repo | `curl -I <url>` before swapping |
| Old docs in `docs/hybrid_ai/*` | They contradict current code | Update after fixes land |

---

## 5. Ek Line Me Summary (Hinglish)

Offline brain **ab sach me kaam karta hai** (llama.cpp + GGUF downloads + resume — docs purane hain), **lekin**: Hermes download link 401 mara hua hai, double-tap download file corrupt kar sakta hai, tool-call JSON bolke sunai deta hai (TTS + chat history me kachra), aur LocalAiConfig ka koi setting apply hi nahi hoti — context window set hona sabse zaroori hai warna model dumb lagega. Ye 4 fix karne ke baad offline mode solid ho jana chahiye.
