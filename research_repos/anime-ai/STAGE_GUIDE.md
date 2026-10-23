# 🎭 Stage Environment Guide

## Tổng Quan

Hệ thống sân khấu 3D cho Virtual Idol với:
- ✨ Platform sàn sân khấu với LED glow
- 🎧 DJ Booth với turntables và mixer
- 💡 Dynamic lighting (concert, studio, simple modes)
- 🎨 Custom backgrounds (gradient, space, stage curtain)
- 🖼️ LED screen decorations
- 🌈 Animated lighting effects

---

## 🚀 Quick Start

### 1. Test Sân Khấu

```bash
npm run dev
```

Mở: http://localhost:5173/humanoid-animations/stage-test.html

### 2. Test với AI Livestream

Mở: http://localhost:5173/humanoid-animations/live.html

Sân khấu đã được tự động tích hợp!

---

## 🎨 Stage Themes

### Cyberpunk (Default)
```javascript
stage.setTheme('cyberpunk');
```
- Full platform với LED edges
- DJ Booth với equipment
- Concert lighting (4 colored spotlights)
- Gradient background
- LED screen decorations

### Minimal
```javascript
stage.setTheme('minimal');
```
- Simple platform only
- No DJ booth
- Studio 3-point lighting
- Clean gradient background
- No decorations

### Space
```javascript
stage.setTheme('space');
```
- Platform với space theme
- DJ Booth
- Dynamic concert lighting
- Starfield background
- LED screens

---

## 💡 Lighting Modes

### Concert Mode (Dynamic)
```javascript
stage.setupLighting('concert');
```
- Main spotlight từ trên xuống
- 4 colored spotlights (pink, cyan, yellow, green)
- Rotating animated lights
- Rim/backlight cho depth

### Studio Mode (3-Point)
```javascript
stage.setupLighting('studio');
```
- Key light (main illumination)
- Fill light (soft shadows)
- Back light (separation from background)

### Simple Mode
```javascript
stage.setupLighting('simple');
```
- Single directional light
- Minimal setup cho performance

---

## 🎨 Backgrounds

### Gradient
```javascript
stage.setupBackground('gradient');
```
Dark blue gradient (cyberpunk style)

### Space
```javascript
stage.setupBackground('space');
```
Starfield với 500 random stars

### Stage Curtain
```javascript
stage.setupBackground('stage');
```
Traditional stage backdrop

---

## 🎛️ Manual Control

### Setup Custom Configuration

```javascript
// Import stage
import { StageEnvironment } from './stageEnvironment.js';

// Create instance
const stage = new StageEnvironment(scene);

// Setup with custom config
stage.setup({
    platform: true,      // Show platform
    djBooth: true,       // Show DJ booth
    lighting: 'concert', // Lighting mode
    background: 'gradient', // Background type
    decorations: true    // Show LED screens
});
```

### Toggle Elements

```javascript
// Hide/show entire stage
stage.setVisible(false);
stage.setVisible(true);

// Clear and rebuild
stage.clear();
stage.setup({ platform: true, djBooth: false });
```

### Update in Animation Loop

```javascript
// In your render loop
function animate() {
    const deltaTime = clock.getDelta();
    const elapsedTime = clock.elapsedTime;
    
    // Update stage (animates lighting effects)
    stage.update(deltaTime, elapsedTime);
    
    renderer.render(scene, camera);
}
```

---

## 🎪 Stage Elements Detail

### Platform
- 6m x 4m main stage
- 0.2m height
- Metallic material (dark blue)
- LED edge glow (cyan)
- Grid pattern overlay

### DJ Booth
- 2m x 1m desk
- 4 metal legs
- 2 turntables (left/right)
- Mixer (center)
- LED strip (pink/purple)

### LED Screens
- 2 side screens (1.5m x 2m)
- Angled towards center
- Pulsing glow effect
- Cyan emissive material

---

## 🌟 Advanced Customization

### Modify Stage Colors

```javascript
// Edit stageEnvironment.js
const platformMaterial = new THREE.MeshStandardMaterial({
    color: 0x1a1a2e,     // ← Change this
    metalness: 0.8,
    roughness: 0.2
});
```

### Add Custom Objects

