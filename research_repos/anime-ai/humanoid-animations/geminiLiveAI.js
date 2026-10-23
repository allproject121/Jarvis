/**
 * Gemini Live AI Integration
 * Real-time voice conversation with Gemini 2.5 Flash
 */

import { GoogleGenAI, Modality } from '@google/genai';

export class GeminiLiveAI {
    constructor(apiKey) {
        this.apiKey = apiKey;
        this.session = null;
        this.isConnected = false;
        this.audioContext = null;
        this.mediaStream = null;
        this.processor = null;
        this.onResponseCallback = null;
        this.onAudioCallback = null;
        this.onAnimationCallback = null; // Callback for animation commands
        
        // Audio queue for playback
        this.audioQueue = [];
        this.isPlaying = false;
        
        // Gemini config
        this.model = 'gemini-2.0-flash-exp';
        this.config = {
            responseModalities: [Modality.AUDIO],
            systemInstruction: `You are Alicia, a cheerful and energetic Virtual Idol DJ. 
You love music, dancing, and interacting with your fans. 
You speak Vietnamese naturally and expressively.
Keep responses short and energetic, like a real livestream idol.
Use emojis occasionally to show emotion.

IMPORTANT: When user asks you to do actions, include these tags in your response:
- [dance] - when user asks to dance or mentions dancing
- [wave] - when greeting or saying hi/hello
- [cheer] - when celebrating or excited
- [bow] - when thanking or being polite
- [kiss] - when sending love or kisses

Example: "Okay let's dance! [dance]" or "Hi there! [wave] Welcome to my stream!"

Available actions: dance, wave, cheer, bow, kiss, idle

Always respond in Vietnamese unless asked otherwise.`,
        };
    }

    /**
     * Connect to Gemini Live API
     */
    async connect() {
        try {
            console.log('🔌 Connecting to Gemini Live API...');
            
            const ai = new GoogleGenAI({ apiKey: this.apiKey });
            
            // Connect to Live API
            this.session = await ai.live.connect({
                model: this.model,
                config: this.config,
                callbacks: {
                    onopen: () => {
                        console.log('✅ Connected to Gemini Live API');
                        this.isConnected = true;
                    },
                    onmessage: (message) => this.handleMessage(message),
                    onerror: (e) => {
                        console.error('❌ Gemini Live error:', e.message);
                        if (this.onResponseCallback) {
                            this.onResponseCallback('error', e.message);
                        }
                    },
                    onclose: (e) => {
                        console.log('🔌 Gemini Live closed:', e.reason);
                        this.isConnected = false;
                    },
                },
            });

            // Setup microphone
            await this.setupMicrophone();
            
            return true;
        } catch (error) {
            console.error('❌ Connection failed:', error);
            return false;
        }
    }

