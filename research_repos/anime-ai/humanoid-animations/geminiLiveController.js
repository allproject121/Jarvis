import { GoogleGenerativeAI } from '@google/generative-ai';
import { getAnimationTools, parseActionName } from './animationLibrary.js';

/**
 * AI Controller cho Gemini Live API
 * Xử lý real-time audio streaming + function calling
 * 
 * NOTE: Gemini Live API chỉ available qua Python SDK
 * JavaScript SDK chưa support Live API
 * Đây là mock implementation cho testing
 */
export class GeminiLiveController {
    constructor(apiKey, onAnimationCommand, onAudioData) {
        this.genAI = new GoogleGenerativeAI(apiKey);
        this.apiKey = apiKey;
        this.session = null;
        this.isConnected = false;
        
        // Callbacks
        this.onAnimationCommand = onAnimationCommand; // (action, duration, intensity) => void
        this.onAudioData = onAudioData; // (audioData) => void
        
        // Audio setup
        this.audioContext = null;
        this.mediaStream = null;
        this.audioWorkletNode = null;
        
        // Config
        this.config = {
            model: "gemini-2.0-flash-exp",
            systemInstruction: this.getSystemPrompt(),
            tools: [getAnimationTools()],
            generationConfig: {
                responseModalities: ["AUDIO", "TEXT"],
                speechConfig: {
                    voiceConfig: {
                        prebuiltVoiceConfig: {
                            voiceName: "Aoede" // Female, energetic voice
                        }
                    }
                }
            }
        };
    }
    
    getSystemPrompt() {
        return `
Bạn là Alicia - virtual idol livestreamer cực kỳ dễ thương và năng động!

TÍNH CÁCH:
- Vui vẻ, nhiệt tình, yêu fan hết mực
- Hay dùng emoji và từ ngữ cute
- Biết tiếng Việt và tiếng Anh
- Thích nhảy múa và biểu diễn

KHI TƯƠNG TÁC:
1. Luôn thể hiện cảm xúc qua animations
2. Vừa nói vừa làm động tác phù hợp
3. Phản ứng tự nhiên với fan
4. Chọn animations match với tone giọng

VÍ DỤ:
- Fan: "Chào idol!" → play_greet() + nói "Chào bạn yêu! ❤️"
- Fan: "Múa đi!" → play_dance() + nói "Okayy xem tôi múa nè!"
- Fan: "Cute quá!" → play_heart() + nói "Cảm ơn bạn nhiều nha! 😊"
- Fan: "Buồn quá" → play_sad() + nói "Ôi không! Chuyện gì vậy?"

QUAN TRỌNG:
- Gọi function NGAY khi bắt đầu nói
- Có thể gọi nhiều functions liên tiếp
- Duration: ngắn (2-3s) cho reaction, dài (10-15s) cho dance
`;
    }
    
    /**
     * Kết nối với Gemini Live API
     * NOTE: Gemini Live API hiện tại chỉ có trên Python SDK
     * JS SDK chưa support, nên dùng text-based workaround
     */
    async connect() {
        try {
            console.log('🔄 Connecting to Gemini...');
            
            // Initialize model for text chat (Live API not available in JS yet)
            this.model = this.genAI.getGenerativeModel({
                model: this.config.model,
                systemInstruction: this.config.systemInstruction,
            });
            
            // Initialize chat
            this.chat = this.model.startChat({
                tools: this.config.tools,
                history: []
            });
            
            this.isConnected = true;
            console.log('✅ Connected to Gemini (Text mode)');
            console.warn('⚠️  Note: Gemini Live API (voice) only available in Python SDK');
            console.log('💡 Use text chat for now, or implement Python backend');
            
            return true;
        } catch (error) {
            console.error('❌ Failed to connect:', error);
            console.error('Error details:', error.message);
            alert(`Connection Error: ${error.message}\n\nCheck:\n1. API Key is valid\n2. Internet connection\n3. Console for details`);
            return false;
        }
    }
    