```javascript
// In stageEnvironment.js - createDecorations()
const customObject = new THREE.Mesh(geometry, material);
customObject.position.set(x, y, z);
this.scene.add(customObject);
this.stageObjects.push(customObject); // For cleanup
```

### Custom Lighting

```javascript
// Add your own lights
const myLight = new THREE.SpotLight(0xff0000, 2);
myLight.position.set(0, 5, 0);
scene.add(myLight);
```

---

## 🎬 Integration Examples

### Example 1: Stage + VRM + AI

```javascript
import { StageEnvironment } from './stageEnvironment.js';

// Setup stage
const stage = new StageEnvironment(scene);
stage.setup({ 
    lighting: 'concert',
    djBooth: true 
});

// Load VRM
loadVRM('./characters/idol.vrm');

// Connect AI
connectAI();

// Animate
function animate() {
    stage.update(deltaTime, elapsedTime);
    vrm.update(deltaTime);
    mixer.update(deltaTime);
    renderer.render(scene, camera);
}
```

### Example 2: Dynamic Stage Changes

```javascript
// Change stage based on animation
function onAnimationChange(animationType) {
    switch(animationType) {
        case 'dance':
            stage.setupLighting('concert');
            break;
        case 'talk':
            stage.setupLighting('studio');
            break;
        case 'idle':
            stage.setupLighting('simple');
            break;
    }
}
```

### Example 3: Sync Lights with Music

```javascript
// In animation loop
function animate() {
    // Get audio analysis data
    const bassLevel = audioAnalyzer.getBass();
    
    // Pulse lights with bass
    stage.lights.forEach(light => {
        if (light.type === 'SpotLight') {
            light.intensity = 2 + bassLevel * 3;
        }
    });
    
    stage.update(deltaTime, elapsedTime);
}
```

---

## 🎮 Browser Console Commands

When testing in browser:

```javascript
// Access stage
window.stage

// Change theme
stage.setTheme('space')

// Toggle visibility
stage.setVisible(false)

// Custom lighting
stage.setupLighting('studio')

// Clear everything
stage.clear()

// Rebuild
stage.setup({ platform: true, djBooth: true })
```

---

## 📊 Performance Notes

### Optimization Tips

1. **Disable decorations** if frame rate drops:
   ```javascript
   stage.setup({ decorations: false });
   ```

2. **Use simple lighting** for better performance:
   ```javascript
   stage.setupLighting('simple');
   ```

3. **Reduce shadow quality** in renderer:
   ```javascript
   renderer.shadowMap.enabled = false;
   ```

### Performance Impact

- Full stage (all elements): ~60 FPS on average GPU
- Minimal stage: ~90+ FPS
- Lighting has biggest impact (concert > studio > simple)

---

## 🐛 Troubleshooting

### Stage không hiển thị
```javascript
// Check if stage exists
console.log(window.stage);

// Rebuild stage
stage.clear();
stage.setup();
```

### Lighting quá tối
```javascript
// Increase light intensity
stage.lights.forEach(light => {
    light.intensity *= 1.5;
});
```

### Animated effects không chạy
```javascript
// Make sure update() is called in animate loop
function animate() {
    stage.update(deltaTime, elapsedTime); // ← This!
    // ...
}
```

---

## 🎯 Next Steps

1. **Add External 3D Models**: Load GLB/GLTF stage props
2. **Music Reactive Lights**: Sync with audio analysis
3. **Particle Effects**: Add sparkles, confetti, fog
4. **Video Textures**: Display videos on LED screens
5. **Interactive Props**: Clickable objects for audience

---

## 📚 File Structure

```
humanoid-animations/
├── stageEnvironment.js    ← Main stage system
├── stage-test.html        ← Testing interface
├── main.js                ← Integration (updated)
└── live.html              ← AI livestream (auto includes stage)
```

---

## 🎉 Example Results

Visit these URLs after `npm run dev`:

1. **Stage Test UI**: http://localhost:5173/humanoid-animations/stage-test.html
   - Interactive controls
   - Theme switcher
   - Toggle elements
   - Real-time preview

2. **AI Livestream**: http://localhost:5173/humanoid-animations/live.html
   - Full AI + animations + stage
   - Chat with idol on stage
   - Dynamic lighting effects

3. **Simple Test**: http://localhost:5173/humanoid-animations/test.html
   - Original test page
   - Now with stage!

---

Happy staging! 🎪✨
