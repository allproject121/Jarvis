const fs = require('fs');
const filePath = 'app/src/main/assets/orb.html';
let content = fs.readFileSync(filePath, 'utf8');

const isCRLF = content.includes('\r\n');
let normalized = content.replace(/\r\n/g, '\n');

const target = `<<<<<<< HEAD
    } else if (animations && animations.length > 0) {
      customModelMixer = new THREE.AnimationMixer(object);
      customModelMixer.clipAction(animations[0]).play();
=======
    }

    // Load Anima motion library (.glb) for standard humanoid rigs without built-in animations
    if (!isVRoid && typeof THREE.GLTFLoader !== "undefined") {
      const animaMotions = [
        { name: "wave", url: "https://appassets.androidplatform.net/assets/motions/wave_290.glb" },
        { name: "shrug", url: "https://appassets.androidplatform.net/assets/motions/shrug_317.glb" },
        { name: "talk_expressive", url: "https://appassets.androidplatform.net/assets/motions/talk_314.glb" },
        { name: "idle_alt1", url: "https://appassets.androidplatform.net/assets/motions/idle_243.glb" },
        { name: "idle_alt2", url: "https://appassets.androidplatform.net/assets/motions/idle_249.glb" },
        { name: "idle_alt3", url: "https://appassets.androidplatform.net/assets/motions/idle_252.glb" }
      ];
      const loader = new THREE.GLTFLoader();
      animaMotions.forEach(function(m) {
        loader.load(
          m.url,
          function(gltf) {
            if (gltf.animations && gltf.animations.length > 0 && customModelEngine) {
              customModelEngine.addAnimaMotion(m.name, gltf.animations[0], gltf.scene);
            }
          },
          undefined,
          function(err) {
            console.warn("Could not load motion:", m.name, err);
          }
        );
      });
>>>>>>> de7a5fe (feat(character): REDMAGIC Mora-inspired interactive 3D anime companion with skeletal retargeting, barge-in, and learning reactions)`;

const replacement = `    } else if (animations && animations.length > 0) {
      customModelMixer = new THREE.AnimationMixer(object);
      customModelMixer.clipAction(animations[0]).play();
    }

    // Load Anima motion library (.glb) for standard humanoid rigs without built-in animations
    if (!isVRoid && typeof THREE.GLTFLoader !== "undefined") {
      const animaMotions = [
        { name: "wave", url: "https://appassets.androidplatform.net/assets/motions/wave_290.glb" },
        { name: "shrug", url: "https://appassets.androidplatform.net/assets/motions/shrug_317.glb" },
        { name: "talk_expressive", url: "https://appassets.androidplatform.net/assets/motions/talk_314.glb" },
        { name: "idle_alt1", url: "https://appassets.androidplatform.net/assets/motions/idle_243.glb" },
        { name: "idle_alt2", url: "https://appassets.androidplatform.net/assets/motions/idle_249.glb" },
        { name: "idle_alt3", url: "https://appassets.androidplatform.net/assets/motions/idle_252.glb" }
      ];
      const loader = new THREE.GLTFLoader();
      animaMotions.forEach(function(m) {
        loader.load(
          m.url,
          function(gltf) {
            if (gltf.animations && gltf.animations.length > 0 && customModelEngine) {
              customModelEngine.addAnimaMotion(m.name, gltf.animations[0], gltf.scene);
            }
          },
          undefined,
          function(err) {
            console.warn("Could not load motion:", m.name, err);
          }
        );
      });`;

if (!normalized.includes(target)) {
  console.error('Target not found in orb.html');
  process.exit(1);
}

let updated = normalized.replace(target, replacement);
if (isCRLF) updated = updated.replace(/\n/g, '\r\n');
fs.writeFileSync(filePath, updated, 'utf8');
console.log('Successfully resolved conflict in orb.html');
