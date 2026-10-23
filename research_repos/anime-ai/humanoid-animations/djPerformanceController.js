/**
 * DJ Performance Controller - Natural mixing of dancing and interaction animations
 * Tự động random giữa nhảy và tương tác với khán giả một cách tự nhiên
 */

import * as THREE from 'three';
import { loadMixamoAnimation } from './loadMixamoAnimation.js';

export class DJPerformanceController {
    constructor(vrm, mixer) {
        this.vrm = vrm;
        this.mixer = mixer;
        this.isPerforming = false;
        this.currentAction = null;
        this.performanceTimer = null;
        this.currentType = null;
        this.lastPlayedDancing = null;
        this.lastPlayedInteraction = null;

        // Animation libraries
        this.dancingAnimations = [
            'dj/dancing/dancing1.fbx',
            'dj/dancing/dancing2.fbx',
            'dj/dancing/dancing3.fbx',
            'dj/dancing/dancing4.fbx',
            'dj/dancing/dancing5.fbx',
            'dj/dancing/dancing6.fbx',
            'dj/dancing/dancing7.fbx',
            'dj/dancing/dancing8.fbx'
        ];

        this.interactionAnimations = [
            'dj/interaction/Blowakiss.fbx',
            'dj/interaction/Cheering.fbx',
            'dj/interaction/Raisyourhandup.fbx',
            'dj/interaction/Sayohyeah.fbx'
        ];

        // Performance patterns - weighted random
        this.patterns = [
            { type: 'dancing', minDuration: 8, maxDuration: 15, weight: 60 },  // 60% nhảy
            { type: 'interaction', minDuration: 2, maxDuration: 5, weight: 25 }, // 25% tương tác
            { type: 'dancing', minDuration: 4, maxDuration: 8, weight: 15 }     // 15% nhảy ngắn
        ];
    }

    /**
     * Bắt đầu performance tự động
     */
    async startPerformance() {
        if (this.isPerforming) {
            console.log('🎭 Performance already running');
            return;
        }

        this.isPerforming = true;
        console.log('🎉 DJ Performance started!');
        
        await this.playNextAnimation();
    }

    /**
     * Dừng performance
     */
    stopPerformance() {
        this.isPerforming = false;
        if (this.performanceTimer) {
            clearTimeout(this.performanceTimer);
            this.performanceTimer = null;
        }
        console.log('⏸️ DJ Performance stopped');
    }

    /**
     * Chọn pattern tiếp theo dựa trên weight
     */
    selectNextPattern() {
        const totalWeight = this.patterns.reduce((sum, p) => sum + p.weight, 0);
        let random = Math.random() * totalWeight;
        
        for (const pattern of this.patterns) {
            random -= pattern.weight;
            if (random <= 0) {
                return pattern;
            }
        }
        
        return this.patterns[0];
    }

    /**
     * Random chọn animation từ library (không lặp lại animation vừa chơi)
     */
    getRandomAnimation(type) {
        const library = type === 'dancing' ? this.dancingAnimations : this.interactionAnimations;
        const lastPlayed = type === 'dancing' ? this.lastPlayedDancing : this.lastPlayedInteraction;
        
        // Nếu chỉ có 1 animation trong library, return luôn
        if (library.length === 1) {
            return library[0];
        }
        
        // Filter ra animations khác với last played
        const availableAnimations = lastPlayed 
            ? library.filter(anim => anim !== lastPlayed)
            : library;
        
        // Random từ available animations
        const randomIndex = Math.floor(Math.random() * availableAnimations.length);
        return availableAnimations[randomIndex];
    }

    /**
     * Random duration trong khoảng
     */
    getRandomDuration(min, max) {
        return min + Math.random() * (max - min);
    }

