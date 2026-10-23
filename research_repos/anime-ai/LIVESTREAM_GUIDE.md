# 🎤 Virtual Idol Livestream System

## Tính năng

✅ **Real-time Voice Conversation** - Gemini Live API streaming  
✅ **AI Function Calling** - AI tự chọn animations phù hợp  
✅ **Audio-driven Lip Sync** - Sync miệng với giọng nói TTS  
✅ **Animation Library** - Thư viện động tác phong phú  
✅ **Voice Activity Detection** - Phát hiện khi user nói  
✅ **Multilingual** - Hỗ trợ tiếng Việt & English

## Kiến trúc

```
User Mic → Gemini Live API (WebSocket)
              ↓
    AI Analysis + Function Calling
              ↓
   ┌──────────┴──────────┐
   ↓                     ↓
TTS Audio         Animation Commands
   ↓                     ↓
Audio Analyzer      Animation Library
   ↓                     ↓
Lip-sync Values    Load .fbx animations
   ↓                     ↓
   └─────→  VRM Character  ←─────┘
```

## Setup nhanh

### 1. Cài đặt dependencies

```bash
npm install
```

### 2. Lấy Gemini API Key

1. Truy cập: https://aistudio.google.com/apikey
2. Tạo API key mới
3. Copy key

### 3. Cấu hình API Key

Mở `humanoid-animations/main.js` và thay:

```javascript
const GEMINI_API_KEY = 'YOUR_API_KEY_HERE'; // ← Paste API key vào đây
```

### 4. Thêm animations (Tùy chọn)

Download từ Mixamo: https://www.mixamo.com

Đặt .fbx files vào thư mục `humanoid-animations/`:

```
humanoid-animations/
├── Wave.fbx
├── HipHopDancing.fbx
├── Capoeira.fbx
├── Jump.fbx
├── Thinking.fbx
└── ...
```

**Animations được hỗ trợ:**
- `idle` - Idle.fbx
- `greet` - Wave.fbx, Waving.fbx
- `dance` - HipHopDancing.fbx, Capoeira.fbx, SambaDancing.fbx
- `excited` - Jump.fbx, Cheering.fbx, Victory.fbx
- `laugh` - Laughing.fbx, ClappingHands.fbx
- `thinking` - Thinking.fbx, LookingAround.fbx
- `sad` - Crying.fbx, Defeated.fbx
- `bow` - Bowing.fbx, ThankYou.fbx
- `heart` - HeartGesture.fbx, BlowingKiss.fbx
- `surprised` - Surprised.fbx, Shocked.fbx

### 5. Chạy dev server

```bash
npm run dev
```

### 6. Mở trình duyệt

```
http://localhost:5173/humanoid-animations/live.html
```

## Sử dụng

### Voice Mode (Mic)

1. Click **"🎤 Bắt đầu Live"**
2. Cho phép mic access
3. Nói chuyện với Alicia!

**Ví dụ câu nói:**
- "Chào idol!" → Wave animation + greeting
- "Múa đi em ơi!" → Dance animation
- "Cute quá!" → Heart gesture
- "Hôm nay thế nào?" → Thinking pose

### Text Mode (Keyboard)

Gõ tin nhắn vào ô chat và nhấn Enter.

### Test Animations (GUI)

Sử dụng panel bên phải:
- **🎭 Test Animations** - Click để test từng animation
- **TimeScale** - Điều chỉnh tốc độ animation

## Tùy chỉnh

### Thêm animations mới

1. Mở `humanoid-animations/animationLibrary.js`
2. Thêm action mới:

```javascript
export const animationLibrary = {
    // ... existing actions
    
    sleep: {
        files: ['Sleeping.fbx', 'Yawn.fbx'],
        description: 'Sleep or yawn - tired reactions',
        duration: 5,
        priority: 5
    }
};
```

3. Download `Sleeping.fbx` từ Mixamo
4. Đặt vào `humanoid-animations/`
5. AI tự động biết cách sử dụng!

### Thay đổi personality

Mở `humanoid-animations/geminiLiveController.js`, edit `getSystemPrompt()`:

```javascript
getSystemPrompt() {
    return `
Bạn là Yuki - virtual idol nhẹ nhàng và dịu dàng!

TÍNH CÁCH:
- Nhẹ nhàng, ấm áp, hay lo lắng cho fan
- Giọng nói dịu dàng
- Thích hát ballad và nhạc nhẹ
...
`;
}
```

### Thay đổi voice

Trong `geminiLiveController.js`, config:

```javascript
speechConfig: {
    voiceConfig: {
        prebuiltVoiceConfig: {
            voiceName: "Puck" // Hoặc: "Kore", "Charon", "Aoede"
        }
    }
}
```

**Voices available:**
- **Aoede** - Female, energetic (mặc định)
- **Puck** - Male, friendly
- **Kore** - Female, warm
- **Charon** - Male, deep

## Architecture Details

### Files Structure

