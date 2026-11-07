# Jarvis Project - Agent Engineering & Work Protocol Rules

All AI agents (Antigravity, Claude, Cursor, Copilot, etc.) working on this repository MUST strictly follow these rules at all times.

---

## 1. Numbered Step & Timestamp Work Tracker (`claude.work` / `WORK_LOG.md`)

- **Sequential Decimal Numbering**: Every task, operation, code change, or test must be tracked using a sequential decimal number (e.g., `[Step 1.0]`, `[Step 2.1]`, `[Step 3.43]`, `[Step 3.44]`).
- **Timestamping**: Every step must include an accurate date and timestamp (`[YYYY-MM-DD HH:mm:ss]`).
- **Standard Step Structure**:
  ```markdown
  ### [Step X.YY] - YYYY-MM-DD HH:mm:ss
  - **Task**: <Clear task title>
  - **Status**: <IN_PROGRESS | COMPLETED | BLOCKED | FAILED>
  - **Files Read / Modified**: <List of full file paths>
  - **Code Integrity Check**: <VERIFIED: No syntax errors, logic matches requirements>
  - **Details & Output**: <Summary of execution or changes>
  - **Next Step Pointer**: <[Step X.ZZ] - What needs to be done next>
  ```
- **Continuity Guarantee**: When starting or resuming work, ALWAYS read `claude.work` (or `WORK_LOG.md`) first. Identify the latest completed step and immediately continue from the specified `Next Step Pointer`.

---

## 2. Mandatory Code Reading & Verification Standard

- **Read Before Edit**: Always inspect the relevant files completely before making modifications.
- **Code Integrity Verification**:
  - Whenever code is read or modified, verify that:
    1. Brackets, braces, and syntax are balanced and error-free.
    2. Imports and dependencies are properly resolved.
    3. Null safety, exception handling, and edge cases are handled defensively.
    4. Existing functionality and comments are preserved.
- **Post-Change Verification**: After editing, verify that the code compiles cleanly (e.g. `./gradlew compileDebugKotlin`) and that no orphaned code blocks or regressions exist.

---

## 3. Sovereign 100% On-Device AI Architecture

- **Zero Cloud Dependency for Core Brain**: All core AI functions (voice, chat, tool calling, reasoning) must be capable of running 100% offline via local GGUF models (`llama.cpp` JNI).
- **Independent Engine Switches**: Maintain independent switches in Settings (`isGeminiEnabled`, `isOllamaEnabled`, `isLocalBrainEnabled`). Never hardcode forced cloud fallbacks when the user has disabled Gemini.
- **Persistent Offline Logging**: All user interactions, `<think> ... </think>` Chain-of-Thought reasoning, and executed action tools must be continuously logged into `/sdcard/Documents/Jarvis/offline_brain.md` and internal storage.

---

## 4. Multi-Location Sync

- Every major step, architectural update, and guide must be synchronized across:
  1. Project Root (`OFFLINE_BRAIN.md`, `claude.work`, `WORK_LOG.md`, `AGENTS.md`)
  2. Brain Session Artifacts (`JARVIS_OFFLINE_AUTONOMOUS_BRAIN_SPEC.md`)
  3. App Documentation (`docs/guides/OFFLINE_BRAIN.md`)
