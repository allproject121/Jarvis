/**
 * AudioWorklet Processor for capturing microphone audio
 * Converts audio to PCM16 format for Gemini Live API
 */

class AudioProcessor extends AudioWorkletProcessor {
    constructor() {
        super();
        this.bufferSize = 4096;
        this.buffer = new Float32Array(this.bufferSize);
        this.bufferIndex = 0;
    }
    
    process(inputs, outputs, parameters) {
        const input = inputs[0];
        
        if (input.length > 0) {
            const channelData = input[0]; // Mono channel
            
            for (let i = 0; i < channelData.length; i++) {
                this.buffer[this.bufferIndex++] = channelData[i];
                
                // Send buffer when full
                if (this.bufferIndex >= this.bufferSize) {
                    // Convert Float32 to Int16 PCM
                    const pcm16 = this.floatToPCM16(this.buffer);
                    
                    // Send to main thread
                    this.port.postMessage(pcm16.buffer);
                    
                    // Reset buffer
                    this.bufferIndex = 0;
                }
            }
        }
        
        return true; // Keep processor alive
    }
    
    /**
     * Convert Float32Array [-1, 1] to Int16Array PCM
     */
    floatToPCM16(float32Array) {
        const int16Array = new Int16Array(float32Array.length);
        
        for (let i = 0; i < float32Array.length; i++) {
            // Clamp to [-1, 1]
            let sample = Math.max(-1, Math.min(1, float32Array[i]));
            
            // Convert to 16-bit PCM
            sample = sample < 0 ? sample * 0x8000 : sample * 0x7FFF;
            int16Array[i] = Math.round(sample);
        }
        
        return int16Array;
    }
}

registerProcessor('audio-processor', AudioProcessor);
