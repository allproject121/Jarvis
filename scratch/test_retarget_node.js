const fs = require('fs');
const path = require('path');

const THREE = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three'));
const { GLTFLoader } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/loaders/GLTFLoader.js'));

const loader = new GLTFLoader();
const buf = fs.readFileSync('app/src/main/assets/motions/idle_243.glb');
const arrayBuf = buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength);

loader.parse(arrayBuf, '', (gltf) => {
  console.log('idle_243 parsed successfully!');
  const asset = gltf.scene;
  const clip = gltf.animations[0];
  console.log('Clip name:', clip.name, 'duration:', clip.duration, 'tracks count:', clip.tracks.length);
  asset.updateMatrixWorld(true);

  // Check bones
  const hips = asset.getObjectByName('Hips');
  console.log('Hips found:', !!hips);
  if (hips) {
    const hipsWorld = new THREE.Vector3();
    hips.getWorldPosition(hipsWorld);
    console.log('Hips world pos:', hipsWorld);
  }
}, (err) => {
  console.error('Error loading idle_243:', err);
});