```
humanoid-animations/
├── main.js                    # Core 3D scene + integration
├── animationLibrary.js        # Animation definitions
├── geminiLiveController.js    # Gemini Live API handler
├── lipSyncController.js       # Audio-driven lip-sync
├── loadMixamoAnimation.js     # FBX → VRM converter
├── mixamoVRMRigMap.js         # Bone mapping
├── live.html                  # Livestream UI
└── test.html                  # Original test page

audioProcessor.js              # AudioWorklet for mic
```

### AI Flow

1. **User speaks** → Mic captures audio
2. **AudioProcessor** → Convert to PCM16
3. **Gemini Live API** → Stream audio via WebSocket
4. **AI Analysis** → Understand intent + emotion
5. **Function Calling** → Choose animation(s)
6. **Animation System** → Load .fbx and play
7. **TTS Audio** → Generate voice response
8. **Lip-sync** → Analyze audio → mouth shapes
9. **VRM Update** → Apply expressions + animations

### Function Calling Example

User: "Múa đi idol!"

AI function call:
```json
{
  "name": "play_dance",
  "args": {
    "duration": 15,
    "intensity": 1.0
  }
}
```

System response:
1. Get random from `dance.files` → `HipHopDancing.fbx`
2. Load animation
3. Play for 15 seconds
4. Return to idle

## Troubleshooting

### ❌ "Connection failed"

**Giải pháp:**
- Kiểm tra API key trong `main.js`
- Verify API key tại: https://aistudio.google.com/apikey
- Check console log (`F12` → Console)

### ❌ "Mic not working"

**Giải pháp:**
- Click 🔒 icon trên address bar
- Allow microphone permission
- Use headphones để tránh echo
- Check browser compatibility (Chrome/Edge recommended)

### ❌ "Animation not found"

**Giải pháp:**
- Download .fbx từ Mixamo
- Đặt vào `humanoid-animations/` folder
- File name phải match với `animationLibrary.js`
- Reload page (`Ctrl+R`)

### ❌ "No lip-sync"

**Giải pháp:**
- Check VRM model có expressions: `aa`, `ih`, `ou`, `ee`
- Verify audio output trong console
- VRM1.0 models recommended

### ❌ "AudioWorklet error"

**Giải pháp:**
- Ensure `audioProcessor.js` ở root directory
- Check HTTPS (localhost OK)
- Clear cache (`Ctrl+Shift+R`)

## Performance Tips

### Optimize for streaming

```javascript
// Trong main.js
renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2)); // Giảm resolution
renderer.shadowMap.enabled = false; // Tắt shadows
```

### Reduce animation memory

- Chỉ load animations cần thiết
- Dispose unused clips
- Use compressed VRM models

### Network optimization

- Gemini Live API yêu cầu stable internet
- Minimum: 1 Mbps upload
- Recommended: 5+ Mbps

## Streaming to OBS

### Setup OBS Browser Source

1. Open OBS Studio
2. Add Source → **Browser**
3. URL: `http://localhost:5173/humanoid-animations/live.html`
4. Width: `1920`, Height: `1080`
5. Custom CSS (optional):

```css
body {
    background: transparent !important;
}
#info, #chat-container, .instructions {
    display: none !important;
}
```

6. ✅ Alicia xuất hiện trong stream!

### Chroma key (Green screen)

Update `main.js`:

```javascript
scene.background = new THREE.Color(0x00ff00); // Green
```

OBS: Add **Chroma Key** filter → pick green.

## Advanced Features

### Multi-agent conversation

Tạo nhiều VRM characters và setup turn-taking với Gemini.

### Chat integration

Kết nối YouTube/Twitch chat để fans tương tác real-time.

### Custom gestures

Train gestures từ webcam với MediaPipe Hands.

### Emotion detection

Phân tích sentiment từ chat → trigger animations.

## API Reference

### `animationLibrary.js`

```javascript
// Get animation tools for AI
getAnimationTools() → ToolDefinition[]

// Get random animation file
getRandomAnimationFile(action: string) → { file, defaultDuration, priority }

// Parse function name
parseActionName(functionName: string) → string
```

### `geminiLiveController.js`

```javascript
// Initialize controller
new GeminiLiveController(apiKey, onAnimationCommand, onAudioData)

// Connect to Gemini Live
connect() → Promise<boolean>

// Start microphone streaming
startMicrophone() → Promise<boolean>

// Send text message
sendText(text: string) → Promise<void>

// Disconnect
disconnect() → void
```

### `lipSyncController.js`

```javascript
// Initialize lip-sync
new LipSyncController(vrm)

// Play audio with lip-sync
playAudio(audioData: ArrayBuffer | string) → Promise<void>

// Queue multiple audio chunks
queueAudio(audioDataArray: ArrayBuffer[]) → Promise<void>

// Close mouth
closeMouth() → void
```

## License

MIT License - Free to use for any purpose!

## Credits

- **Three.js** - 3D rendering
- **@pixiv/three-vrm** - VRM support
- **Google Gemini 2.0** - AI & voice
- **Mixamo** - Animations

---

Made with ❤️ by GitHub Copilot

**Enjoy your virtual idol! 🎤✨**
