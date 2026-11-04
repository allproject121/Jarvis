# Task.md — Jarvis Task Tracker

> **Purpose:** Kaunsa kaam chal raha hai, kaunsa pending hai, kaunsa done hai.  
> **Rule:** Jab bhi kaam shuru karo, yahan `[IN PROGRESS]` mark karo. Khatam ho to `[DONE]` karo.  
> **Last Updated:** Nov 3, 2026

---

## 🔴 Active / In Progress

*(Abhi koi active task nahi — next session mein jo kaam shuru ho, yahan mark karo)*

---

## 🟡 Pending (Next Up)

### P-09 — Phone test: AndroLLM merge + feature fixes (Nov 3–4)
**Priority:** High
**Install over old build (uninstall NAHI)** taaki memory/chat data migrate ho.
1. Memories ki count same rahe (Memory screen); chat history dikhe; naya voice turn Chat tab mein ek hi baar aaye.
2. Driving: car BT connect → on; earbuds disconnect → on hi rahe; car disconnect → off; app restart → state wahi. Settings → Driving → Auto-reject + SMS → call karo → kat jaaye + SMS jaaye.
3. App Lock: FaceLock → WhatsApp lock → WhatsApp kholo → lock screen → unlock → wapas WhatsApp.
4. SOS test: asli location link aaye (New Delhi nahi).
5. Bolo: "battery kitni hai", "DND on karo", "YouTube par lo-fi search karo", "storage kitna bacha hai", "WhatsApp messages padho", "driving mode on karo".
6. Touch Guard arm → app band karo → phone hilao → notification/photo.
7. Bypass IDE: `scan`, `status` asli numbers; hataye gaye switches dikhne band.
**Rollback:** `backups/feature-fixes-2026-11-04/restore.ps1`, phir zaroorat ho to `backups/androllm-merge-2026-11-03/restore.ps1`.

### P-10 — Gemini Live handlers (protected file, user permission chahiye)
`JarvisConversationService.kt`: Live ke flashlight/search_youtube/call/send_whatsapp handlers ko dispatcher jaisa honest banana; VoiceGuard check Live par; `VoiceGuardPolicy.markOwnerVerified()` call; wake-word sensitivity setting wire karna.

### P-08 — Offline Brain device test
**Priority:** High
**Kya karna hai:** Nov 3 ka Offline Brain change phone par test karo.
**Test steps:**
1. Models screen → "Qwen3 4B (Jarvis Offline Brain)" download (2.33 GB) → select
2. Settings → Gemini API key galat daal do → voice session start
3. Expected: error loop nahi; event "Gemini unavailable (API_KEY_INVALID). Offline Brain is now active."; status "OFFLINE – Local AI"
4. Bolo "torch on karo" → torch on hona chahiye (tool call offline)
5. Text chat mein bhi sawaal → offline jawab aana chahiye
6. Sahi key save karo → watcher turant Gemini reconnect kare (blocked key badal gayi)
7. Settings → Engines → toggle OFF → purana behaviour (sirf error banner)
**Rollback:** `backups/offline-brain-2026-11-03/restore.ps1`

### P-01 — Code Studio Manifest Entry Verify
**Priority:** High  
**Kya karna hai:** `AndroidManifest.xml` mein `CodeStudioActivity` ka `<activity>` entry sahi se add hua kya confirm karo. Agar nahi hua to add karo.  
**Expected manifest entry:**
```xml
<!-- Jarvis Code Studio: AI coding IDE (same task, Jarvis theme) -->
<activity
    android:name=".codestudio.CodeStudioActivity"
    android:theme="@style/Theme.Jarvis.Fullscreen"
    android:configChanges="orientation|screenSize|keyboardHidden"
    android:windowSoftInputMode="adjustResize"
    android:exported="false" />
```

---

### P-02 — Edge TTS Live Device Test
**Priority:** Medium  
**Kya karna hai:** Edge TTS (Maria voice) real phone pe test karo.  
**Test steps:**
1. Settings → Voice → TTS Engine → "Edge Neural" select karo
2. Voice: "hi-IN-SwaraNeural" choose karo
3. Test button dabao — Maria ki awaaz aani chahiye
4. Gemini Live voice nahi badlni chahiye (protected)

---

### P-03 — Dynamic Island SYSTEM_ALERT_WINDOW Permission
**Priority:** Medium  
**Kya karna hai:** Dynamic Island overlay SYSTEM_ALERT_WINDOW permission request properly karta hai kya test karo.  
**Test steps:**
1. Settings → Customization → Dynamic Island toggle ON karo
2. Permission dialog aana chahiye
3. Permission do → top mein Island dikhe
4. Jarvis existing overlay ke saath conflict nahi hona chahiye (ek hi dikhe)