    /**
     * Play animation tiếp theo
     */
    async playNextAnimation() {
        if (!this.isPerforming) return;

        // Chọn pattern tiếp theo
        const pattern = this.selectNextPattern();
        const animationFile = this.getRandomAnimation(pattern.type);
        const duration = this.getRandomDuration(pattern.minDuration, pattern.maxDuration);

        // Store as last played to prevent repeats
        if (pattern.type === 'dancing') {
            this.lastPlayedDancing = animationFile;
        } else {
            this.lastPlayedInteraction = animationFile;
        }

        console.log(`🎵 Playing ${pattern.type}: ${animationFile.split('/').pop()} (${duration.toFixed(1)}s)`);

        try {
            // Load và play animation
            const clip = await loadMixamoAnimation(`./${animationFile}`, this.vrm);
            
            if (!clip) {
                console.warn('⚠️ Failed to load animation, trying next...');
                // Retry with delay
                this.performanceTimer = setTimeout(() => this.playNextAnimation(), 1000);
                return;
            }

            const newAction = this.mixer.clipAction(clip);
            
            // Crossfade from current action
            if (this.currentAction) {
                const fadeTime = pattern.type === 'interaction' ? 0.3 : 0.5;
                newAction.reset();
                newAction.fadeIn(fadeTime);
                this.currentAction.fadeOut(fadeTime);
            } else {
                newAction.fadeIn(0.5);
            }

            newAction.setLoop(THREE.LoopRepeat);
            newAction.play();

            this.currentAction = newAction;
            this.currentType = pattern.type;

            // Schedule next animation
            this.performanceTimer = setTimeout(() => {
                this.playNextAnimation();
            }, duration * 1000);

        } catch (error) {
            console.error('❌ Error loading animation:', error);
            // Retry after short delay
            this.performanceTimer = setTimeout(() => this.playNextAnimation(), 2000);
        }
    }

    /**
     * Manual trigger specific type (for testing)
     */
    async playDancing() {
        const animFile = this.getRandomAnimation('dancing');
        console.log('🕺 Manual dancing:', animFile);
        
        try {
            const clip = await loadMixamoAnimation(`./${animFile}`, this.vrm);
            if (clip) {
                const action = this.mixer.clipAction(clip);
                
                if (this.currentAction) {
                    action.reset();
                    action.fadeIn(0.5);
                    this.currentAction.fadeOut(0.5);
                }
                
                action.setLoop(THREE.LoopRepeat);
                action.play();
                this.currentAction = action;
            }
        } catch (error) {
            console.error('Error:', error);
        }
    }

    async playInteraction() {
        const animFile = this.getRandomAnimation('interaction');
        console.log('👋 Manual interaction:', animFile);
        
        try {
            const clip = await loadMixamoAnimation(`./${animFile}`, this.vrm);
            if (clip) {
                const action = this.mixer.clipAction(clip);
                
                if (this.currentAction) {
                    action.reset();
                    action.fadeIn(0.3);
                    this.currentAction.fadeOut(0.3);
                }
                
                action.setLoop(THREE.LoopRepeat);
                action.play();
                this.currentAction = action;
            }
        } catch (error) {
            console.error('Error:', error);
        }
    }

    /**
     * Adjust performance energy (ảnh hưởng tới tỉ lệ dancing/interaction)
     */
    setEnergy(level) {
        // level: 'low', 'medium', 'high'
        switch(level) {
            case 'low':
                // Nhiều tương tác hơn, ít nhảy
                this.patterns[0].weight = 40; // dancing dài
                this.patterns[1].weight = 40; // interaction
                this.patterns[2].weight = 20; // dancing ngắn
                break;
            case 'high':
                // Nhiều nhảy hơn, ít tương tác
                this.patterns[0].weight = 70; // dancing dài
                this.patterns[1].weight = 15; // interaction
                this.patterns[2].weight = 15; // dancing ngắn
                break;
            default: // medium
                this.patterns[0].weight = 60;
                this.patterns[1].weight = 25;
                this.patterns[2].weight = 15;
        }
        
        console.log(`⚡ Energy level set to: ${level}`);
    }

    /**
     * Get current performance stats
     */
    getStats() {
        return {
            isPerforming: this.isPerforming,
            currentType: this.currentType,
            totalDancing: this.dancingAnimations.length,
            totalInteractions: this.interactionAnimations.length
        };
    }
}
