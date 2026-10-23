global.self = global;
global.window = global;
const fs = require('fs');
const path = require('path');
const THREE = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three'));
const { GLTFLoader } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/loaders/GLTFLoader.js'));
const { FBXLoader } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/loaders/FBXLoader.js'));

const gltfLoader = new GLTFLoader();
const fbxLoader = new FBXLoader();

const MIXAMO_VRM_MAP = {
  mixamorigHips: 'J_Bip_C_Hips',
  mixamorigSpine: 'J_Bip_C_Spine',
  mixamorigSpine1: 'J_Bip_C_Chest',
  mixamorigSpine2: 'J_Bip_C_UpperChest',
  mixamorigNeck: 'J_Bip_C_Neck',
  mixamorigHead: 'J_Bip_C_Head',
  mixamorigLeftShoulder: 'J_Bip_L_Shoulder',
  mixamorigLeftArm: 'J_Bip_L_UpperArm',
  mixamorigLeftForeArm: 'J_Bip_L_LowerArm',
  mixamorigLeftHand: 'J_Bip_L_Hand',
  mixamorigRightShoulder: 'J_Bip_R_Shoulder',
  mixamorigRightArm: 'J_Bip_R_UpperArm',
  mixamorigRightForeArm: 'J_Bip_R_LowerArm',
  mixamorigRightHand: 'J_Bip_R_Hand',
  mixamorigLeftUpLeg: 'J_Bip_L_UpperLeg',
  mixamorigLeftLeg: 'J_Bip_L_LowerLeg',
  mixamorigLeftFoot: 'J_Bip_L_Foot',
  mixamorigLeftToeBase: 'J_Bip_L_ToeBase',
  mixamorigRightUpLeg: 'J_Bip_R_UpperLeg',
  mixamorigRightLeg: 'J_Bip_R_LowerLeg',
  mixamorigRightFoot: 'J_Bip_R_Foot',
  mixamorigRightToeBase: 'J_Bip_R_ToeBase'
};

async function testRetarget() {
  const moraBuf = fs.readFileSync('app/src/main/assets/models/mora.glb');
  const moraGltf = await new Promise(r => gltfLoader.parse(moraBuf.buffer.slice(moraBuf.byteOffset, moraBuf.byteOffset + moraBuf.byteLength), '', r));
  const moraScene = moraGltf.scene;
  moraScene.updateMatrixWorld(true);

  const fbxBuf = fs.readFileSync('research_repos/anime-ai/humanoid-animations/Breathing Idle.fbx');
  const fbx = fbxLoader.parse(fbxBuf.buffer.slice(fbxBuf.byteOffset, fbxBuf.byteOffset + fbxBuf.byteLength), '');
  fbx.updateMatrixWorld(true);

  const clip = fbx.animations.find(a => a.tracks.length > 0);
  console.log('Source Clip:', clip.name, 'Duration:', clip.duration);

  // For each bone in map, check retargeted rotation at t=0
  const restInv = new THREE.Quaternion();
  const parentRest = new THREE.Quaternion();
  const quat = new THREE.Quaternion();

  const testBones = [
    'mixamorigLeftArm', 'mixamorigLeftForeArm', 'mixamorigLeftHand',
    'mixamorigRightArm', 'mixamorigRightForeArm', 'mixamorigRightHand',
    'mixamorigHead', 'mixamorigNeck',
    'mixamorigLeftUpLeg', 'mixamorigLeftLeg'
  ];

  testBones.forEach(mName => {
    const vName = MIXAMO_VRM_MAP[mName];
    const mNode = fbx.getObjectByName(mName);
    const vNode = moraScene.getObjectByName(vName);
    if (!mNode || !vNode) return;

    mNode.getWorldQuaternion(restInv).invert();
    if (mNode.parent) mNode.parent.getWorldQuaternion(parentRest);
    else parentRest.identity();

    // Find track
    const track = clip.tracks.find(t => t.name.startsWith(mName) && t.name.includes('quaternion') || t.name.includes('rotation'));
    if (!track) {
      console.log(`No track for ${mName}`);
      return;
    }

    quat.fromArray(track.values, 0);
    quat.premultiply(parentRest).multiply(restInv);

    // VRM 0.x flip
    const vrmQuat = new THREE.Quaternion(-quat.x, quat.y, -quat.z, quat.w);

    // Apply to Mora bone
    vNode.quaternion.copy(vrmQuat);

    // Convert to Euler angles (in degrees) to inspect the physiological angle
    const euler = new THREE.Euler().setFromQuaternion(vrmQuat, 'XYZ');
    const degX = (euler.x * 180 / Math.PI).toFixed(1);
    const degY = (euler.y * 180 / Math.PI).toFixed(1);
    const degZ = (euler.z * 180 / Math.PI).toFixed(1);

    console.log(`${vName.padEnd(20)} | VRM Quat: [${vrmQuat.x.toFixed(3)}, ${vrmQuat.y.toFixed(3)}, ${vrmQuat.z.toFixed(3)}, ${vrmQuat.w.toFixed(3)}] | Euler: [${degX}°, ${degY}°, ${degZ}°]`);
  });

  moraScene.updateMatrixWorld(true);

  // Check world positions of joints to verify hands and feet are in natural positions
  console.log('\n--- VERIFYING WORLD POSITIONS OF MORA LIMBS ---');
  ['J_Bip_L_Hand', 'J_Bip_R_Hand', 'J_Bip_L_Foot', 'J_Bip_R_Foot', 'J_Bip_C_Head'].forEach(bName => {
    const b = moraScene.getObjectByName(bName);
    const p = new THREE.Vector3();
    b.getWorldPosition(p);
    console.log(`${bName.padEnd(20)} WorldPos: [X: ${p.x.toFixed(3)}m, Y: ${p.y.toFixed(3)}m, Z: ${p.z.toFixed(3)}m]`);
  });
}

testRetarget().catch(console.error);
