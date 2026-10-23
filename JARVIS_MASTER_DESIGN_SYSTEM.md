# JARVIS × MYRA AI — MASTER VISUAL DESIGN SYSTEM SPECIFICATION
**Document ID:** `JARVIS-DESIGN-SYSTEM-003`  
**Classification:** Master UI/UX & Visual Architecture Standard  
**Version:** 3.0 (Unified Cybernetic Stark HUD Architecture)  
**Package:** `com.jarvis.assistant.ui`  

---

## 1. Visual Philosophy & Design Language

The Jarvis design system marries high-fidelity aerospace command aesthetics with ultra-clean modern Android ergonomics. Rather than overwhelming the operator with noisy sci-fi decorations or shallow anime webviews, the system employs **purposeful cybernetics**: every line, glow ring, particle oscillation, and contrast layer corresponds directly to an active subsystem state, hardware sensor, or AI reasoning pipeline.

---

## 2. Complete Color Palette & Semantic Color Architecture

### 2.1 Thematic Accent Palettes (User-Selectable via Studio)

| Theme Preset | Primary Accent | Secondary Accent | Glow Halo Base | Status Highlight | Character / Persona |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **STARK CYAN (Default)** | `#00E5FF` | `#00B0FF` | `rgba(0, 229, 255, 0.35)` | `#00E676` (Tactical Green) | J.A.R.V.I.S. Core Intelligence |
| **F.R.I.D.A.Y. VIOLET** | `#7C4DFF` | `#B388FF` | `rgba(124, 77, 255, 0.35)`| `#FF4081` (Vibrant Pink) | Tactical Adaptive Defense AI |
| **E.D.I.T.H. CRIMSON** | `#FF1744` | `#FF5252` | `rgba(255, 23, 68, 0.35)`  | `#FF9100` (Amber Alert) | Global Orbital Defense Network |
| **CYBER EMERALD** | `#00E676` | `#69F0AE` | `rgba(0, 230, 118, 0.35)` | `#00E5FF` (Cyan Trace) | Stealth Reconnaissance & Matrix |
| **SOLAR GOLD** | `#FFD600` | `#FFE57F` | `rgba(255, 214, 0, 0.35)`  | `#FF6D00` (Solar Flare) | Overclocked Hyper-Core Mode |

### 2.2 Foundation Background & Surface Architecture

```
Layer 0 (Root Window):        #070914 (Deep Space Obsidian - 100% Opaque)
Layer 1 (Recessed Canvas):    #0A0D1B (Subtle Ambient Canvas - Behind Orb & Grid)
Layer 2 (Standard Card):      #0E1322 (Base Card Surface - 95% Opaque with 1px border)
Layer 3 (Elevated Surface):   #141A2E (Elevated Tile / Dock Container - with Frosted Blur)
Layer 4 (Modal / Sheet):      #1A2238 (Bottom Sheets & Dialog Overlays - 90% blur backdrop)
Layer 5 (Floating Pip / HUD): #202945 (Active Floating Bubble / Tooltip Overlay)
```

### 2.3 Semantic Typography & Text Colors

- **Text Primary (`#FFFFFF`):** 100% white for primary headers, critical telemetric numbers, operator commands, and direct answers.
- **Text Secondary (`#B0BEC5`):** Blue-grey for conversational stream responses, code annotations, and sub-labels.
- **Text Muted (`#6E7A99`):** Low-contrast steel slate for timestamps, inactive tab chips, background thread counts, and license disclaimers.
- **Text Accent (`?attr/themeAccent`):** Dynamic primary theme color for protocol titles, active indicators, and emphasized AI conclusions.
- **Text Warning (`#FF9100`):** Amber for API rate limits, battery low warnings, and pending permission requests.
- **Text Danger / Critical (`#FF1744`):** Stark red for security intrusion alerts, unauthorized app unlock attempts, and hardware thermal throttling.

---

## 3. Typography & Typeface Scale

Jarvis utilizes the system sans-serif font family enhanced with strict tracking, tabular numbers, and monospace blocks for aerospace-level precision:

| Typography Role | Size (sp) | Weight | Letter Spacing | Case | Target View / Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Display Header** | `32sp` | Bold (700) | `+0.04em` | Regular | Large Clock, Biometric Face Match % |
| **Screen Title** | `22sp` | Bold (700) | `+0.06em` | ALL CAPS | Activity & Fragment Main Headers |
| **Section Header** | `15sp` | Medium (500)| `+0.08em` | ALL CAPS | Protocol Dividers, Studio Tab Names |
| **Body Primary** | `14sp` | Regular (400)| `+0.01em` | Regular | Assistant Chat Responses, Long Prose |
| **Body Secondary** | `13sp` | Regular (400)| `0.00em` | Regular | Setting Sub-items, Unread Messages |
| **Telemetry / Data**| `12sp` | Bold (700) | `+0.10em` | ALL CAPS | Latency Ping, Battery Voltage, GPS |
| **Code / Terminal** | `12sp` | Mono (400) | `-0.01em` | Raw Case | Termux Output, Python Scripts, JSON |
| **Micro Caption** | `10sp` | Bold (700) | `+0.12em` | ALL CAPS | Chip Pills, Status Dot Subtitles |

