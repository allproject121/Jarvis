const fs = require('fs');
const path = require('path');

const THREE = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three'));
const { GLTFLoader } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/loaders/GLTFLoader.js'));
const { FBXLoader } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/loaders/FBXLoader.js'));

// VRM to Mora bone map
const VRM_TO_MORA = {
  hips: 'J_Bip_C_Hips',
  spine: 'J_Bip_C_Spine',
  chest: 'J_Bip_C_Chest',
  upperChest: 'J_Bip_C_UpperChest',
  neck: 'J_Bip_C_Neck',
  head: 'J_Bip_C_Head',
  leftShoulder: 'J_Bip_L_Shoulder',
  leftUpperArm: 'J_Bip_L_UpperArm',
  leftLowerArm: 'J_Bip_L_LowerArm',
  leftHand: 'J_Bip_L_Hand',
  rightShoulder: 'J_Bip_R_Shoulder',
  rightUpperArm: 'J_Bip_R_UpperArm',
  rightLowerArm: 'J_Bip_R_LowerArm',
  rightHand: 'J_Bip_R_Hand',
  leftUpperLeg: 'J_Bip_L_UpperLeg',
  leftLowerLeg: 'J_Bip_L_LowerLeg',
  leftFoot: 'J_Bip_L_Foot',
  leftToes: 'J_Bip_L_ToeBase',
  rightUpperLeg: 'J_Bip_R_UpperLeg',
  rightLowerLeg: 'J_Bip_R_LowerLeg',
  rightFoot: 'J_Bip_R_Foot',
  rightToes: 'J_Bip_R_ToeBase',
  // Fingers
  leftThumbProximal: 'J_Bip_L_Thumb1',
  leftThumbIntermediate: 'J_Bip_L_Thumb2',
  leftThumbDistal: 'J_Bip_L_Thumb3',
  leftIndexProximal: 'J_Bip_L_Index1',
  leftIndexIntermediate: 'J_Bip_L_Index2',
  leftIndexDistal: 'J_Bip_L_Index3',
  leftMiddleProximal: 'J_Bip_L_Middle1',
  leftMiddleIntermediate: 'J_Bip_L_Middle2',
  leftMiddleDistal: 'J_Bip_L_Middle3',
  leftRingProximal: 'J_Bip_L_Ring1',
  leftRingIntermediate: 'J_Bip_L_Ring2',
  leftRingDistal: 'J_Bip_L_Ring3',
  leftLittleProximal: 'J_Bip_L_Little1',
  leftLittleIntermediate: 'J_Bip_L_Little2',
  leftLittleDistal: 'J_Bip_L_Little3',
  rightThumbProximal: 'J_Bip_R_Thumb1',
  rightThumbIntermediate: 'J_Bip_R_Thumb2',
  rightThumbDistal: 'J_Bip_R_Thumb3',
  rightIndexProximal: 'J_Bip_R_Index1',
  rightIndexIntermediate: 'J_Bip_R_Index2',
  rightIndexDistal: 'J_Bip_R_Index3',
  rightMiddleProximal: 'J_Bip_R_Middle1',
  rightMiddleIntermediate: 'J_Bip_R_Middle2',
  rightMiddleDistal: 'J_Bip_R_Middle3',
  rightRingProximal: 'J_Bip_R_Ring1',
  rightRingIntermediate: 'J_Bip_R_Ring2',
  rightRingDistal: 'J_Bip_R_Ring3',
  rightLittleProximal: 'J_Bip_R_Little1',
  rightLittleIntermediate: 'J_Bip_R_Little2',
  rightLittleDistal: 'J_Bip_R_Little3'
};