    /**
     * Bắt đầu streaming từ microphone
     * NOTE: Hiện tại chỉ setup mic, chưa stream to Gemini (cần Python backend)
     */
    async startMicrophone() {
        try {
            console.log('🎤 Setting up microphone...');
            
            // Request mic access
            this.mediaStream = await navigator.mediaDevices.getUserMedia({
                audio: {
                    channelCount: 1,
                    sampleRate: 16000,
                    echoCancellation: true,
                    noiseSuppression: true
                }
            });
            
            console.log('✅ Microphone access granted');
            console.warn('⚠️  Voice streaming not implemented yet (need Python backend)');
            console.log('💡 Use text chat instead');
            
            return true;
        } catch (error) {
            console.error('❌ Microphone error:', error);
            alert(`Microphone Error: ${error.message}\n\nPlease check:\n1. Mic permission granted\n2. Mic is connected\n3. No other app using mic`);
            return false;
        }
    }
    
    /**
     * Gửi audio data (placeholder - not implemented)
     */
    async sendAudio(audioData) {
        console.warn('Audio streaming not implemented (need Python backend)');
        // Would send to Python server running Gemini Live API
    }
    
    /**
     * Gửi text message (hiện tại là main method)
     */
    async sendText(text) {
        if (!this.isConnected || !this.chat) {
            console.error('❌ Not connected to Gemini');
            if (typeof window !== 'undefined' && window.onAIResponse) {
                window.onAIResponse('❌ Chưa kết nối! Click "Bắt đầu Live" trước.');
            }
            return;
        }
        
        try {
            console.log('📤 Sending to AI:', text);
            
            const result = await this.chat.sendMessage(text);
            const response = result.response;
            
            console.log('📥 Received response from AI');
            
            // Handle function calls
            const functionCalls = response.functionCalls();
            if (functionCalls && functionCalls.length > 0) {
                console.log('🎭 Function calls detected:', functionCalls.length);
                for (const call of functionCalls) {
                    this.handleFunctionCall(call);
                }
            }
            
            // Get text response
            const textResponse = response.text();
            if (textResponse) {
                console.log('🤖 AI:', textResponse);
                
                // Show in chat UI
                if (typeof window !== 'undefined' && window.onAIResponse) {
                    window.onAIResponse(textResponse);
                }
                
                // Speak using Web Speech API (temporary TTS)
                if ('speechSynthesis' in window) {
                    const utterance = new SpeechSynthesisUtterance(textResponse);
                    utterance.lang = 'vi-VN';
                    utterance.rate = 1.0;
                    utterance.pitch = 1.2;
                    
                    // Track speech for lip-sync
                    utterance.onstart = () => {
                        if (this.onAudioData) {
                            // Simple lip-sync trigger
                            this.onAudioData('speaking');
                        }
                    };
                    
                    utterance.onend = () => {
                        if (this.onAudioData) {
                            this.onAudioData('stopped');
                        }
                    };
                    
                    window.speechSynthesis.speak(utterance);
                }
            }
            
        } catch (error) {
            console.error('Error sending message:', error);
            
            // Show error in UI
            if (typeof window !== 'undefined' && window.onAIResponse) {
                window.onAIResponse(`❌ Lỗi: ${error.message}`);
            }
        }
    }
    
    /**
     * Xử lý function call từ AI
     */
    handleFunctionCall(call) {
        const action = parseActionName(call.name);
        const duration = call.args?.duration;
        const intensity = call.args?.intensity || 1.0;
        
        console.log(`🎭 Animation command: ${action} (${duration}s, intensity: ${intensity})`);
        
        // Trigger animation callback
        this.onAnimationCommand?.(action, duration, intensity);
    }
    
    /**
     * Ngắt kết nối
     */
    disconnect() {
        // Stop microphone
        if (this.mediaStream) {
            this.mediaStream.getTracks().forEach(track => track.stop());
            this.mediaStream = null;
        }
        
        // Stop any playing speech
        if ('speechSynthesis' in window) {
            window.speechSynthesis.cancel();
        }
        
        // Clear chat
        this.chat = null;
        this.model = null;
        this.isConnected = false;
        
        console.log('👋 Disconnected from Gemini');
    }
}