---

## 4. Surfaces, Radii, Borders, and Glassmorphism

### 4.1 Corner Radii Hierarchy
- **Extra Small (`4dp`):** Inline telemetry tags, code block corners.
- **Small (`8dp`):** Action buttons, input consoles, slider thumbs, icon boxes (`bg_jarvis_icon_square.xml`).
- **Medium (`14dp`):** Action cards (`bg_jarvis_action_card.xml`), settings row groups, chat turn bubbles.
- **Large (`20dp`):** Protocol tiles in hamburger menu, preview viewport cards.
- **Pill / Circular (`50%` / `28dp`):** Bottom dock buttons, elevated mic button, filter chips (`bg_jarvis_chip.xml`).

### 4.2 Border & Stroke Standards
All cards and interactive surfaces must have a subtle cybernetic stroke:
- **Default Inactive Stroke:** `1dp` solid `rgba(255, 255, 255, 0.08)` (ensures card separation on deep black OLED displays).
- **Active / Focused Stroke:** `1.5dp` solid `?attr/themeAccent` with a soft `4dp` outer blur ring.
- **Alert / Warning Stroke:** `1.5dp` solid `#FF9100` or `#FF1744`.

### 4.3 Real-Time Frosted Glass (Blur Effect)
Implemented natively via `FrostedGlassEffect.kt`:
- **Android 12+ (API 31+):** Uses hardware `RenderEffect.createBlurEffect(25f, 25f, Shader.TileMode.CLAMP)` applied to container backdrops.
- **Pre-Android 12 Fallback:** High-density alpha layered scrim (`#141A2EE6` - 90% opacity deep obsidian tint) with no frame rate degradation.

---

## 5. Master Component Specification

### 5.1 Action & Telemetry Cards
- **Drawable:** `@drawable/bg_jarvis_action_card`
- **Specification:**
  ```xml
  <shape xmlns:android="http://schemas.android.com/apk/res/android">
      <solid android:color="#0E1322" />
      <stroke android:width="1dp" android:color="#1A243D" />
      <corners android:radius="14dp" />
  </shape>
  ```
- **Internal Padding:** `16dp` horizontal, `14dp` vertical.
- **Ripple Effect:** Bound to `?attr/selectableItemBackgroundBorderless` with alpha `0.12`.

### 5.2 Floating Curved Bottom Dock (`CurvedDockLineView`)
- **Dimensions:** Width `match_parent`, Height `72dp`.
- **Geometry:** Custom Bezier curve dipping down to `0dp` on sides and creating a smooth concave recess of `48dp` radius around the elevated center microphone button.
- **Fill Shader:** Linear gradient from `#141A2EE6` (top) to `#070914FA` (bottom).
- **Upper Border:** `1.5dp` stroke drawn with dynamic `themeAccent` alpha gradient (brightest at center peak, fading towards screen edges).

### 5.3 Elevated Holographic Microphone Button
- **Dimensions:** `64dp` diameter circular Floating Action Button.
- **Elevation:** `8dp` drop shadow with dynamic colored ambient blur.
- **Background:** Multi-ring gradient (`bg_jarvis_mic_center.xml` + `bg_jarvis_orb_halo.xml`).
- **Core Icon:** 28dp high-contrast white microphone glyph (`ic_mic_elevated.xml`).
- **Active State Animation:** Breathing scale oscillation between `1.0x` and `1.15x` with radial sound-wave ripples during active audio recording.

### 5.4 Segmented Tab Chips
- **Dimensions:** Height `36dp`, Min Width `80dp`, Corner Radius `18dp`.
- **Selected State:** Background `@drawable/bg_jarvis_chip` with `?attr/themeAccent` stroke and `rgba(0, 229, 255, 0.15)` fill. Text is bold `?attr/themeAccent`.
- **Unselected State:** Background `@drawable/bg_jarvis_row_item` with `rgba(255, 255, 255, 0.05)` fill. Text is medium `?attr/themeTextMuted`.

---

## 6. Accessibility & Multi-Form Scalability

1. **Touch Target Enforcement:** All clickable buttons, switches, and tabs strictly maintain minimum bounding boxes of `48dp x 48dp` (using `android:minHeight="48dp"` and `touchDelegate` expansion where visual size is smaller).
2. **Dynamic Display Scaling:** All text elements use `sp` units and are enclosed in flexible `wrap_content` containers with `maxLines` and `ellipsize="end"` to prevent UI truncation up to `150%` font scale.
3. **Contrast Compliance:** All primary text-to-background combinations achieve a minimum contrast ratio of `12:1` (exceeding WCAG AAA `7:1`).
4. **Foldable & Tablet Responsiveness:** Employs responsive two-column grid layouts when screen width exceeds `600dp` (`layout-sw600dp`), splitting the central orb and telemetry stream side-by-side.

---
*Verified against active Android styles in `res/values/styles.xml` and `res/values/attrs.xml`.*
