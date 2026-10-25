global.self = global;
global.window = global;
const fs = require('fs');
const path = require('path');
const THREE = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three'));
const { GLTFLoader } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/loaders/GLTFLoader.js'));

// Load mora_clips.js
require('../app/src/main/assets/mora_clips.js');
console.log('MORA_RETARGETED_CLIPS length:', window.MORA_RETARGETED_CLIPS ? window.MORA_RETARGETED_CLIPS.length : 0);

const gltfLoader = new GLTFLoader();
const buf = fs.readFileSync('app/src/main/assets/models/mora.glb');

gltfLoader.parse(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength), '', (gltf) => {
  const root = gltf.scene;
  const mixer = new THREE.AnimationMixer(root);
  const clips = window.MORA_RETARGETED_CLIPS.map(d => THREE.AnimationClip.parse(d));

  clips.forEach(clip => {
    console.log(`\n--- Clip: ${clip.name} (duration: ${clip.duration}s, tracks: ${clip.tracks.length}) ---`);
    const action = mixer.clipAction(clip);
    action.play();

    // Check at t = 0, 0.5, 1.0s
    [0, 0.5, 1.0].forEach(t => {
      mixer.setTime(t);
      root.updateMatrixWorld(true);

      const lFoot = root.getObjectByName('J_Bip_L_Foot');
      const rFoot = root.getObjectByName('J_Bip_R_Foot');
      const hips = root.getObjectByName('J_Bip_C_Hips');
      const lFootWorld = new THREE.Vector3();
      const rFootWorld = new THREE.Vector3();
      const hipsWorld = new THREE.Vector3();
      if (lFoot) lFoot.getWorldPosition(lFootWorld);
      if (rFoot) rFoot.getWorldPosition(rFootWorld);
      if (hips) hips.getWorldPosition(hipsWorld);

      console.log(`  t=${t.toFixed(1)}s -> Hips Y: ${hipsWorld.y.toFixed(4)}, L_Foot Y: ${lFootWorld.y.toFixed(4)}, R_Foot Y: ${rFootWorld.y.toFixed(4)}`);
    });

    action.stop();
  });
});
