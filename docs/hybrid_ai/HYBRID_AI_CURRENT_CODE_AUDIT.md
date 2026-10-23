# JARVIS HYBRID AI — CURRENT CODE AUDIT & ROOT-CAUSE ANALYSIS

**Document Version:** 1.0.0  
**Date:** October 18, 2026  
**Auditor:** Senior Android AI Architect & Integration Lead  
**Target Repository:** `C:\Users\Roshan\Documents\Jarvis`  
**Target Device Tested:** iQOO Z7 Pro 5G (`10BG1D01DA007QG` / vivo I2221, MediaTek Dimensity 7200, 8GB RAM, arm64-v8a)

---

## 1. Executive Summary & File Inventory

A line-by-line inspection of the Jarvis Android codebase was conducted to evaluate the current state of AI subsystems, voice pipelines, tool execution engines, and offline capabilities. 

### Audited File Inventory

| Subsystem | File Path | Primary Class / Role | Audit Status |
| :--- | :--- | :--- | :--- |
| **Local SLM Engine** | `app/src/main/java/com/jarvis/assistant/ai/local/LocalAiEngine.kt` | `LocalAiEngine` | **Critical Defect:** Fake inference; no native runtime |
| **Model Downloads** | `app/src/main/java/com/jarvis/assistant/ai/local/LocalModelManager.kt` | `LocalModelManager` | **Critical Defect:** Downloads fail; invalid URLs |
| **Model Config** | `app/src/main/java/com/jarvis/assistant/ai/local/LocalAiConfig.kt` | `LocalAiConfig`, `LocalModelDescriptor` | **Critical Defect:** Gated/broken HF links |
| **Offline Voice** | `app/src/main/java/com/jarvis/assistant/ai/local/OfflineVoiceConversationEngine.kt` | `OfflineVoiceConversationEngine` | **Fragmented:** Vosk + Regex works; LLM broken |
| **Model Routing** | `app/src/main/java/com/jarvis/assistant/ai/router/ModelRouter.kt` | `ModelRouter`, `AiModelMode` | **Architectural Gap:** Disconnected from live WebSocket |
| **Online Gemini** | `app/src/main/java/com/jarvis/assistant/network/GeminiLiveWebSocket.kt` | `GeminiLiveWebSocket` | **Fully Functional:** Live PCM, Bidi stream, Tool calling |
| **Service & Tools** | `app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt` | `JarvisConversationService` | **Coupled:** 30+ tools hardwired to Gemini WebSocket |
| **Command Engine**| `app/src/main/java/com/jarvis/assistant/automation/command/CommandExecutor.kt` | `CommandExecutor`, `CommandParser` | **Functional:** Regex-based intent execution |
| **Phone Control** | `app/src/main/java/com/jarvis/assistant/automation/phone/PhoneControlManager.kt` | `PhoneControlManager` | **Functional:** Flashlight, Apps, Volume, Wi-Fi |
| **Vector Memory** | `app/src/main/java/com/jarvis/assistant/ai/memory/VectorMemoryEngine.kt` | `VectorMemoryEngine` | **Functional:** RAG context builder working |

---

## 2. Root-Cause Analysis: Why Offline Local AI is Failing

During real-world testing on the physical device, selecting Offline AI or disconnecting Wi-Fi/Mobile Data resulted in either total silence, an error message ("Sir, my offline neural core encountered an issue"), or generic canned responses ("Sir, offline mode me Torch, Camera, Apps...").

Our audit identified **four fatal root causes**:

```
+-----------------------------------------------------------------------------------------+
|                                OFFLINE AI FAILURE CHAIN                                 |
+-----------------------------------------------------------------------------------------+
|                                                                                         |
| 1. LocalAiConfig.kt                                                                     |
|    Download URLs point to gated 401 repos or non-existent .bin files                    |
|             |                                                                           |
|             v                                                                           |
| 2. LocalModelManager.kt                                                                 |
|    Downloads fail immediately; isModelReady() returns false for all models             |
|             |                                                                           |
|             v                                                                           |
| 3. LocalAiEngine.initialize()                                                           |
|    Halts execution with LocalEngineState.Error; never creates engine                    |
|             |                                                                           |
|             v                                                                           |
| 4. Missing Native Inference Engine                                                      |
|    LocalAiEngine.kt lines 82-84: MediaPipe GenAI LlmInference code is COMMENTED OUT!   |
|    There is NO native .so (llama.cpp, ONNX, MediaPipe) loaded or executed               |
|             |                                                                           |
|             v                                                                           |
| 5. executeInference() Fallback                                                          |
|    Bypasses prompt formatting; evaluates static hardcoded string when {...}             |
|                                                                                         |
+-----------------------------------------------------------------------------------------+
```

