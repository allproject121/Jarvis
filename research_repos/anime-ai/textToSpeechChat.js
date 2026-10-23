/**
 * Text-to-Speech AI Chat using Gemini 2.5 TTS API
 * User sends text → AI responds with audio
 */

import { GoogleGenAI } from '@google/genai';

export class TextToSpeechChat {
    constructor(apiKey) {
        this.ai = new GoogleGenAI({ apiKey });
        this.audioContext = new (window.AudioContext || window.webkitAudioContext)();
        this.currentSource = null;
        this.isPlaying = false;
        this.conversationHistory = [];
        
        // Callbacks
        this.onAudioStart = null;
        this.onAudioEnd = null;
        this.onError = null;
        this.onTextResponse = null;
    }

    /**
     * Generate text response from Gemini
     */
    async generateTextResponse(userMessage) {
        try {
            // Add system prompt for first message to guide AI behavior
            if (this.conversationHistory.length === 0) {
                this.conversationHistory.push({
                    role: 'user',
                    parts: [{ text: `You are Alicia, a friendly virtual idol anime character who can perform animations. 
When users greet you (xin chào, hello), respond warmly.
When users ask you to dance (nhảy, dance), be excited and mention you're dancing.
When users ask about actions (wave, clap, etc), respond as if you're doing them.
Keep responses short and conversational in Vietnamese or English based on user's language.
Example: If user says "hãy nhảy đi", respond: "Được rồi! *nhảy* Tôi đang nhảy đây, thật vui!" ` }]
                });
                
                this.conversationHistory.push({
                    role: 'model',
                    parts: [{ text: 'Xin chào! Tôi là Alicia, một virtual idol! Tôi có thể nói chuyện, nhảy múa và biểu diễn cho bạn. Bạn muốn làm gì?' }]
                });
            }
            
            // Add user message to history
            this.conversationHistory.push({
                role: 'user',
                parts: [{ text: userMessage }]
            });

            // Generate AI text response using standard Gemini model
            const textResponse = await this.ai.models.generateContent({
                model: 'gemini-2.0-flash-exp',
                contents: this.conversationHistory,
            });

            const aiText = textResponse.candidates?.[0]?.content?.parts?.[0]?.text || '';
            
            // Add AI response to history
            this.conversationHistory.push({
                role: 'model',
                parts: [{ text: aiText }]
            });

            if (this.onTextResponse) {
                this.onTextResponse(aiText);
            }

            return aiText;
        } catch (error) {
            console.error('Text generation error:', error);
            if (this.onError) {
                this.onError(error);
            }
            throw error;
        }
    }

    /**
     * Convert text to speech using Gemini 2.5 TTS
     */
    async textToSpeech(text, voiceName = 'Puck') {
        try {
            console.log('Converting to speech:', text);

            // Create prompt with Vietnamese context and friendly character
            const prompt = `Say this in a friendly, cheerful voice like an anime character: ${text}`;

            const response = await this.ai.models.generateContent({
                model: 'gemini-2.5-flash-preview-tts',
                contents: [{ parts: [{ text: prompt }] }],
                config: {
                    responseModalities: ['AUDIO'],
                    speechConfig: {
                        voiceConfig: {
                            prebuiltVoiceConfig: { voiceName }
                        }
                    }
                }
            });

            const audioData = response.candidates?.[0]?.content?.parts?.[0]?.inlineData?.data;
            
            if (!audioData) {
                throw new Error('No audio data received from TTS API');
            }

            return audioData; // Base64 encoded PCM audio
        } catch (error) {
            console.error('TTS error:', error);
            if (this.onError) {
                this.onError(error);
            }
            throw error;
        }
    }

    /**
     * Play audio from base64 PCM data
     */
    async playAudio(base64AudioData) {
        try {
            // Stop any currently playing audio
            this.stop();

            this.isPlaying = true;
            if (this.onAudioStart) {
                this.onAudioStart();
            }

            // Decode base64 to ArrayBuffer
            const binaryString = atob(base64AudioData);
            const bytes = new Uint8Array(binaryString.length);
            for (let i = 0; i < binaryString.length; i++) {
                bytes[i] = binaryString.charCodeAt(i);
            }

            // Gemini TTS outputs 24kHz, 16-bit PCM mono audio
            const sampleRate = 24000;
            const numChannels = 1;
            const bytesPerSample = 2; // 16-bit = 2 bytes

            // Convert PCM to Float32Array for Web Audio API
            const numSamples = bytes.length / bytesPerSample;
            const audioBuffer = this.audioContext.createBuffer(
                numChannels,
                numSamples,
                sampleRate
            );

            const channelData = audioBuffer.getChannelData(0);
            const dataView = new DataView(bytes.buffer);

            for (let i = 0; i < numSamples; i++) {
                // Read 16-bit signed integer and convert to float [-1, 1]
                const sample = dataView.getInt16(i * bytesPerSample, true);
                channelData[i] = sample / 32768.0;
            }

            // Create source and play
            this.currentSource = this.audioContext.createBufferSource();
            this.currentSource.buffer = audioBuffer;
            this.currentSource.connect(this.audioContext.destination);

            this.currentSource.onended = () => {
                this.isPlaying = false;
                if (this.onAudioEnd) {
                    this.onAudioEnd();
                }
            };

            this.currentSource.start(0);
            
            return audioBuffer.duration;
        } catch (error) {
            this.isPlaying = false;
            console.error('Audio playback error:', error);
            if (this.onError) {
                this.onError(error);
            }
            throw error;
        }
    }

    /**
     * Send message and get audio response
     */
    async sendMessage(userMessage, voiceName = 'Puck') {
        try {
            // 1. Generate text response
            const aiText = await this.generateTextResponse(userMessage);

            // 2. Convert to speech
            const audioData = await this.textToSpeech(aiText, voiceName);

            // 3. Play audio
            const duration = await this.playAudio(audioData);

            return {
                text: aiText,
                audioDuration: duration
            };
        } catch (error) {
            console.error('Send message error:', error);
            throw error;
        }
    }

    /**
     * Stop audio playback
     */
    stop() {
        if (this.currentSource) {
            try {
                this.currentSource.stop();
            } catch (e) {
                // Ignore if already stopped
            }
            this.currentSource = null;
        }
        this.isPlaying = false;
    }

    /**
     * Clear conversation history
     */
    clearHistory() {
        this.conversationHistory = [];
    }

    /**
     * Get available voice options
     */
    static getVoiceOptions() {
        return [
            // Bright/Upbeat voices (good for anime character)
            'Puck',      // Upbeat (recommended for cheerful character)
            'Zephyr',    // Bright
            'Fenrir',    // Excitable
            'Leda',      // Youthful
            'Kore',      // Firm
            'Aoede',     // Breezy
            
            // Other options
            'Charon',    // Informative
            'Callirrhoe', // Easy-going
            'Autonoe',   // Bright
            'Laomedeia', // Upbeat
        ];
    }
}