const MIXAMO_MAP = {
  mixamorigHips: 'hips',
  mixamorigSpine: 'spine',
  mixamorigSpine1: 'chest',
  mixamorigSpine2: 'upperChest',
  mixamorigNeck: 'neck',
  mixamorigHead: 'head',
  mixamorigLeftShoulder: 'leftShoulder',
  mixamorigLeftArm: 'leftUpperArm',
  mixamorigLeftForeArm: 'leftLowerArm',
  mixamorigLeftHand: 'leftHand',
  mixamorigLeftHandThumb1: 'leftThumbProximal',
  mixamorigLeftHandThumb2: 'leftThumbIntermediate',
  mixamorigLeftHandThumb3: 'leftThumbDistal',
  mixamorigLeftHandIndex1: 'leftIndexProximal',
  mixamorigLeftHandIndex2: 'leftIndexIntermediate',
  mixamorigLeftHandIndex3: 'leftIndexDistal',
  mixamorigLeftHandMiddle1: 'leftMiddleProximal',
  mixamorigLeftHandMiddle2: 'leftMiddleIntermediate',
  mixamorigLeftHandMiddle3: 'leftMiddleDistal',
  mixamorigLeftHandRing1: 'leftRingProximal',
  mixamorigLeftHandRing2: 'leftRingIntermediate',
  mixamorigLeftHandRing3: 'leftRingDistal',
  mixamorigLeftHandPinky1: 'leftLittleProximal',
  mixamorigLeftHandPinky2: 'leftLittleIntermediate',
  mixamorigLeftHandPinky3: 'leftLittleDistal',
  mixamorigRightShoulder: 'rightShoulder',
  mixamorigRightArm: 'rightUpperArm',
  mixamorigRightForeArm: 'rightLowerArm',
  mixamorigRightHand: 'rightHand',
  mixamorigRightHandPinky1: 'rightLittleProximal',
  mixamorigRightHandPinky2: 'rightLittleIntermediate',
  mixamorigRightHandPinky3: 'rightLittleDistal',
  mixamorigRightHandRing1: 'rightRingProximal',
  mixamorigRightHandRing2: 'rightRingIntermediate',
  mixamorigRightHandRing3: 'rightRingDistal',
  mixamorigRightHandMiddle1: 'rightMiddleProximal',
  mixamorigRightHandMiddle2: 'rightMiddleIntermediate',
  mixamorigRightHandMiddle3: 'rightMiddleDistal',
  mixamorigRightHandIndex1: 'rightIndexProximal',
  mixamorigRightHandIndex2: 'rightIndexIntermediate',
  mixamorigRightHandIndex3: 'rightIndexDistal',
  mixamorigRightHandThumb1: 'rightThumbProximal',
  mixamorigRightHandThumb2: 'rightThumbIntermediate',
  mixamorigRightHandThumb3: 'rightThumbDistal',
  mixamorigLeftUpLeg: 'leftUpperLeg',
  mixamorigLeftLeg: 'leftLowerLeg',
  mixamorigLeftFoot: 'leftFoot',
  mixamorigLeftToeBase: 'leftToes',
  mixamorigRightUpLeg: 'rightUpperLeg',
  mixamorigRightLeg: 'rightLowerLeg',
  mixamorigRightFoot: 'rightFoot',
  mixamorigRightToeBase: 'rightToes',
  // Short names (without mixamorig prefix)
  Hips: 'hips',
  Spine: 'spine',
  Spine1: 'chest',
  Spine2: 'upperChest',
  Neck: 'neck',
  Head: 'head',
  LeftShoulder: 'leftShoulder',
  LeftArm: 'leftUpperArm',
  LeftForeArm: 'leftLowerArm',
  LeftHand: 'leftHand',
  RightShoulder: 'rightShoulder',
  RightArm: 'rightUpperArm',
  RightForeArm: 'rightLowerArm',
  RightHand: 'rightHand',
  LeftUpLeg: 'leftUpperLeg',
  LeftLeg: 'leftLowerLeg',
  LeftFoot: 'leftFoot',
  LeftToeBase: 'leftToes',
  RightUpLeg: 'rightUpperLeg',
  RightLeg: 'rightLowerLeg',
  RightFoot: 'rightFoot',
  RightToeBase: 'rightToes'
};

const MORA_HIPS_Y = 0.8794;

