# Jarvis Android: APK Size Reduction & On-Demand Resource Pack Architecture

**Document Version:** 1.0.0  
**Date:** November 7, 2026  
**Target Hardware:** Physical Device `10BG1D01DA007QG` (Android 14 / SDK 34, Vivo/iQOO I2221)  
**Primary Objective:** Reduce Jarvis APK size from **~491 MB to ~40-45 MB** using an On-Demand Dynamic Download Architecture (Free Fire Style Resource Hub) tailored for local/offline workflows.

---

## 1. Executive Summary & Context

Is document me Jarvis Android app ke APK size ko dramatically optimize karne ka complete blueprint darj hai. 

Pehle ke builds me heavy offline models (Vosk speech model, God's Eye Cesium 3D map data, duplicate 3D meshes, dual-ABI native libraries) ko APK ke andar bundle karne ki wajah se app ka size **491 MB** ho gaya tha.

Is architecture ke mutabik:
- **Base APK:** Sirf **~40–45 MB** ka hoga (Core UI, Cloud LLM, Tools, Navigation, Settings).
- **Heavy Resources & Models:** Free Fire / BGMI ke "Download Center" ki tarah app ke andar se on-demand download ya local PC se sync honge.

---

## 2. Reverse Engineering Analysis: Myra (Maya v5.2.34) APK

Myra AI (`com.getmaya.android` v5.2.34) ko jab decode kiya gaya tha, uska APK size sirf **~28 MB** tha. Decompiled sources (`C6500x91.java`, `U4.java`, Apktool decompiled layouts) se pata chala ki unhone size kaise kam rakha tha:

### 2.1. Dynamic / Cloud-Loaded Playbooks
- Saare complex skills (`daily-briefing.md`, `whatsapp-pro.md`, `call-secretary.md`, `music-dj.md`, `phone-care.md`) APK ke andar nahi the.
- Class `C6500x91.java` (lines 166–170) runtime par GitHub CDN (`https://cdn.jsdelivr.net/gh/HunterisLive-1/mobile-maya-skills@main/`) se direct fetch karti thi.

### 2.2. Ultra-Lightweight Quantized TFLite Models (Assets: ~7.3 MB Total)
Myra ne koi heavy Vosk model (100 MB+) ya GGUF model APK me bundle nahi kiya tha:
- `silero_vad.tflite`: **630 KB** (Voice Activity Detection)
- `hey_maya.tflite` / `wake_up_maya.tflite`: **~1.2 MB** (Wake word detection)
- `embedding_model.tflite`: **1.27 MB**
- Heavy speech synthesis cloud TTS ya tiny C++ library (`libpocket_tts.so` <1 MB) par offloaded thi.

### 2.3. Selective Native Libraries (`lib/` - ~31 MB Uncompressed / ~12 MB Compressed)
- 32-bit emulator/older ABIs (`x86`, `x86_64`) ko poori tarah exclude kiya gaya tha.
- Sirf modern `arm64-v8a` aur minimal `armeabi-v7a` targeted the.

### 2.4. Single DEX + Aggressive R8 / ProGuard Shrinking (`classes.dex`: ~11 MB)
- `minifyEnabled true` aur `shrinkResources true` enabled tha.
- Code me dead libraries aur debug symbols stripped the, jisse koi multidex bloat nahi tha (Single `classes.dex`).

---

## 3. Jarvis Ke Current APK Ka Size Breakdown (~491 MB)

Deep scan aur uncompressed ZIP analysis ke anusaar Jarvis me bloat kahan hai:

```
┌────────────────────────────────────────────────────────────────────────┐
│                      JARVIS APK SIZE BREAKDOWN                         │
├──────────────────────────┬──────────────┬──────────────────────────────┤
│ Folder                   │ Size         │ Major Space Consumers        │
├──────────────────────────┼──────────────┼──────────────────────────────┤
│ 1. assets/               │ ~274.2 MB    │ • Vosk STT Model (85 MB)     │
│                          │              │ • Guardian TFLite (67.7 MB)  │
│                          │              │ • God's Eye Cesium (50 MB)   │
│                          │              │ • Duplicate 3D Meshes (20 MB)│
├──────────────────────────┼──────────────┼──────────────────────────────┤
│ 2. lib/ (Native .so)     │ ~185.6 MB    │ • arm64-v8a: 130.8 MB        │
│                          │              │ • armeabi-v7a (32-bit): 54.7M│
│                          │              │ • Duplicate ONNX (37 MB)     │
├──────────────────────────┼──────────────┼──────────────────────────────┤
│ 3. classes*.dex (Code)   │ ~105.0 MB    │ • 21 Multidex files          │
│                          │              │   (Debug build me R8 OFF)    │
└──────────────────────────┴──────────────┴──────────────────────────────┘
```

---

## 4. Free Fire Style "Jarvis Resource Hub" (On-Demand Downloader)

Free Fire aur BGMI ki tarah Jarvis ke andar ek dedicated **"Resource & Model Download Center"** hoga. 

### 4.1. UI Layout & Architecture
```
┌──────────────────────────────────────────────────────────────┐
│  📦 JARVIS RESOURCE CENTER                                    │
│  Base APK: 42 MB  |  Downloaded: 0 MB  |  Free Storage: 85 GB │
├──────────────────────────────────────────────────────────────┤
│  [ TABS: 🧠 AI Brains  |  🎙️ Voice  |  🧍‍♀️ 3D Avatars  |  🌍 Maps ] │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  🧠 AI BRAINS & LOCAL LLMs                                   │
│  ──────────────────────────────────────────────────────────  │
│  ◈ Qwen 2.5 1.5B Instruct (GGUF Q4_K_M)                      │
│    Size: 980 MB  |  RAM: ~2.1 GB  |  Local Tool Calling     │
│    Status: [ 📥 DOWNLOAD ]  or  [ 🔄 SYNC FROM PC ]          │
│                                                              │
│  ◈ SmolLM2 360M (Fast Mini Brain)                            │
│    Size: 320 MB  |  RAM: ~800 MB  |  Battery Friendly        │
│    Status: [ 📥 DOWNLOAD ]                                   │
│                                                              │
│  ◈ PC Ollama Bridge (Local PC Brain - 0 MB Phone Storage)    │
│    Size: 0 MB Phone Storage (Streams over Local Wi-Fi)       │
│    Status: [ ⚡ CONNECT TO PC (192.168.1.x:11434) ]          │
│                                                              │
├──────────────────────────────────────────────────────────────┤
│  🎙️ OFFLINE VOICE & SPEECH PACKS                             │
│  ──────────────────────────────────────────────────────────  │
│  ◈ Vosk Speech Recognizer (English / Hinglish STT)          │
│    Size: 85 MB  |  Latency: <200ms  |  100% Offline Speech   │
│    Status: [ 📥 DOWNLOAD PACK ]                              │
│                                                              │
│  ◈ PocketTTS Offline Neural Voice (Hindi/English 24kHz)      │
│    Size: 18 MB  |  Latency: ~80ms  |  Native C++ JNI         │
│    Status: [ 📥 DOWNLOAD PACK ]                              │
│                                                              │
├──────────────────────────────────────────────────────────────┤
│  🧍‍♀️ 3D VIRTUAL COMPANIONS                                    │
│  ──────────────────────────────────────────────────────────  │
│  ◈ Mora 3D Cyberpunk Companion + 11 Motion Reactions        │
│    Size: 12 MB  |  Three.js WebGPU / Canvas Stage            │
│    Status: [ 📥 DOWNLOAD PACK ]                              │
│                                                              │
│  ◈ Maya / Jett Tactical Character                            │
│    Size: 6 MB  |  Lightweight Rigged Avatar                  │
│    Status: [ 📥 DOWNLOAD PACK ]                              │
│                                                              │
├──────────────────────────────────────────────────────────────┤
│  🌍 TACTICAL MAPS & GOD'S EYE FEEDS                          │
│  ──────────────────────────────────────────────────────────  │
│  ◈ 3D Globe & Satellite Offline Bundle                       │
│    Size: 45 MB  |  Cesium Shaders & Global Feeds             │
│    Status: [ 📥 DOWNLOAD PACK ]                              │
└──────────────────────────────────────────────────────────────┘
```

Har pack ke aage:
- **Download Button / Cancel**
- **Live Progress Bar (0% - 100%) & Speed (MB/s)**
- **Active / Installed Badge ✅**
- **Delete Button 🗑️** (Storage free karne ke liye)

---

## 5. Local Setup Ke Liye 4 Sync & Download Modes (A, B, C, D)

Kyunki user ka setup poora local/offline oriented hai, isliye 4 flexible options provide kiye gaye hain:

### Option A: Local PC-to-Phone Wi-Fi Sync (Zero Internet Usage)
- **Concept:** Agar PC par models ya packs pehle se downloaded hain, toh phone unhe local Wi-Fi router ke zariye direct PC se pull kar leta hai.
- **Speed:** 10 MB/s se 50 MB/s (Local Gigabit/5GHz Wi-Fi).
- **Data Usage:** 0 MB Internet Data.
- **Workflow:**
  1. PC par chhota sa lightweight HTTP server ya Jarvis PC bridge chalta hai (`http://192.168.x.x:8080/packs`).
  2. Phone app me user tap karega: **"Sync from My PC"**.
  3. App PC se models ko download karke phone ke internal storage me save kar lega.

### Option B: Local PC Ollama Bridge (Phone Me 0 MB Storage Kharch)
- **Concept:** Phone me koi heavy LLM model download karne ki zaroorat hi nahi.
- **Workflow:**
  1. User ke PC par Ollama chal raha hota hai (e.g., `qwen2.5:1.5b`, `llama3.2:3b`, ya `deepseek-r1:1.5b`).
  2. Phone ka Jarvis app local network par `http://192.168.x.x:11434/v1` API se connect hota hai.
  3. Phone par zero storage kharch hota hai aur processing PC ke powerful GPU par hoti hai.

### Option C: SD Card / Phone Storage 1-Click Import
- **Concept:** Direct offline manual transfer via USB cable.
- **Workflow:**
  1. User PC se phone ke `Download/` folder me koi bhi `.gguf` model ya pack zip file copy karta hai.
  2. App me button hota hai: **"📁 Import from Storage"**.
  3. App file ko verify karke Jarvis ke internal directory me link/move kar leta hai.

### Option D: Direct High-Speed Download (GitHub Releases / HuggingFace)
- **Concept:** Phone jab internet se connected ho, toh direct official repository se 1-click download.

#### Option D Ka Technical Mechanism:
1. **Hosting Location (100% Free & Unlimited Bandwidth):**
   - **GitHub Releases:** Asset packs (`vosk_voice_pack.zip`, `mora_3d_pack.zip`) ko repo ke Release assets me upload kiya jaata hai.
   - **HuggingFace Hub:** GGUF models direct HuggingFace resolve URL se fetch hote hain.
2. **App Downloader Engine:**
   - Phone ko kisi `git` CLI ki zaroorat nahi hoti.
   - Jarvis ka built-in [`LocalModelManager.kt`](file:///c:/Users/rosha/Downloads/Project's/Moblie%20AI/AI%20Folder/Jarvis-main/app/src/main/java/com/jarvis/assistant/ai/local/LocalModelManager.kt) OkHttp streaming call karta hai.
   - HTTP `Range` headers use hote hain, jisse download pause aur resume ho sakta hai (agar network break ho toh zero se restart nahi hota).
3. **Storage & Extraction:**
   - File download hokar `context.filesDir/packs/` me save hoti hai.
   - Agar `.zip` file hai, toh app usko turant background coroutine me unzip karta hai aur temporary `.zip` ko delete kar deta hai taaki storage double na lage.
   - `.gguf` files direct `files/local_ai_models/` me store hoti hain aur SHA-256 integrity verify hoti hai.

#### Option D Catalog Schema (`catalog.json`):
```json
[
  {
    "id": "vosk_speech_pack",
    "name": "Offline Voice Recognition (Vosk)",
    "category": "VOICE",
    "size": "85 MB",
    "url": "https://github.com/HunterisLive-1/jarvis-assets/releases/download/v1.0/vosk_model.zip",
    "targetDir": "packs/vosk"
  },
  {
    "id": "mora_3d_avatar",
    "name": "Mora 3D Virtual Companion",
    "category": "COMPANION_3D",
    "size": "12 MB",
    "url": "https://github.com/HunterisLive-1/jarvis-assets/releases/download/v1.0/mora_3d.zip",
    "targetDir": "packs/mora"
  },
  {
    "id": "qwen_1.5b_gguf",
    "name": "Qwen 2.5 1.5B Offline Brain",
    "category": "LLM",
    "size": "980 MB",
    "url": "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
    "targetDir": "local_ai_models"
  }
]
```

---

## 6. Implementation Action Plan & Milestones

### Phase 1: Build & Packaging Optimization
1. **ABI Filter:** `app/build.gradle.kts` me `ndk.abiFilters = listOf("arm64-v8a")` enable karna (Removes 55 MB of unused 32-bit `armeabi-v7a` binaries).
2. **Remove Dead Assets:** Unused `assets/guardian/ecapa6s.tflite` (42 MB) aur `rdn2b6s.tflite` (26 MB) ko base APK se hatana.
3. **Deduplicate 3D Meshes:** Identical duplicate animation files aur character meshes ko single reference me convert karna (~20 MB save).

### Phase 2: Dynamic Resource Downloader Engine
1. **`JarvisPackManager.kt`:** A unified pack manager that handles:
   - Manifest catalog reading (`catalog.json`)
   - OkHttp background download with progress StateFlow
   - Zip extraction and directory validation
   - Delete / Free space management
2. **Local PC Sync Server (`pc_sync.py` / Local bridge):**
   - 1-line python script on PC to serve packs over local Wi-Fi without internet.

### Phase 3: Resource Download Center UI
1. **`ResourceCenterBottomSheet.kt` / Activity:**
   - Free Fire style Material 3 cards with Category Tabs.
   - Storage usage indicator bar.
   - 1-Click Download, Pause, Resume, and Delete actions.

---

## 7. Expected Results

| Metric | Before Optimization | After Dynamic Pack Architecture | Improvement |
| :--- | :--- | :--- | :--- |
| **Base APK Size** | **~491 MB** | **~40–45 MB** | **~91% Size Reduction** |
| **Installation Time** | 45–60 seconds | **3–5 seconds** | **10x Faster Install** |
| **User Flexibility** | Sabhi models mandatory | User jo chahe wahi pack download kare | **Custom Storage Control** |
| **Offline Reliability** | Pre-bundled only | Local PC Wi-Fi sync + SD Card import | **100% Offline Independence** |
