global.self = global;
global.window = global;
const fs = require('fs');
const path = require('path');
const THREE = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three'));
const { GLTFLoader } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/loaders/GLTFLoader.js'));

const gltfLoader = new GLTFLoader();

async function checkTalk314() {
  const buf = fs.readFileSync('app/src/main/assets/motions/talk_314.glb');
  const gltf = await new Promise(r => gltfLoader.parse(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength), '', r));
  gltf.scene.updateMatrixWorld(true);

  console.log('--- talk_314.glb Bones & Rest WorldQuat ---');
  ['LeftArm', 'LeftForeArm', 'LeftHand', 'RightArm', 'RightForeArm', 'RightHand', 'Head', 'neck'].forEach(name => {
    const node = gltf.scene.getObjectByName(name);
    if (!node) {
      console.log('Missing node:', name);
      return;
    }
    const q = new THREE.Quaternion();
    node.getWorldQuaternion(q);
    console.log(`${name.padEnd(15)} WorldQuat: [${q.x.toFixed(3)}, ${q.y.toFixed(3)}, ${q.z.toFixed(3)}, ${q.w.toFixed(3)}]`);
  });
}

checkTalk314().catch(console.error);
