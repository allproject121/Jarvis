/**
 * Animation Library - Định nghĩa thư viện animations cho AI
 * Mỗi action có:
 * - files: Danh sách .fbx files cho action đó
 * - description: Mô tả cho AI function calling
 * - duration: Thời gian mặc định (giây)
 * - priority: Độ ưu tiên (cao hơn = ưu tiên hơn khi conflict)
 */

export const animationLibrary = {
    idle: {
        files: ['Idle.fbx'],
        description: 'Default standing idle pose - use when no other action is needed',
        duration: 0, // Loop forever
        priority: 0
    },
    
    greet: {
        files: ['Waving.fbx'],
        description: 'Greeting gestures - wave hand to say hello or goodbye to fans',
        duration: 1,
        priority: 5
    },
    
    dance: {
        files: [
            'HipHopDancing.fbx',
            'Flail.fbx', 
            'NorthernSoulFloorCombo.fbx',
            'BreakdanceFreeze.fbx',
            'Breakdance1990.fbx',
            'Breakdance19902.fbx'
        ],
        description: 'Dance performances - various energetic dance styles for entertainment',
        duration: 15,
        priority: 8
    },
    
    excited: {
        files: ['Excited.fbx', 'Clapping.fbx'],
        description: 'Excited happy reactions - Excited, Clapping, celebrating good news',
        duration: 4,
        priority: 7
    },
    
    laugh: {
        files: ['Laughing.fbx', 'Clapping.fbx'],
        description: 'Laughing and clapping - react to funny jokes or amusing moments',
        duration: 3,
        priority: 6
    },
    
    thinking: {
        files: ['Thinking.fbx'],
        description: 'Contemplative poses - thinking gesture when considering a question',
        duration: 4,
        priority: 4
    },
    
    sad: {
        files: ['Crying.fbx', 'Defeated.fbx'],
        description: 'Sad emotions - crying or feeling defeated for sad topics',
        duration: 5,
        priority: 6
    },
    
    bow: {
        files: ['ThankYou.fbx'],
        description: 'Respectful bow - thank fans or apologize politely',
        duration: 3,
        priority: 5
    },
    
    heart: {
        files: ['BlowingKiss.fbx'],
        description: 'Show love to fans - blowing kiss',
        duration: 3,
        priority: 7
    },
    
    surprised: {
        files: ['Surprised.fbx', 'Shocked.fbx'],
        description: 'Surprised reaction - reacting to unexpected or shocking news',
        duration: 2,
        priority: 6
    }
};

/**
 * Get animation tools definition for Gemini Function Calling
 */
export function getAnimationTools() {
    return {
        function_declarations: Object.entries(animationLibrary).map(([action, config]) => ({
            name: `play_${action}`,
            description: config.description,
            parameters: {
                type: "object",
                properties: {
                    duration: {
                        type: "number",
                        description: `How long to play animation in seconds (default: ${config.duration || 'infinite'})`
                    },
                    intensity: {
                        type: "number", 
                        description: "Animation intensity from 0.0 to 1.0 (default: 1.0)",
                        minimum: 0,
                        maximum: 1
                    }
                }
            }
        }))
    };
}

/**
 * Random chọn file từ action category
 */
export function getRandomAnimationFile(action) {
    const config = animationLibrary[action];
    if (!config) return null;
    
    const files = config.files;
    const randomIndex = Math.floor(Math.random() * files.length);
    return {
        file: files[randomIndex],
        defaultDuration: config.duration,
        priority: config.priority
    };
}

/**
 * Parse function call name thành action name
 */
export function parseActionName(functionName) {
    // "play_dance" → "dance"
    return functionName.replace('play_', '');
}
