global.self = global;
global.window = global;
const fs = require('fs');
const path = require('path');
const THREE = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three'));
const { GLTFLoader } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/loaders/GLTFLoader.js'));

const gltfLoader = new GLTFLoader();
const buf = fs.readFileSync('app/src/main/assets/models/mora.glb');

gltfLoader.parse(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength), '', (gltf) => {
  const root = gltf.scene;
  const box = new THREE.Box3().setFromObject(root);
  console.log('BoundingBox min:', box.min.x.toFixed(4), box.min.y.toFixed(4), box.min.z.toFixed(4));
  console.log('BoundingBox max:', box.max.x.toFixed(4), box.max.y.toFixed(4), box.max.z.toFixed(4));
  console.log('Total height:', (box.max.y - box.min.y).toFixed(4));

  // Foot bones positions
  const lFoot = root.getObjectByName('J_Bip_L_Foot');
  const rFoot = root.getObjectByName('J_Bip_R_Foot');
  const lToe = root.getObjectByName('J_Bip_L_ToeBase');
  const rToe = root.getObjectByName('J_Bip_R_ToeBase');
  const hips = root.getObjectByName('J_Bip_C_Hips');

  const lFootWorld = new THREE.Vector3();
  const rFootWorld = new THREE.Vector3();
  const hipsWorld = new THREE.Vector3();
  if (lFoot) lFoot.getWorldPosition(lFootWorld);
  if (rFoot) rFoot.getWorldPosition(rFootWorld);
  if (hips) hips.getWorldPosition(hipsWorld);

  console.log('Hips Y:', hipsWorld.y.toFixed(4));
  console.log('L_Foot Y:', lFootWorld.y.toFixed(4), 'R_Foot Y:', rFootWorld.y.toFixed(4));
  if (lToe) {
    const lToeWorld = new THREE.Vector3();
    lToe.getWorldPosition(lToeWorld);
    console.log('L_Toe Y:', lToeWorld.y.toFixed(4));
  }
});