function retargetMixamoClip(asset, clip, targetClipName) {
  asset.updateMatrixWorld(true);

  // Find hips
  const hipsNode = asset.getObjectByName('mixamorigHips') || asset.getObjectByName('Hips');
  let hipsHeight = 1.0;
  if (hipsNode) {
    const p = new THREE.Vector3();
    hipsNode.getWorldPosition(p);
    hipsHeight = p.y > 0 ? p.y : 1.0;
  }
  const hipsScale = MORA_HIPS_Y / hipsHeight;

  const tracks = [];
  const restInv = new THREE.Quaternion();
  const parentRest = new THREE.Quaternion();
  const quat = new THREE.Quaternion();

  clip.tracks.forEach((track) => {
    const parts = track.name.split('.');
    const boneRaw = parts[0];
    const prop = parts[1];
    const vrmBone = MIXAMO_MAP[boneRaw];
    if (!vrmBone) return;
    const targetBone = VRM_TO_MORA[vrmBone];
    if (!targetBone) return;

    const srcNode = asset.getObjectByName(boneRaw);
    if (!srcNode || !srcNode.parent) return;

    srcNode.getWorldQuaternion(restInv).invert();
    srcNode.parent.getWorldQuaternion(parentRest);

    if (track.ValueTypeName === 'quaternion' || track.name.endsWith('.quaternion')) {
      const values = new Float32Array(track.values.length);
      for (let i = 0; i < track.values.length; i += 4) {
        quat.fromArray(track.values, i);
        // Correct Pixiv VRM transformation
        quat.premultiply(parentRest).multiply(restInv);
        // VRM 0.x coordinate inversion (mirror X and Z)
        values[i]     = -quat.x;
        values[i + 1] =  quat.y;
        values[i + 2] = -quat.z;
        values[i + 3] =  quat.w;
      }
      tracks.push(new THREE.QuaternionKeyframeTrack(`${targetBone}.quaternion`, track.times, values));
    } else if ((track.ValueTypeName === 'vector' || track.name.endsWith('.position')) && vrmBone === 'hips' && prop === 'position') {
      // Hips translation: keep only Y (scaled), zero out X and Z to keep model anchored
      const values = new Float32Array(track.values.length);
      for (let i = 0; i < track.values.length; i += 3) {
        values[i]     = 0;
        values[i + 1] = track.values[i + 1] * hipsScale;
        values[i + 2] = 0;
      }
      tracks.push(new THREE.VectorKeyframeTrack(`${targetBone}.position`, track.times, values));
    }
  });

  return new THREE.AnimationClip(targetClipName, clip.duration, tracks);
}

// Test retargeting on Laughing.fbx
const fbxBuf = fs.readFileSync('research_repos/anime-ai/humanoid-animations/Laughing.fbx');
const fbx = new FBXLoader().parse(fbxBuf.buffer.slice(fbxBuf.byteOffset, fbxBuf.byteOffset + fbxBuf.byteLength), '');
const convertedJoy = retargetMixamoClip(fbx, fbx.animations[0], 'Joy');

console.log('Retargeted Joy clip:', convertedJoy.name);
console.log('Duration:', convertedJoy.duration);
console.log('Tracks count:', convertedJoy.tracks.length);
console.log('Sample tracks:', convertedJoy.tracks.slice(0, 10).map(t => t.name));

// Verify track values on arms and legs
const leftArmTrack = convertedJoy.tracks.find(t => t.name.includes('J_Bip_L_UpperArm'));
console.log('J_Bip_L_UpperArm track samples (first 8 values):', leftArmTrack ? Array.from(leftArmTrack.values.slice(0, 8)).map(v => Number(v.toFixed(4))) : 'not found');

const leftLegTrack = convertedJoy.tracks.find(t => t.name.includes('J_Bip_L_UpperLeg'));
console.log('J_Bip_L_UpperLeg track samples (first 8 values):', leftLegTrack ? Array.from(leftLegTrack.values.slice(0, 8)).map(v => Number(v.toFixed(4))) : 'not found');
