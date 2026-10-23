# JARVIS × MYRA AI — COMPLETE SCREEN & COMPONENT INVENTORY
**Document ID:** `JARVIS-COMP-INV-007`  
**Classification:** Complete UI Component & Screen Matrix  
**Package:** `com.jarvis.assistant.ui`  

---

## 1. Master Component Inventory Matrix

| # | Screen / Component Name | Host Activity / Fragment | UI Category | Visual Elements Included | Interactive States | Accessibility Attributes | Exact XML / Kotlin Reference | Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **01** | **Main Dashboard & Energy HUD** | `MainActivity` / `HomeFragment` | Dashboard | WebGL WebView orb, status badges, telemetry cards, prompt chips | Idle, Listening, Thinking, Speaking, Tool Calling, Alert | `contentDescription="Central Energy Core"`, min target 48dp | `fragment_home.xml` / `HomeFragment.kt` | Active |
| **02** | **Floating Curved Bottom Dock** | `MainActivity` (`CurvedDockLineView`) | Navigation | Bezier curve canvas, 5 anchor icons, elevated center mic FAB | Idle, Hover, Pressed (0.92x scale), Active highlight | Icon labels, touch expansion, `minHeight="72dp"` | `CurvedDockLineView.kt` / `activity_main.xml` | Active |
| **03** | **Stark Protocols Hamburger Menu** | `MainActivity` (`StarkMenuBottomSheet`) | Navigation / Modal | Frosted glass scrim, 10 protocol grid tiles, status dots | Closed, Sliding in, Tile Pressed, Dismissed | Grid labels, `role="navigation"`, full focus order | `bottom_sheet_stark_menu.xml` / `StarkMenuBottomSheet.kt` | Active |
| **04** | **Tactical Conversational Stream** | `MainActivity` / `ChatFragment` | Chat / Stream | Message RecyclerView, markdown renderer, syntax code blocks | Idle, Streaming tokens, Tool executing, Bubble long-press | TalkBack reading order, `contentDescription="Chat Turn"` | `fragment_chat.xml` / `ChatFragment.kt` | Active |
| **05** | **SuperCore OS Controller** | `SuperCoreActivity` | System / OS | 4 system cards, MaterialSwitches, action buttons, status pills | Default, Checking, Active (Green), Disabled (Amber) | Switch state announcements, high-contrast labels | `activity_super_core.xml` / `SuperCoreActivity.kt` | Active |
| **06** | **Jarvis Matrix Agent Hub** | `JarvisMatrixActivity` | Agentic Hub | Filter chips bar, Termux terminal card, macro launcher, stock ticker | Chip selected/unselected, Agent running (spinner), Done | Full label descriptors, keyboard navigation support | `activity_jarvis_matrix.xml` / `JarvisMatrixActivity.kt` | Active |
| **07** | **Stark HUD Studio** | `CustomizationStudioActivity` | Customization | WebGL preview card, 4 tab chips, seekbars, theme color cards | Tab switching, Slider drag, Preset preview, Sticky Save | Slider value announcements, 48dp touch targets | `activity_customization_studio.xml` / `CustomizationStudioActivity.kt` | Active |
| **08** | **Deep Settings Console** | `SettingsActivity` / `SettingsFragment` | Settings | Profile card, categorized settings stacks, chevrons, search bar | Card Pressed, Expanded, Toggled, Disabled | Semantic heading levels, descriptive subtitles | `fragment_settings.xml` / `SettingsFragment.kt` | Active |
| **09** | **God's Eye Visual Intelligence** | `GodsEyeActivity` | Vision / Camera | Camera preview SurfaceView, crosshairs HUD, telemetry overlay | Pan/Zoom, Target Lock, Thermal Toggle, Stream Active | Audio feedback for target acquisition, high contrast | `activity_gods_eye.xml` / `GodsEyeActivity.kt` | Active |
| **10** | **Episodic Vector Memory Vault** | `MemoryActivity` | Memory / Data | Search query bar, memory card list, timestamp badges, delete swipe | Idle, Typing search, Filter applied, Item dismissed | Search accessibility announcements, delete confirm | `activity_memory.xml` / `MemoryActivity.kt` | Active |
| **11** | **Voice Biometric Enrollment** | `VoiceEnrollmentActivity` | Security / Voice | Microphone gauge, sentence prompt cards, enrollment progress bar | Prompt idle, Recording sample (1/3), Verified | Voice cue guidance, high-contrast typography | `activity_voice_enrollment.xml` / `VoiceEnrollmentActivity.kt` | Active |
| **12** | **Biometric AppLock & Face Guard** | `JarvisAppLockActivity` | Security | BiometricPrompt trigger, PIN input keypad, intruder photo banner | Biometric challenge, PIN typed, Error shake, Success | Screen reader PIN obfuscation, vibration feedback | `activity_jarvis_app_lock.xml` / `JarvisAppLockActivity.kt` | Active |
| **13** | **Hands-Free Driving Sentinel** | `HandsFreeActivity` | Sentinel / Audio | Large speedometer/status circle, incoming call modal, mic wave | Standby, Car paired, Call incoming, Voice answering | Full voice-only navigation without touch requirement | `activity_hands_free.xml` / `HandsFreeActivity.kt` | Active |
| **14** | **Floating Bubble & Avatar Overlay** | `OverlayAvatarActivity` / Overlay Service | Overlay | Movable floating window, mini WebGL orb / anime avatar, mic icon | Idle, Dragging, Snapped to screen edge, Expanded HUD | Draggable handle description, dismiss target at bottom | `activity_overlay_avatar.xml` / `OverlayAvatarActivity.kt` | Active |
| **15** | **API & Model Connection Hub** | `ApiConnectionActivity` | Configuration | Encrypted password fields, model picker spinner, test ping button | Idle, Input focused, Testing connection, Ping latency | Masked password semantics, test result status voice | `activity_api_connection.xml` / `ApiConnectionActivity.kt` | Active |
| **16** | **Privacy Shield & Sensor Guard** | `PrivacyShieldActivity` | Privacy / Admin | Device admin status card, zero-cloud toggle, emergency wipe code | Safe state, Lockdown active, Cloud bypassed | Security alert sounds, confirmation dialogues | `activity_privacy_shield.xml` / `PrivacyShieldActivity.kt` | Active |
| **17** | **Termux Permission Sentinel** | `TermuxPermissionActivity` | Developer / Shell | Permission checklist, Termux API status check, test bash button | Permission pending, Granted, Shell responsive | Step-by-step resolution links, clear error codes | `TermuxPermissionActivity.kt` / `TermuxBridge.kt` | Active |
| **18** | **AI Persona & Voice Selector** | `PersonaActivity` | Customization | Persona cards (Jarvis, Friday, Edith, Myra), pitch/speed sliders | Card selected, Audio sample playing, Custom prompt edit | Audio preview buttons with clear accessibility text | `activity_persona.xml` / `PersonaActivity.kt` | Active |

