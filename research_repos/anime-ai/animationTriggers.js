/**
 * Keyword-based Animation Triggers
 * Detects keywords in user/AI text and triggers corresponding animations
 */

export class AnimationTriggers {
    constructor(animationHandler, audioHandler) {
        this.animationHandler = animationHandler;
        this.audioHandler = audioHandler; // Callback to play audio: (audioFile) => void
        
        // Keyword mappings to { action, audio }
        this.keywordMap = {
            // Greetings → Wave + audio
            'xin chào': { action: 'greet', audio: 'onii-chan.mp3' },
            'chào': { action: 'greet', audio: 'onii-chan.mp3' },
            'hello': { action: 'greet', audio: 'onii-chan.mp3' },
            'hi': { action: 'greet', audio: 'onii-chan.mp3' },
            'hey': { action: 'greet', audio: 'onii-chan.mp3' },
            'vẫy tay': { action: 'greet', audio: 'onii-chan.mp3' },
            'vẫy': { action: 'greet', audio: 'onii-chan.mp3' },
            
            // Dancing keywords → Dance + random audio (nya or bebe-lin)
            'nhảy': { action: 'dance', audio: ['nya_cat_girl.mp3', 'bebe-lin-otaku.mp3', 'aiscream-nani-ga-suki.mp3', 'kobayashi-kanna-kamui-cute-singing-remix-0-13.mp3'] },
            'múa': { action: 'dance', audio: ['nya_cat_girl.mp3', 'bebe-lin-otaku.mp3', 'aiscream-nani-ga-suki.mp3', 'kobayashi-kanna-kamui-cute-singing-remix-0-13.mp3'] },
            'dance': { action: 'dance', audio: ['nya_cat_girl.mp3', 'bebe-lin-otaku.mp3', 'aiscream-nani-ga-suki.mp3', 'kobayashi-kanna-kamui-cute-singing-remix-0-13.mp3'] },
            'nhảy đi': { action: 'dance', audio: ['nya_cat_girl.mp3', 'bebe-lin-otaku.mp3', 'aiscream-nani-ga-suki.mp3', 'kobayashi-kanna-kamui-cute-singing-remix-0-13.mp3'] },
            'nhảy nào': { action: 'dance', audio: ['nya_cat_girl.mp3', 'bebe-lin-otaku.mp3', 'aiscream-nani-ga-suki.mp3', 'kobayashi-kanna-kamui-cute-singing-remix-0-13.mp3'] },
            'khiêu vũ': { action: 'dance', audio: ['nya_cat_girl.mp3', 'bebe-lin-otaku.mp3', 'aiscream-nani-ga-suki.mp3', 'kobayashi-kanna-kamui-cute-singing-remix-0-13.mp3'] },
            
            // UwU sounds
            'uwu': { action: 'excited', audio: 'uwu-discord-gorl.mp3' },
            'hãy nói uwu': { action: 'excited', audio: 'uwu-discord-gorl.mp3' },
            'nói uwu': { action: 'excited', audio: 'uwu-discord-gorl.mp3' },
            
            // Happy/Excited → Happy animations
            'vui': { action: 'excited', audio: 'nya_cat_girl.mp3' },
            'hạnh phúc': { action: 'excited', audio: 'nya_cat_girl.mp3' },
            'happy': { action: 'excited', audio: 'nya_cat_girl.mp3' },
            'excited': { action: 'excited', audio: 'nya_cat_girl.mp3' },
            'yay': { action: 'excited', audio: 'nya_cat_girl.mp3' },
            
            // Sad → Sad animations
            'buồn': { action: 'sad', audio: null },
            'sad': { action: 'sad', audio: null },
            'khóc': { action: 'sad', audio: null },
            'cry': { action: 'sad', audio: null },
            
            // Thinking → Thinking animations
            'suy nghĩ': { action: 'thinking', audio: null },
            'think': { action: 'thinking', audio: null },
            'hmm': { action: 'thinking', audio: null },
            
            // Laughing
            'cười': { action: 'laugh', audio: 'nya_cat_girl.mp3' },
            'laugh': { action: 'laugh', audio: 'nya_cat_girl.mp3' },
            'haha': { action: 'laugh', audio: 'nya_cat_girl.mp3' },
            'funny': { action: 'laugh', audio: 'nya_cat_girl.mp3' },
            
            // Heart/Love gestures
            'yêu': { action: 'heart', audio: 'onii-chan.mp3' },
            'love': { action: 'heart', audio: 'onii-chan.mp3' },
            'thương': { action: 'heart', audio: 'onii-chan.mp3' },
            'kiss': { action: 'heart', audio: 'onii-chan.mp3' },
            
            // Idle → Return to idle
            'dừng': { action: 'idle', audio: null },
            'stop': { action: 'idle', audio: null },
            'nghỉ': { action: 'idle', audio: null },
            'idle': { action: 'idle', audio: null },
        };
    }
    
    /**
     * Detect keywords in text and trigger animations + audio
     * Returns true if animation was triggered
     */
    async detectAndTrigger(text) {
        if (!text || !this.animationHandler) return false;
        
        const lowerText = text.toLowerCase().trim();
        console.log(`🔍 Scanning text for keywords: "${lowerText}"`);
        
        // Check each keyword
        for (const [keyword, config] of Object.entries(this.keywordMap)) {
            if (lowerText.includes(keyword)) {
                const action = config.action;
                let audio = config.audio;
                
                // If audio is array, pick random
                if (Array.isArray(audio)) {
                    audio = audio[Math.floor(Math.random() * audio.length)];
                }
                
                console.log(`🎬 Detected keyword "${keyword}" → triggering "${action}" animation + audio: ${audio}`);
                
                // Trigger audio first to get duration
                let audioDuration = null;
                if (audio && this.audioHandler) {
                    audioDuration = await this.audioHandler(audio);
                    console.log(`⏱️ Audio duration: ${audioDuration}s`);
                }
                
                // Trigger animation with audio duration
                this.animationHandler(action, audioDuration);
                
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Process user message and trigger appropriate animation
     */
    async processUserMessage(userText) {
        return await this.detectAndTrigger(userText);
    }
    
    /**
     * Process AI response and trigger appropriate animation
     * Also handles AI describing its own actions
     */
    async processAIResponse(aiText) {
        // First check for self-action descriptions
        const actionPatterns = [
            /\*vẫy tay\*/i,
            /\*nhảy\*/i,
            /\*múa\*/i,
            /\*wave\*/i,
            /\*dance\*/i,
        ];
        
        for (const pattern of actionPatterns) {
            if (pattern.test(aiText)) {
                const match = aiText.match(pattern)[0];
                console.log(`🎬 AI self-action detected: ${match}`);
                await this.detectAndTrigger(match);
                return true;
            }
        }
        
        // Then check for regular keywords
        return await this.detectAndTrigger(aiText);
    }
    
    /**
     * Add custom keyword mapping
     */
    addKeyword(keyword, action) {
        this.keywordMap[keyword.toLowerCase()] = action;
    }
    
    /**
     * Remove keyword mapping
     */
    removeKeyword(keyword) {
        delete this.keywordMap[keyword.toLowerCase()];
    }
    
    /**
     * Get all registered keywords
     */
    getKeywords() {
        return { ...this.keywordMap };
    }
}
