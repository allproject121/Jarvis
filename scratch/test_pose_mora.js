global.self = global;
global.window = global;
global.Blob = global.Blob || class {};
global.URL = global.URL || { createObjectURL: () => 'blob:mock', revokeObjectURL: () => {} };

const fs = require('fs');
const path = require('path');
const THREE = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three'));
const { GLTFLoader } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/loaders/GLTFLoader.js'));

const gltfLoader = new GLTFLoader();

async function testMotion() {
  const moraBuf = fs.readFileSync('app/src/main/assets/models/mora.glb');
  const moraGltf = await new Promise((resolve, reject) => {
    gltfLoader.parse(moraBuf.buffer.slice(moraBuf.byteOffset, moraBuf.byteOffset + moraBuf.byteLength), '', resolve, reject);
  });
  const mora = moraGltf.scene;
  mora.updateMatrixWorld(true);

  const motionBuf = fs.readFileSync('research_repos/Anima/frontend/public/motions/idle_243.glb');
  const motionGltf = await new Promise((resolve, reject) => {
    gltfLoader.parse(motionBuf.buffer.slice(motionBuf.byteOffset, motionBuf.byteOffset + motionBuf.byteLength), '', resolve, reject);
  });
  const motion = motionGltf.scene;
  motion.updateMatrixWorld(true);
  const clip = motionGltf.animations[0];

  const meshyVRMRigMap = {
    Hips: 'J_Bip_C_Hips',
    Spine02: 'J_Bip_C_Spine',
    Spine01: 'J_Bip_C_Chest',
    Spine: 'J_Bip_C_UpperChest',
    neck: 'J_Bip_C_Neck',
    Head: 'J_Bip_C_Head',
    LeftShoulder: 'J_Bip_L_Shoulder',
    LeftArm: 'J_Bip_L_UpperArm',
    LeftForeArm: 'J_Bip_L_LowerArm',
    LeftHand: 'J_Bip_L_Hand',
    RightShoulder: 'J_Bip_R_Shoulder',
    RightArm: 'J_Bip_R_UpperArm',
    RightForeArm: 'J_Bip_R_LowerArm',
    RightHand: 'J_Bip_R_Hand',
    LeftUpLeg: 'J_Bip_L_UpperLeg',
    LeftLeg: 'J_Bip_L_LowerLeg',
    LeftFoot: 'J_Bip_L_Foot',
    LeftToeBase: 'J_Bip_L_ToeBase',
    RightUpLeg: 'J_Bip_R_UpperLeg',
    RightLeg: 'J_Bip_R_LowerLeg',
    RightFoot: 'J_Bip_R_Foot',
    RightToeBase: 'J_Bip_R_ToeBase',
  };

  const restInv = new THREE.Quaternion();
  const parentRest = new THREE.Quaternion();
  const q = new THREE.Quaternion();
  const tracks = [];

  for (const track of clip.tracks) {
    const [boneName, prop] = track.name.split('.');
    const vrmBone = meshyVRMRigMap[boneName];
    if (!vrmBone) continue;
    const vrmNode = mora.getObjectByName(vrmBone);
    const src = motion.getObjectByName(boneName);
    if (!vrmNode || !src) continue;
    src.getWorldQuaternion(restInv).invert();
    if (src.parent) src.parent.getWorldQuaternion(parentRest);
    else parentRest.identity();

    if (track.ValueTypeName === 'quaternion' || track.name.endsWith('.quaternion')) {
      const values = track.values.slice();
      for (let i = 0; i < values.length; i += 4) {
        q.fromArray(values, i);
        q.premultiply(parentRest).multiply(restInv);
        // VRM 0.x coordinate inversion: x = -x, z = -z
        values[i] = -q.x;
        values[i + 1] = q.y;
        values[i + 2] = -q.z;
        values[i + 3] = q.w;
      }
      tracks.push(new THREE.QuaternionKeyframeTrack(vrmNode.name + '.quaternion', track.times, values));
    }
  }

  console.log('Total retargeted tracks generated:', tracks.length);
  tracks.slice(0, 5).forEach(t => {
    console.log('Track:', t.name, 'first quat:', t.values.slice(0, 4));
  });

  const retargetedClip = new THREE.AnimationClip('idle_243_retargeted', clip.duration, tracks);
  const mixer = new THREE.AnimationMixer(mora);
  const action = mixer.clipAction(retargetedClip);
  action.play();

  // Test with mixer.update(1.0)
  mixer.update(1.0);
  mora.updateMatrixWorld(true);
  console.log('\n--- POSE AFTER mixer.update(1.0) ---');
  ['J_Bip_C_Hips', 'J_Bip_C_Head', 'J_Bip_L_UpperArm', 'J_Bip_L_LowerArm', 'J_Bip_L_Hand', 'J_Bip_R_UpperArm', 'J_Bip_R_LowerArm', 'J_Bip_R_Hand', 'J_Bip_L_LowerLeg', 'J_Bip_R_LowerLeg'].forEach(bName => {
    const b = mora.getObjectByName(bName);
    const pos = new THREE.Vector3();
    b.getWorldPosition(pos);
    const rot = new THREE.Euler().setFromQuaternion(b.quaternion);
    console.log(bName.padEnd(18), 'WorldPos:', [Number(pos.x.toFixed(3)), Number(pos.y.toFixed(3)), Number(pos.z.toFixed(3))], 'LocalEulerDeg:', [Number((rot.x*180/Math.PI).toFixed(1)), Number((rot.y*180/Math.PI).toFixed(1)), Number((rot.z*180/Math.PI).toFixed(1))]);
  });
}

testMotion().catch(console.error);