### Root Cause 1: Missing Native Inference Runtime in `LocalAiEngine.kt`
In `LocalAiEngine.kt`, lines 80-87:
```kotlin
try {
    Log.i(TAG, "Loading local model weights into memory from ${modelFile.absolutePath}...")
    // In production MediaPipe setup:
    // LlmInference.createFromOptions(context, options)
    isModelLoaded = true
    _engineState.value = LocalEngineState.Ready
    Log.i(TAG, "Local AI Engine ready! Model: ${config.modelId}")
    return@withLock true
}
```
**Finding:** The engine sets `isModelLoaded = true` without initializing any native library or inference engine. Neither `llama.cpp` JNI (`libllama.so`), nor MediaPipe GenAI (`com.google.mediapipe:tasks-genai`), nor ONNX Runtime is actively linked in the compilation chain or executed in memory.

### Root Cause 2: Invalid & Gated Model URLs in `LocalAiConfig.kt`
In `LocalAiConfig.kt`, lines 26-52:
```kotlin
// Gemma 2 2B:
downloadUrl = "https://huggingface.co/google/gemma-2-2b-it-cpu-gpu/resolve/main/gemma-2-2b-it-gpu-int4.bin"
// Qwen 2.5 1.5B:
downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct/resolve/main/qwen2.5-1.5b-int4.bin"
// SmolLM2 1.7B:
downloadUrl = "https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct/resolve/main/smollm2-1.7b-int4.bin"
```
**Finding:** 
- The Google Gemma repository on HuggingFace is gated and requires an authenticated bearer token. Downloading it without headers returns `HTTP 401 Unauthorized`.
- The Qwen and SmolLM2 repositories do not contain `.bin` quantized weights at those URLs. HuggingFace returns `HTTP 404 Not Found`.
- Consequently, downloads fail immediately, leaving empty or zero-byte files in the app storage.

### Root Cause 3: Readiness Check Halts Initialization
In `LocalAiEngine.kt`, lines 72-78:
```kotlin
val modelFile = modelManager.getModelFile(config.modelId)
if (!modelManager.isModelReady(config.modelId)) {
    val errorMsg = "Local model '${config.modelId}' is not downloaded. Please download it in Settings."
    Log.w(TAG, errorMsg)
    _engineState.value = LocalEngineState.Error(errorMsg)
    return@withLock false
}
```
**Finding:** Because the download fails, `modelManager.isModelReady()` always returns `false`. The engine gets permanently stuck in `LocalEngineState.Error`.

### Root Cause 4: Hardcoded Keyword Fallback in `LocalAiEngine.executeInference()`
In `LocalAiEngine.kt`, lines 213-275:
```kotlin
private suspend fun executeInference(userPrompt: String, memoryContext: String, formattedPrompt: String): String {
    // Ignores formattedPrompt completely!
    val lower = normalized.lowercase(Locale.ROOT).trim()
    ...
    return when {
        lower.contains("hello") -> "Hello Roshan Sir! Main ready hoon..."
        lower.contains("kaise ho") -> "Main bilkul theek hoon Roshan Sir!..."
        lower.contains("who created you") -> "Mujhe Roshan Sir ne banaya hai..."
        ...
        else -> "Sir, offline mode me Torch, Camera, Apps ya Volume control karne ke liye command kahein."
    }
}
```
**Finding:** Even if the method runs, it completely discards the LLM input prompt and executes a hardcoded string `when` block. It has zero capability for reasoning, function calling, or answering free-form conversational questions offline.

---

## 3. The Tool Calling & Action Execution Gap

### Gemini Online Tool Execution
In `JarvisConversationService.kt`, lines 1831-2800:
`handleToolCallExecution(callId, name, args)` directly manages over 30 device control and automation capabilities:
- Flashlight, volume, brightness, battery telemetry
- App launching and Chrome web navigation
- Accessibility service clicks, typing, scrolling, and key events
- WhatsApp messaging and call automation
- Phone contact dialer
- Memory addition, search, and deletion
- YouTube playback and ad-skipping
- GodsEye visual surveillance camera selection

**The Critical Architectural Problem:**
`handleToolCallExecution` is tightly coupled to `liveWebSocket?.sendToolResponse(callId, name, result)`. It can only receive calls dispatched by Google's Gemini server over the bidirectional WebSocket, and sends results back exclusively through the WebSocket.

