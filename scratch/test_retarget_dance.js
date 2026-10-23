global.self = global;
global.window = global;
global.Blob = global.Blob || class {};
global.URL = global.URL || { createObjectURL: () => 'blob:mock', revokeObjectURL: () => {} };

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
  mixamorigRightToeBase: 'J_Bip_R_ToeBase',
};

async function testRetargetDance() {
  const moraBuf = fs.readFileSync('app/src/main/assets/models/mora.glb');
  const moraGltf = await new Promise((resolve, reject) => {
    gltfLoader.parse(moraBuf.buffer.slice(moraBuf.byteOffset, moraBuf.byteOffset + moraBuf.byteLength), '', resolve, reject);
  });
  const mora = moraGltf.scene;
  mora.updateMatrixWorld(true);

  const fbxBuf = fs.readFileSync('research_repos/anime-ai/humanoid-animations/dj/dancing/Miku.fbx');
  const fbx = fbxLoader.parse(fbxBuf.buffer.slice(fbxBuf.byteOffset, fbxBuf.byteOffset + fbxBuf.byteLength), '');
  fbx.updateMatrixWorld(true);
  const clip = fbx.animations[0];

  console.log('Miku.fbx clip duration:', clip.duration, 'tracks count:', clip.tracks.length);

  const restInv = new THREE.Quaternion();
  const parentRest = new THREE.Quaternion();
  const q = new THREE.Quaternion();
  const tracks = [];

  for (const track of clip.tracks) {
    const parts = track.name.split('.');
    const mName = parts[0];
    const targetBone = MIXAMO_VRM_MAP[mName];
    if (!targetBone) continue;
    const vrmNode = mora.getObjectByName(targetBone);
    const src = fbx.getObjectByName(mName);
    if (!vrmNode || !src) continue;

    src.getWorldQuaternion(restInv).invert();
    if (src.parent) src.parent.getWorldQuaternion(parentRest);
    else parentRest.identity();

    if (track.ValueTypeName === 'quaternion' || track.name.endsWith('.quaternion')) {
      const values = track.values.slice();
      for (let i = 0; i < values.length; i += 4) {
        q.fromArray(values, i);
        // Transform: parentRest * q * restInv
        q.premultiply(parentRest).multiply(restInv);
        // VRM 0.x coordinate inversion
        values[i] = -q.x;
        values[i + 1] = q.y;
        values[i + 2] = -q.z;
        values[i + 3] = q.w;
      }
      tracks.push(new THREE.QuaternionKeyframeTrack(vrmNode.name + '.quaternion', track.times, values));
    }
  }

  console.log('Successfully retargeted', tracks.length, 'tracks from Miku.fbx');
  const danceClip = new THREE.AnimationClip('dance', clip.duration, tracks);
  const mixer = new THREE.AnimationMixer(mora);
  const action = mixer.clipAction(danceClip);
  action.play();

  for (const t of [1.0, 3.0, 5.0]) {
    mixer.setTime(t);
    mixer.update(0.001);
    mora.updateMatrixWorld(true);
    console.log(`\n--- DANCE POSE AT t = ${t}s ---`);
    ['J_Bip_C_Hips', 'J_Bip_C_Head', 'J_Bip_L_UpperArm', 'J_Bip_L_Hand', 'J_Bip_R_UpperArm', 'J_Bip_R_Hand', 'J_Bip_L_LowerLeg', 'J_Bip_R_LowerLeg'].forEach(bName => {
      const b = mora.getObjectByName(bName);
      const pos = new THREE.Vector3();
      b.getWorldPosition(pos);
      const rot = new THREE.Euler().setFromQuaternion(b.quaternion);
      console.log(bName.padEnd(18), 'WorldPos:', [Number(pos.x.toFixed(3)), Number(pos.y.toFixed(3)), Number(pos.z.toFixed(3))], 'LocalEulerDeg:', [Number((rot.x*180/Math.PI).toFixed(1)), Number((rot.y*180/Math.PI).toFixed(1)), Number((rot.z*180/Math.PI).toFixed(1))]);
    });
  }
}

testRetargetDance().catch(console.error);
