global.self = global;
global.window = global;
const fs = require('fs');
const path = require('path');
const THREE = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three'));
const { GLTFLoader } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/loaders/GLTFLoader.js'));
const { FBXLoader } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/loaders/FBXLoader.js'));

const gltfLoader = new GLTFLoader();
const fbxLoader = new FBXLoader();

async function inspectMixamo() {
  const idleBuf = fs.readFileSync('app/src/main/assets/motions/idle_243.glb');
  const idleGltf = await new Promise(r => gltfLoader.parse(idleBuf.buffer.slice(idleBuf.byteOffset, idleBuf.byteOffset + idleBuf.byteLength), '', r));
  console.log('--- Mixamo idle_243.glb Rest Bones ---');
  idleGltf.scene.traverse(node => {
    if (['mixamorigHips', 'mixamorigHead', 'mixamorigLeftArm', 'mixamorigLeftForeArm', 'mixamorigLeftHand', 'mixamorigRightArm', 'mixamorigRightForeArm', 'mixamorigRightHand'].includes(node.name)) {
      const q = node.quaternion;
      const p = node.position;
      console.log(`${node.name.padEnd(25)} | pos: [${p.x.toFixed(2)}, ${p.y.toFixed(2)}, ${p.z.toFixed(2)}] | quat: [${q.x.toFixed(3)}, ${q.y.toFixed(3)}, ${q.z.toFixed(3)}, ${q.w.toFixed(3)}]`);
    }
  });

  const danceBuf = fs.readFileSync('research_repos/anime-ai/humanoid-animations/HipHopDancing.fbx');
  const danceFbx = fbxLoader.parse(danceBuf.buffer.slice(danceBuf.byteOffset, danceBuf.byteOffset + danceBuf.byteLength), '');
  console.log('\n--- Mixamo HipHopDancing.fbx Rest Bones ---');
  danceFbx.traverse(node => {
    if (['mixamorigHips', 'mixamorigHead', 'mixamorigLeftArm', 'mixamorigLeftForeArm', 'mixamorigLeftHand', 'mixamorigRightArm', 'mixamorigRightForeArm', 'mixamorigRightHand'].includes(node.name)) {
      const q = node.quaternion;
      const p = node.position;
      console.log(`${node.name.padEnd(25)} | pos: [${p.x.toFixed(2)}, ${p.y.toFixed(2)}, ${p.z.toFixed(2)}] | quat: [${q.x.toFixed(3)}, ${q.y.toFixed(3)}, ${q.z.toFixed(3)}, ${q.w.toFixed(3)}]`);
    }
  });
}

inspectMixamo().catch(console.error);