---

## 2. Reusable Visual Component Library (`res/drawable/`)

| Drawable Name | Visual Appearance | Used By |
| :--- | :--- | :--- |
| `bg_jarvis_action_card.xml` | Obsidian rectangle (`#0E1322`), `14dp` corner radius, `1dp #1A243D` cybernetic border | All cards in Settings, Matrix, SuperCore, Studio |
| `bg_jarvis_pill_button.xml` | Fully rounded pill container (`28dp` radius), primary accent background, high contrast text | Primary CTA buttons, active category filter chips |
| `bg_jarvis_icon_square.xml` | Square container (`8dp` radius), subtle obsidian fill, border highlight | Back buttons, quick action icon buttons, toolbar anchors |
| `bg_jarvis_chip.xml` | Capsule chip (`18dp` radius), `?attr/themeAccent` stroke and semi-transparent fill | Tab selectors, prompt suggestion pills, status tags |
| `bg_jarvis_status_badge.xml`| Micro pill badge (`6dp` radius), padding `8dp x 4dp`, bold typography | `ACTIVE`, `DISABLED`, `STANDBY` status indicators |
| `bg_jarvis_mic_center.xml` | Circular multi-layer gradient with drop shadow and halo ring | Elevated center floating action button on bottom dock |

---
*Verified against active Android resource files in `app/src/main/res`.*
