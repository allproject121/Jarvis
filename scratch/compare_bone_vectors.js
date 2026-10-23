global.self = global;
global.window = global;
const fs = require('fs');
const path = require('path');
const THREE = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three'));
const { GLTFLoader } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/loaders/GLTFLoader.js'));
const { FBXLoader } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/loaders/FBXLoader.js'));

const gltfLoader = new GLTFLoader();
const fbxLoader = new FBXLoader();

async function compareBoneVectors() {
  const moraBuf = fs.readFileSync('app/src/main/assets/models/mora.glb');
  const moraGltf = await new Promise(r => gltfLoader.parse(moraBuf.buffer.slice(moraBuf.byteOffset, moraBuf.byteOffset + moraBuf.byteLength), '', r));
  moraGltf.scene.updateMatrixWorld(true);

  const fbxBuf = fs.readFileSync('research_repos/anime-ai/humanoid-animations/dj/dancing/Miku.fbx');
  const fbx = fbxLoader.parse(fbxBuf.buffer.slice(fbxBuf.byteOffset, fbxBuf.byteOffset + fbxBuf.byteLength), '');
  fbx.updateMatrixWorld(true);

  const pairs = [
    ['mixamorigHips', 'J_Bip_C_Hips'],
    ['mixamorigSpine1', 'J_Bip_C_Chest'],
    ['mixamorigHead', 'J_Bip_C_Head'],
    ['mixamorigLeftArm', 'J_Bip_L_UpperArm'],
    ['mixamorigLeftForeArm', 'J_Bip_L_LowerArm'],
    ['mixamorigLeftHand', 'J_Bip_L_Hand'],
    ['mixamorigRightArm', 'J_Bip_R_UpperArm'],
    ['mixamorigRightForeArm', 'J_Bip_R_LowerArm'],
    ['mixamorigRightHand', 'J_Bip_R_Hand'],
    ['mixamorigLeftUpLeg', 'J_Bip_L_UpperLeg'],
    ['mixamorigLeftLeg', 'J_Bip_L_LowerLeg'],
    ['mixamorigLeftFoot', 'J_Bip_L_Foot']
  ];

  console.log('--- REST WORLD ROTATIONS ---');
  pairs.forEach(([mName, vName]) => {
    const mNode = fbx.getObjectByName(mName);
    const vNode = moraGltf.scene.getObjectByName(vName);
    if (!mNode || !vNode) {
      console.log(`Missing ${mName} or ${vName}`);
      return;
    }
    const mq = new THREE.Quaternion();
    mNode.getWorldQuaternion(mq);
    const vq = new THREE.Quaternion();
    vNode.getWorldQuaternion(vq);

    // Direction vector of bone to child
    const mp = new THREE.Vector3();
    mNode.getWorldPosition(mp);
    const vp = new THREE.Vector3();
    vNode.getWorldPosition(vp);

    console.log(`${mName.padEnd(22)} (Mixamo) WorldQuat: [${mq.x.toFixed(3)}, ${mq.y.toFixed(3)}, ${mq.z.toFixed(3)}, ${mq.w.toFixed(3)}]`);
    console.log(`${vName.padEnd(22)} (Mora)   WorldQuat: [${vq.x.toFixed(3)}, ${vq.y.toFixed(3)}, ${vq.z.toFixed(3)}, ${vq.w.toFixed(3)}]`);
  });
}

compareBoneVectors().catch(console.error);