---

### P-04 — Auto-Fix Loop End-to-End Test
**Priority:** Medium  
**Kya karna hai:** Code Studio mein Auto-Fix feature test karo.  
**Test steps:**
1. Code Studio → koi project kholo
2. Intentional error likho (e.g., syntax error)
3. "Fix with AI" tap karo
4. Auto-Fix cycle dekho: analyze → patch → apply → validate
5. "AUTO-FIX VERIFIED" ya "VALIDATION FAILED" message aana chahiye

---

### P-05 — Gods Eye Phase 6: Mobile Touch & WebView
**Priority:** Medium  
**Source:** `docs/guides/RESUME.md`  
**Kya karna hai:**
- Touch gestures test karo (pinch, pan, rotate)
- Cesium globe WebView responsiveness check karo
- Zoom aur orientation handling
- Tactical HUD overlay controls mobile pe
- WebView cache/render memory optimize karo
- Orb integration options evaluate karo

---

### P-06 — Uncensored Local AI (Feasibility → Implementation)
**Priority:** Low  
**Background:** User ne 2.7 mein poocha tha feasibility ke baare mein.  
**Kya karna hai:** Agar user green light de to:
1. Dolphin / abliterated Llama/Qwen GGUF model download
2. `OfflineVoiceConversationEngine.kt` mein alternate model path add karo
3. Settings mein "Uncensored Mode" toggle add karo
4. ⚠️ **Start mat karo bina user ke explicitly bolne ke**

---

### P-07 — RedMagic Jarvis Sync
**Priority:** Low  
**Kya karna hai:** RedMagic version ke saath latest changes sync karo. User ke saath discuss karna hai ki exactly kya sync karna hai.

---

## ✅ Done

### D-01 — AndroLLM Features Merge into Jarvis ✅
**Completed:** Oct 31, 2026  
**Kya kiya:** AndroLLM ke missing features (memory pipeline, voice assistant, customization options) Jarvis ke existing screens mein merge kiye. Koi alag app/tab nahi banaya.

---

### D-02 — BYPASS IDE → Code Studio Implementation ✅
**Completed:** Nov 1, 2026  
**Kya kiya:** BYPASS IDE ke saare features Jarvis mein Code Studio ke naam se implement kiye:
- AI Code Editor (syntax highlighting, find/replace, tabs)
- AI Edit + Auto-Fix
- Project Generator (AI + offline templates)
- Live Preview Server (localhost:8080)
- Interactive Terminal (PTY, root toggle, safety guards)
- AI Modes in Chat (Consultation/Autonomous/Full-Stack)
- Maria Hinglish persona + Edge TTS
- Dynamic Island overlay
- News/IP/Wikipedia tools
- 3 parallel agents chalaaye

---

### D-03 — CampusVoice Login Fix ✅
**Completed:** Nov 3, 2026  
**Kya kiya:** Google sign-in ka localhost pe 404 error fix kiya. Ab friendly message aata hai. Email login properly kaam karta hai.  
**File:** `campusvoice-source_v3/src/campus/context/AuthContext.tsx`

---

### D-04 — Documentation Organization ✅
**Completed:** Nov 3, 2026  
**Kya kiya:** 6 documentation files banaye:
- `gev.md` — 15 Gods Eye docs merged (182k chars)
- `CLAUDE.md` — Work log
- `codex.md` — Master index
- `antigravity.md` — Antigravity reference
- `prompt.md` — Prompts history
- `task.md` — Task tracker (yahi file)

---

### D-05 — Offline Brain (Gemini fail → Qwen3 4B) ✅
**Completed:** Nov 3, 2026
**Kya kiya:** Qwen3 4B GGUF default offline model; `</tool_call>` stop-word parse bug fix; Gemini text/voice fail hone par offline failover + 5 min cooldown + rejected-key guard; Settings toggle. Details `CLAUDE.md` Session 4. Device test pending (P-08).

---

## 📊 Stats

| Status | Count |
|---|---|
| 🔴 Active | 0 |
| 🟡 Pending | 8 |
| ✅ Done | 5 |
| **Total** | **13** |

---

## 📝 Task Template

Naya task add karne ke liye:

```markdown
### P-XX — [Task Name]
**Priority:** High / Medium / Low
**Kya karna hai:** [description]
**Files:** [relevant files]
**Test steps:** (agar applicable ho)
1. ...
```
