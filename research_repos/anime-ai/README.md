# Anime AI

> Open-source framework for building AI-powered virtual characters with VRM avatars, voice synthesis, lip-sync, and animation systems.

Anime AI helps developers create interactive AI characters that can speak, animate, and interact in real time.

Perfect for:

* AI Agents
* Virtual Assistants
* VTubers
* Educational Characters
* AI Streamers
* Interactive NPCs

---

## Features

### VRM Avatar Support

* Load and render VRM 1.0 avatars
* Runtime character switching
* Expression control system
* Spring bone physics

### Animation System

* Mixamo FBX support
* Automatic Mixamo → VRM retargeting
* Animation blending and crossfades
* Animation sequence playback

### AI Voice Integration

* Google Gemini Text-to-Speech support
* Multiple voice options
* Adjustable speed and pitch
* Real-time audio playback

### Lip Sync

* Automatic mouth movement generation
* Audio-driven facial expressions
* Real-time synchronization with speech

### Developer Friendly

* Three.js based
* Modern ES Modules
* Drag-and-drop testing workflow
* Easy integration into AI applications

---

## Demo

### Default Character

* VRM Avatar Loading
* Idle Animation
* Voice Playback
* Lip Sync

### Supported Assets

| Type      | Format                   |
| --------- | ------------------------ |
| Character | .vrm                     |
| Animation | .fbx                     |
| Audio     | Generated via Gemini TTS |

---

## Project Structure

```text
anime-ai/
├── characters/
│   └── VRM1_Alicia_Solid.vrm
│
├── humanoid-animations/
│   ├── main.js
│   ├── tts.js
│   ├── loadMixamoAnimation.js
│   ├── mixamoVRMRigMap.js
│   ├── test.html
│   └── animations/
│
├── package.json
└── README.md
```

---

## Quick Start

### Install

```bash
npm install
```

### Run

Open:

```text
humanoid-animations/test.html
```

The application will automatically:

1. Load the default VRM avatar
2. Start idle animation
3. Enable lip-sync
4. Initialize voice controls

---

## Example

```javascript
import { GeminiTTS, VoiceCharacter } from './tts.js';

const tts = new GeminiTTS(API_KEY);

const character = new VoiceCharacter(
  currentVrm,
  currentMixer,
  tts
);

await character.speak(
  "Hello! Welcome to Anime AI.",
  {
    voice: "Puck",
    speakingRate: 1.0
  }
);
```

---

## Use Cases

### AI Agent Avatar

Give AI agents a visual identity with voice and animation.

### VTuber Applications

Create AI-driven VTuber experiences with minimal setup.

### Educational Assistants

Build interactive learning companions that can explain concepts using speech and gestures.

### Customer Support Characters

Deploy virtual representatives with natural voice interaction.

---

## Roadmap

### Current

* VRM Loading
* Mixamo Animation Support
* Gemini TTS
* Lip Sync
* Animation Blending

### Planned

* OpenAI Realtime API Integration
* Live Microphone Lip Sync
* Emotion System
* Multi-Character Scene Support
* Agent Framework Integration
* Streaming Mode
* Browser Voice Chat

---

## Contributing

Contributions are welcome.

Areas where help is appreciated:

* VRM support improvements
* Animation systems
* Lip-sync quality
* Voice integrations
* Documentation
* Example projects

Please open an issue before submitting major changes.

---

## Why This Project Exists

Most AI agent frameworks focus on intelligence but lack visual presence.

Anime AI aims to bridge that gap by providing an open-source foundation for AI-powered virtual characters that can see, speak, animate, and interact naturally.

---

## License

MIT License