    /**
     * Setup microphone for audio input
     */
    async setupMicrophone() {
        try {
            // Get microphone stream
            this.mediaStream = await navigator.mediaDevices.getUserMedia({
                audio: {
                    channelCount: 1,
                    sampleRate: 16000,
                    echoCancellation: true,
                    noiseSuppression: true,
                    autoGainControl: true,
                }
            });

            // Create audio context
            this.audioContext = new (window.AudioContext || window.webkitAudioContext)({
                sampleRate: 16000,
            });

            const source = this.audioContext.createMediaStreamSource(this.mediaStream);
            
            // Create script processor for audio data
            this.processor = this.audioContext.createScriptProcessor(4096, 1, 1);
            
            this.processor.onaudioprocess = (e) => {
                if (!this.isConnected || !this.session) return;
                
                const inputData = e.inputBuffer.getChannelData(0);
                
                // Convert float32 to int16 PCM
                const pcmData = new Int16Array(inputData.length);
                for (let i = 0; i < inputData.length; i++) {
                    const s = Math.max(-1, Math.min(1, inputData[i]));
                    pcmData[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
                }
                
                // Convert to base64
                const base64 = this.arrayBufferToBase64(pcmData.buffer);
                
                // Send to Gemini with correct structure
                this.session.sendRealtimeInput({
                    media: {
                        data: base64,
                        mimeType: "audio/pcm;rate=16000"
                    }
                });
            };

            source.connect(this.processor);
            this.processor.connect(this.audioContext.destination);
            
            console.log('🎤 Microphone ready');
        } catch (error) {
            console.error('❌ Microphone setup failed:', error);
            throw error;
        }
    }

    /**
     * Handle incoming messages from Gemini
     */
    handleMessage(message) {
        if (message.serverContent && message.serverContent.interrupted) {
            // Clear audio queue on interruption
            this.audioQueue = [];
            console.log('⏸️ Interrupted');
            return;
        }

        if (message.serverContent && message.serverContent.modelTurn) {
            const turn = message.serverContent.modelTurn;
            
            // Extract text response
            if (turn.parts) {
                for (const part of turn.parts) {
                    // Audio data
                    if (part.inlineData && part.inlineData.data) {
                        const audioBuffer = this.base64ToArrayBuffer(part.inlineData.data);
                        this.audioQueue.push(audioBuffer);
                        
                        // Start playback if not already playing
                        if (!this.isPlaying) {
                            this.playAudioQueue();
                        }
                    }
                    
                    // Text response (for display)
                    if (part.text && this.onResponseCallback) {
                        this.onResponseCallback('text', part.text);
                        
                        // Parse for animation commands
                        this.parseAnimationCommands(part.text);
                    }
                }
            }
        }
    }

    /**
     * Parse text for animation command tags
     */
    parseAnimationCommands(text) {
        if (!this.onAnimationCallback) return;
        
        // Animation tag mapping
        const animationMap = {
            '[dance]': 'dancing',
            '[wave]': 'greeting',
            '[cheer]': 'cheering',
            '[bow]': 'bowing',
            '[kiss]': 'blowing_kiss',
            '[clap]': 'clapping',
            '[point]': 'pointing',
            '[laugh]': 'laughing',
            '[cry]': 'crying',
            '[angry]': 'angry'
        };
        
        // Check for animation tags
        for (const [tag, action] of Object.entries(animationMap)) {
            if (text.includes(tag)) {
                console.log(`🎭 Animation triggered: ${action}`);
                this.onAnimationCallback(action);
                // Only trigger first found animation
                break;
            }
        }
    }

    /**
     * Play audio from queue
     */
    async playAudioQueue() {
        if (this.isPlaying || this.audioQueue.length === 0) return;
        
        this.isPlaying = true;
        
        while (this.audioQueue.length > 0) {
            const audioData = this.audioQueue.shift();
            await this.playAudio(audioData);
        }
        
        this.isPlaying = false;
    }

    /**
     * Play audio buffer
     */
    async playAudio(audioData) {
        return new Promise((resolve) => {
            if (!this.audioContext) {
                resolve();
                return;
            }
            
            // Decode PCM audio (16-bit, 24000Hz from Gemini)
            const pcmData = new Int16Array(audioData);
            const float32Data = new Float32Array(pcmData.length);
            
            // Convert int16 to float32
            for (let i = 0; i < pcmData.length; i++) {
                float32Data[i] = pcmData[i] / 32768.0;
            }
            
            // Create audio buffer
            const audioBuffer = this.audioContext.createBuffer(
                1, // mono
                float32Data.length,
                24000 // Gemini outputs 24kHz
            );
            
            audioBuffer.getChannelData(0).set(float32Data);
            
            // Play audio
            const source = this.audioContext.createBufferSource();
            source.buffer = audioBuffer;
            source.connect(this.audioContext.destination);
            
            source.onended = () => {
                resolve();
                if (this.onAudioCallback) {
                    this.onAudioCallback('ended');
                }
            };
            
            source.start(0);
            
            if (this.onAudioCallback) {
                this.onAudioCallback('playing');
            }
        });
    }

    /**
     * Send text message to Gemini
     */
    sendText(text) {
        if (!this.isConnected || !this.session) {
            console.warn('⚠️ Not connected to Gemini');
            return;
        }
        
        this.session.send({
            text: text
        });
        
        console.log('💬 Sent text:', text);
    }

    /**
     * Disconnect from Gemini Live
     */
    disconnect() {
        console.log('🔌 Disconnecting...');
        
        // Stop microphone
        if (this.processor) {
            this.processor.disconnect();
            this.processor = null;
        }
        
        if (this.mediaStream) {
            this.mediaStream.getTracks().forEach(track => track.stop());
            this.mediaStream = null;
        }
        
        if (this.audioContext) {
            this.audioContext.close();
            this.audioContext = null;
        }
        
        // Clear audio queue
        this.audioQueue = [];
        this.isPlaying = false;
        
        // Close session
        if (this.session) {
            this.session.close();
            this.session = null;
        }
        
        this.isConnected = false;
        console.log('✅ Disconnected');
    }

    /**
     * Utility: ArrayBuffer to Base64
     */
    arrayBufferToBase64(buffer) {
        let binary = '';
        const bytes = new Uint8Array(buffer);
        for (let i = 0; i < bytes.byteLength; i++) {
            binary += String.fromCharCode(bytes[i]);
        }
        return btoa(binary);
    }

    /**
     * Utility: Base64 to ArrayBuffer
     */
    base64ToArrayBuffer(base64) {
        const binaryString = atob(base64);
        const len = binaryString.length;
        const bytes = new Uint8Array(len);
        for (let i = 0; i < len; i++) {
            bytes[i] = binaryString.charCodeAt(i);
        }
        return bytes.buffer;
    }

    /**
     * Set callback for text responses
     */
    onResponse(callback) {
        this.onResponseCallback = callback;
    }

    /**
     * Set callback for audio events
     */
    onAudio(callback) {
        this.onAudioCallback = callback;
    }
    
    /**
     * Set callback for animation commands
     */
    onAnimation(callback) {
        this.onAnimationCallback = callback;
    }
}
