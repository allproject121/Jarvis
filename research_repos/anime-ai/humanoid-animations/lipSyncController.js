/**
 * Audio-driven Lip Sync
 * Phân tích audio output từ AI → control VRM mouth expressions
 * Currently using Web Speech API (will upgrade to Gemini Live API when available)
 */

export class LipSyncController {
    constructor(vrm) {
        this.vrm = vrm;
        this.isPlaying = false;
        this.animationFrame = null;
        
        // Smoothing
        this.smoothFactor = 0.7;
        this.currentVolume = 0;
        this.targetVolume = 0;
    }
    
    /**
     * Handle audio data from AI
     * For now, just triggers lip movement on 'speaking' signal
     */
    async playAudio(audioData) {
        if (audioData === 'speaking') {
            this.startLipSync();
        } else if (audioData === 'stopped') {
            this.stopLipSync();
        }
    }
    
    /**
     * Start lip sync animation with random mouth movement
     */
    startLipSync() {
        if (this.isPlaying) return;
        
        this.isPlaying = true;
        const startTime = Date.now();
        
        const animate = () => {
            if (!this.isPlaying) return;
            
            const elapsed = (Date.now() - startTime) / 1000;
            
            // Simulate natural talking with varying mouth opening
            const baseOpen = 0.4;
            const variation = 0.3 * Math.sin(elapsed * 15); // Fast movement
            const mouthOpen = Math.max(0, Math.min(1, baseOpen + variation));
            
            // Apply to VRM
            if (this.vrm?.expressionManager) {
                this.vrm.expressionManager.setValue('aa', mouthOpen);
                
                // Add subtle variations
                const variation2 = Math.sin(elapsed * 8) * 0.1;
                this.vrm.expressionManager.setValue('ih', Math.abs(variation2));
            }
            
            this.animationFrame = requestAnimationFrame(animate);
        };
        
        animate();
    }
    
    /**
     * Stop lip sync and close mouth
     */
    stopLipSync() {
        this.isPlaying = false;
        
        if (this.animationFrame) {
            cancelAnimationFrame(this.animationFrame);
            this.animationFrame = null;
        }
        
        this.closeMouth();
    }
    
    /**
     * Close mouth (idle state)
     */
    closeMouth() {
        if (this.vrm?.expressionManager) {
            this.vrm.expressionManager.setValue('aa', 0);
            this.vrm.expressionManager.setValue('ih', 0);
            this.vrm.expressionManager.setValue('ou', 0);
            this.vrm.expressionManager.setValue('ee', 0);
        }
    }
    
    /**
     * Start talking animation (called when AI speaks)
     */
    startTalking() {
        this.startLipSync();
    }
    
    /**
     * Stop talking animation
     */
    stopTalking() {
        this.stopLipSync();
    }
}
