# Gemini Live AI Integration

## Tính năng Voice Conversation với Gemini 2.0 Flash

Virtual Idol có thể nói chuyện trực tiếp với người dùng qua voice, sử dụng Gemini Live API.

## Cài đặt

```bash
npm install @google/genai
```

## Cấu hình

1. **API Key**: Cập nhật `GEMINI_API_KEY` trong `main.js`
2. **System Instruction**: Tùy chỉnh personality trong `geminiLiveAI.js`

## Cách sử dụng

### Trong live.html

1. Click nút **"🎤 Bắt đầu Live"**
2. Cho phép microphone access
3. Nói chuyện với Alicia
4. AI sẽ trả lời bằng giọng nói tự nhiên

### Các tính năng

- ✅ **Real-time voice conversation** - Nói chuyện 2 chiều
- ✅ **Auto lip-sync** - Miệng nhân vật sync với giọng nói AI
- ✅ **Echo cancellation** - Chống echo tự động
- ✅ **Noise suppression** - Lọc nhiễu background
- ✅ **Text display** - Hiển thị response dạng text trong chat

## Kiến trúc

### GeminiLiveAI (geminiLiveAI.js)
- Connect to Gemini Live API
- Stream microphone input (16kHz PCM)
- Receive audio output (24kHz PCM)
- Play audio response
- Handle interruptions

### Audio Processing
```
Microphone → AudioContext → PCM 16bit → Base64 → Gemini
Gemini → Base64 → PCM 16bit → AudioBuffer → Speaker
```

### Lip Sync Integration
```
Audio playing → LipSyncController → VRM mouth expressions
Audio ended → Close mouth
```

## Gemini Model

**Model**: `gemini-2.0-flash-exp`
- Fast response time (~300ms)
- Native audio support
- Vietnamese language support
- Expressive voice

## System Instruction

```javascript
systemInstruction: `You are Alicia, a cheerful and energetic Virtual Idol DJ. 
You love music, dancing, and interacting with your fans. 
You speak Vietnamese naturally and expressively.
Keep responses short and energetic, like a real livestream idol.
Use emojis occasionally to show emotion.`
```

## Callbacks

### onResponse(type, data)
```javascript
geminiLive.onResponse((type, data) => {
    if (type === 'text') {
        console.log('AI response:', data);
        // Display in chat UI
    }
});
```

### onAudio(event)
```javascript
geminiLive.onAudio((event) => {
    if (event === 'playing') {
        // Start lip sync animation
    } else if (event === 'ended') {
        // Close mouth
    }
});
```

## API Methods

### connect()
```javascript
const success = await geminiLive.connect();
```

### disconnect()
```javascript
geminiLive.disconnect();
```

### sendText(text)
```javascript
geminiLive.sendText('Xin chào!');
```

## Troubleshooting

### Microphone không hoạt động
- Kiểm tra browser permissions
- Đảm bảo HTTPS (localhost ok)
- Check console cho errors

### Audio không phát
- Kiểm tra volume system
- Verify AudioContext not blocked
- Test với headphones (tránh echo)

### Echo feedback
- **Bắt buộc dùng headphones**
- System không có echo cancellation khi dùng speaker
- Browser default audio device thường không có echo cancellation

### Lag/Delay
- Gemini API có latency ~300-500ms (bình thường)
- Network issues có thể tăng delay
- Sử dụng connection tốt

## Performance

### Audio Queue
- Buffer multiple audio chunks
- Smooth playback without gaps
- Handle interruptions

### Memory Management
- Auto cleanup on disconnect
- Proper stream disposal
- No memory leaks

## Limitations

- **Browser only** - Không hỗ trợ Node.js backend (cần mic/speaker)
- **HTTPS required** - Microphone API yêu cầu secure context
- **Gemini API quota** - Free tier có giới hạn requests
- **No echo cancellation** on speaker output - Dùng headphones

## Future Enhancements

- [ ] Advanced lip-sync với phoneme detection
- [ ] Emotion detection từ voice tone
- [ ] Animation triggers từ conversation context
- [ ] Multi-language support
- [ ] Custom voice models
- [ ] Background music mixing
