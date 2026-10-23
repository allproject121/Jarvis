# AI Coding Agent Instructions for VRM Character Animation System

## Project Overview
This is a 3D VRM (Virtual Reality Model) character system for AI voice streaming applications. It loads VRM avatars, converts Mixamo FBX animations to VRM-compatible format, and provides procedural lip-sync through mouth expressions.

## Architecture
- **Core Module**: `humanoid-animations/main.js` - Handles VRM loading, Three.js scene setup, animation mixing, and drag-and-drop functionality
- **Animation Conversion**: `humanoid-animations/loadMixamoAnimation.js` - Converts Mixamo FBX animations to VRM bone structure using bone mapping
- **Bone Mapping**: `humanoid-animations/mixamoVRMRigMap.js` - Maps Mixamo rig names to VRM Humanoid bone names
- **Demo Interface**: `humanoid-animations/test.html` - Browser-based testing with drag-and-drop for FBX/VRM files

## Key Patterns & Conventions

### VRM Loading & Optimization
```javascript
// Always apply VRM optimizations after loading
VRMUtils.removeUnnecessaryVertices(gltf.scene);
VRMUtils.combineSkeletons(gltf.scene);
VRMUtils.combineMorphs(vrm);

// Rotate VRM0.0 models
VRMUtils.rotateVRM0(vrm);

// Disable frustum culling for VRM scenes
vrm.scene.traverse((obj) => {
    obj.frustumCulled = false;
});
```

### Animation System
- Use `THREE.AnimationMixer` for VRM animations
- Crossfade between animations with 0.5-second transitions: `action.crossFadeTo(newAction, 0.5, false)`
- Loop animations: `action.loop = THREE.LoopRepeat`
- Load Mixamo animations: `const clip = await loadMixamoAnimation(url, currentVrm)`

### Lip-Sync Implementation
```javascript
// Procedural mouth movement in animation loop
const mouthValue = 0.3 + 0.3 * Math.sin(Math.PI * elapsedTime * 2);
vrm.expressionManager.setValue('aa', mouthValue);
```

### Bone Mapping
- Reference `mixamoVRMRigMap` for converting Mixamo bone names to VRM standard
- Handles position scaling based on hips height difference between Mixamo and VRM
- Accounts for VRM0 vs VRM1 coordinate system differences (handedness flips)

## Development Workflows

### Building & Running
- `npm run dev` - Start Vite development server
- `npm run build` - Compile TypeScript and build for production
- Test animations by dragging FBX files onto `humanoid-animations/test.html` in browser

### Adding New Animations
1. Download FBX from Mixamo with T-pose
2. Place in `humanoid-animations/` directory
3. Load via `loadFBX('./AnimationName.fbx')` or drag-and-drop in test interface

### Model Management
- Store VRM files in `characters/` directory
- Default model loads automatically: `characters/VRM1_Alicia_Solid.vrm`
- Supports runtime model switching via drag-and-drop

## Integration Points
- **Three.js Ecosystem**: Uses GLTFLoader, OrbitControls, AnimationMixer
- **VRM Standard**: @pixiv/three-vrm for model loading and utilities
- **Animation Sources**: Mixamo FBX files converted to VRM-compatible clips
- **Future TTS**: Google Gemini API integration planned (see README for API setup)

## Common Gotchas
- Always check VRM version (0.0 vs 1.0) for coordinate system differences
- Mixamo animations require T-pose and specific rig structure
- Expression 'aa' controls mouth opening for lip-sync
- AnimationMixer must be updated in render loop: `currentMixer.update(deltaTime)`
- VRM scenes need `vrm.update(deltaTime)` in animation loop

## File Structure Reference
- `humanoid-animations/main.js` - Core 3D scene and VRM management
- `humanoid-animations/loadMixamoAnimation.js` - FBX to VRM animation conversion
- `humanoid-animations/mixamoVRMRigMap.js` - Bone name mapping dictionary
- `characters/` - VRM model storage directory
- `humanoid-animations/test.html` - Browser testing interface</content>
<parameter name="filePath">/home/zetonopo/Desktop/anime-ai/.github/copilot-instructions.md