### Offline Voice Pipeline Gap
In `OfflineVoiceConversationEngine.kt`, lines 398-644:
When the user speaks offline:
1. Speech is transcribed by Vosk or Android Native SpeechRecognizer.
2. It runs regex pattern matching via `CommandParser.parse(clean)`.
3. If regex matches (e.g., "torch on", "open camera"), it calls `PhoneControlManager`.
4. If regex does NOT match, it delegates to `ModelRouter` -> `LocalAiEngine`.
5. Because `LocalAiEngine` is broken and cannot output tool calls, **the offline pipeline cannot execute complex or multi-turn tool commands**.

```
+------------------------------------------------------------------------------------------+
|                              CURRENT TOOL CALLING SILOS                                  |
+------------------------------------------------------------------------------------------+
|                                                                                          |
| [ Gemini Live WebSocket ] -------> [ handleToolCallExecution() ] ----> [ Native Actions ]|
|                                                  ^                                       |
|                                                  | (Tightly coupled to WebSocket)        |
|                                                                                          |
| [ Vosk / SpeechRecognizer ] -----> [ CommandParser Regex ] ---------> [ PhoneControl ]   |
|                                                  |                                       |
|                                                  v                                       |
|                                    [ LocalAiEngine (Broken) ]                            |
|                                                  |                                       |
|                                                  x (NO TOOL CALLING CAPABILITY)          |
|                                                                                          |
+------------------------------------------------------------------------------------------+
```

---

## 4. Voice Pipeline Subsystem Audit

### 1. Online Voice Pipeline (Gemini Live)
- **Audio Capture:** 16kHz, 16-bit Mono Linear PCM streamed via `AudioRecord` in chunks of 512 samples.
- **Protocol:** Bidirectional WebSocket to `wss://generativelanguage.googleapis.com/.../BidiGenerateContent`.
- **Playback:** Low-latency `AudioTrack` playing 24kHz raw PCM directly from Gemini audio responses.
- **Barge-in:** Energy-based Voice Activity Detection (VAD) instantly flushes `AudioTrack` and notifies Gemini via client content turns.
- **Stability:** Excellent; reconnection, API key verification, and backoff logic are mature.

### 2. Offline Voice Pipeline
- **Continuous ASR:** Dual-engine architecture:
  - Primary: `AndroidNativeSpeechRecognizer` (on-device continuous mode).
  - Secondary fallback: Vosk small-en-us model (`vosk-model-small-en-us-0.15`).
- **TTS Playback:** System `TextToSpeech` with custom sentence-boundary chunking (`.`, `!`, `?`, `\n`).
- **Shortcoming:** PocketTTS was analyzed during MYRA REA, but native `.so` files were missing 64-bit runtime dependencies. System TTS currently serves as the functional offline voice output.

---

## 5. Audit Matrix & Fix Requirements

| Component | Current Defect | Impact | Fix Requirement |
| :--- | :--- | :--- | :--- |
| **Inference Engine** | Commented-out placeholder in `LocalAiEngine.kt` | Offline AI cannot generate tokens | Integrate `llama.cpp` precompiled `libllama.so` (arm64-v8a) with Kotlin JNI bindings. |
| **Model Weights** | 401 Unauthorized / 404 Not Found URLs | Model download fails | Provide verified, direct GGUF links (Qwen 2.5 1.5B Instruct Q4_K_M, SmolLM2 1.7B Instruct Q4_K_M). |
| **Tool Dispatcher** | Trapped inside `JarvisConversationService` | Offline & Hermes models cannot trigger tools | Decouple `handleToolCallExecution` into a singleton `JarvisToolDispatcher` reusable by all AI providers. |
| **Tool Calling Parser** | None in `LocalAiEngine` | Models output raw text without executing actions | Implement ChatML `<tool_call>` parser to detect function calls from local models. |
| **Model Router** | Divergent routing logic for WebSocket vs Text | Inconsistent state transitions | Unify under a single `AiProvider` interface with `AiFallbackController`. |

---

## 6. Conclusion

The Jarvis architecture already possesses all necessary native capabilities (device control, accessibility automation, background services, memory management, and voice streaming). The failure of the offline experience is purely due to the **absence of an actual inference engine (`llama.cpp`)** and the **tight coupling of tool execution to the Gemini WebSocket**. 

Once `llama.cpp` JNI is linked and tools are exposed via a unified `JarvisToolDispatcher`, Jarvis will achieve complete offline autonomy.
