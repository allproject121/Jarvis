# JARVIS PROJECT - PENDING WORK, FUTURE ROADMAP & IDEAS VAULT (PENDING_WORK.md)

> **MANDATORY AGENT RULE**:
> All AI agents (Antigravity, Claude, Cursor, Copilot, Codex, Local LLM, etc.) working on this repository **MUST** read this file at the start of any session.
> - **Immutability**: Never delete, remove, or alter existing ideas or backlog tasks without explicit, unambiguous user confirmation.
> - **Automatic Idea & Task Auto-Save**: Whenever the user mentions a new idea, feature request, or plan that is not implemented in the current turn—or if the user forgets how to implement it, or if any pending task is left incomplete—the agent **MUST AUTOMATICALLY append and save** it to this file before finishing the turn.
> - **Proactive Reminder**: Whenever the user asks or when relevant in future sessions, proactively inform and remind the user of these stored ideas and pending tasks.

---

## 📌 Active Pending Tasks & Ideas Backlog

### [PENDING-001] Official YouTube Channel Integration
- **Category**: UI / Social & Developer Links
- **Date Added**: 2026-11-07
- **Priority**: Medium
- **Status**: PENDING_URL
- **Description**: Add official YouTube channel link and interactive launch button in the About section of Settings (`SettingsFragment.kt` and `fragment_settings.xml`) once the official channel handle/URL is provided by Roshan.
- **Dependencies**: YouTube channel URL from Roshan (`@tiwariroshan...` or channel ID).

---

### [PENDING-002] 1-Click Sovereign Local Brain GGUF Model Downloader & Execution Pipeline
- **Category**: Sovereign Local AI / On-Device Core
- **Date Added**: 2026-11-07
- **Priority**: High
- **Status**: IN_PROGRESS / PENDING_DEVICE_VERIFICATION
- **Description**: Verify end-to-end download and live offline execution of Qwen 2.5 (0.5B / 1.5B) and Llama 3.2 (1B) GGUF models directly on physical phone hardware via the Home HUD Bento Card and `LocalLlamaEngine`.
- **Target**: Zero-cloud autonomy with real-time token/sec telemetry display.

---

### [PENDING-003] Vosk & Whisper Offline Voice Wake-Word Pipeline
- **Category**: Voice Intelligence / Audio Engine
- **Date Added**: 2026-11-07
- **Priority**: High
- **Status**: PENDING_INTEGRATION
- **Description**: Seamless offline ASR using embedded Vosk wake-word ("Hey Jarvis" / "Jarvis") coupled with Whisper-tiny/base offline speech-to-text and PocketTTS / Android Speech synthesis for 100% cloudless voice conversation.

---

### [PENDING-004] Custom 3D Companion Wardrobe, Skins & Shader Materials
- **Category**: 3D Graphics / Three.js Companion Studio
- **Date Added**: 2026-11-07
- **Priority**: Medium
- **Status**: CONCEPTUAL_BACKLOG
- **Description**: Enable dynamic skin and apparel switching for 3D companions (Hinata, Mora, Stark Orb) in Customization Studio. Include holographic wireframe shader mode and dynamic glowing particle aura.

---

### [PENDING-005] Holographic HUD Floating System Overlay & God's Eye 3D Globe Sync
- **Category**: UI / UX System Overlay
- **Date Added**: 2026-11-07
- **Priority**: Medium
- **Status**: BACKLOG
- **Description**: Build a system-wide floating holographic bubble widget that renders live Arc Reactor pulsing diagnostics and quick voice action triggers over any Android app.

---

### [PENDING-006] NDK Battery, Thermal & Big-Core Scheduler Optimization
- **Category**: Native C++ / NDK Performance
- **Date Added**: 2026-11-07
- **Priority**: Medium
- **Status**: BACKLOG
- **Description**: Implement thermal throttling detection and dynamic thread affinity migration in `llama_jni.cpp` to prevent device overheating during extended multi-turn offline reasoning sessions.

---

## 📝 Idea Vault (Quick Capture Template)

*When adding new ideas or postponed tasks, use the following template:*

```markdown
### [PENDING-XXX] <Idea / Task Title>
- **Category**: <AI Core | UI/UX | 3D Graphics | Voice | Security | Tools>
- **Date Added**: YYYY-MM-DD
- **Priority**: <Low | Medium | High | Critical>
- **Status**: <IDEA | PLANNING | PENDING_ASSETS | POSTPONED>
- **Description**: <Detailed explanation of the idea or workflow>
- **Implementation Notes**: <Technical hints, proposed classes or libraries>
```
