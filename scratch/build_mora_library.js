const fs = require('fs');
const path = require('path');

const THREE = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three'));
const { GLTFLoader } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/loaders/GLTFLoader.js'));
const { FBXLoader } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/loaders/FBXLoader.js'));

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

const BONE_RIG_MAP = {
  // Meshy/Anima names
  Hips: 'hips',
  Spine02: 'spine',
  Spine01: 'chest',
  Spine: 'upperChest',
  neck: 'neck',
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
  RightToeBase: 'rightToes',
  // Mixamo names
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
  mixamorigRightShoulder: 'rightShoulder',
  mixamorigRightArm: 'rightUpperArm',
  mixamorigRightForeArm: 'rightLowerArm',
  mixamorigRightHand: 'rightHand',
  mixamorigLeftUpLeg: 'leftUpperLeg',
  mixamorigLeftLeg: 'leftLowerLeg',
  mixamorigLeftFoot: 'leftFoot',
  mixamorigLeftToeBase: 'leftToes',
  mixamorigRightUpLeg: 'rightUpperLeg',
  mixamorigRightLeg: 'rightLowerLeg',
  mixamorigRightFoot: 'rightFoot',
  mixamorigRightToeBase: 'rightToes'
};

// Finger posing constants
const RELAXED = { Proximal: 0.35, Intermediate: 0.45, Distal: 0.3 };
const SOFT = { Proximal: 0.15, Intermediate: 0.2, Distal: 0.12 };
const OPEN = { Proximal: 0.06, Intermediate: 0.08, Distal: 0.05 };
const allFingers = (curl) => ({ Index: curl, Middle: curl, Ring: curl, Little: curl });
const HAND_SHAPES = { relaxed: allFingers(RELAXED), soft: allFingers(SOFT), open: allFingers(OPEN) };
const THUMB_CURL = { Metacarpal: 0.1, Proximal: 0.2, Distal: 0.2 };

function generatePosedFingers(duration, handShapeName = 'relaxed') {
  const tracks = [];
  const shape = HAND_SHAPES[handShapeName] || HAND_SHAPES.relaxed;

  for (const side of ['left', 'right']) {
    const sign = side === 'left' ? -1 : 1;
    for (const finger of ['Index', 'Middle', 'Ring', 'Little']) {
      for (const [joint, angle] of Object.entries(shape[finger])) {
        const vrmBone = `${side}${finger}${joint === 'Proximal' ? '1' : joint === 'Intermediate' ? '2' : '3'}`;
        // mapped bone
        const boneKey = `${side}${finger}${joint}`;
        const moraBone = VRM_TO_MORA[boneKey];
        if (moraBone) {
          const euler = new THREE.Euler(0, 0, sign * angle);
          const q = new THREE.Quaternion().setFromEuler(euler);
          // VRM 0.x flip
          q.x = -q.x; q.z = -q.z;
          const vals = [q.x, q.y, q.z, q.w, q.x, q.y, q.z, q.w];
          tracks.push(new THREE.QuaternionKeyframeTrack(`${moraBone}.quaternion`, [0, duration], vals));
        }
      }
    }
  }
  return tracks;
}

const MORA_HIPS_Y = 0.8794;

function retargetClip(asset, clip, targetName, handShape = 'relaxed') {
  asset.updateMatrixWorld(true);

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
    const vrmBone = BONE_RIG_MAP[boneRaw];
    if (!vrmBone) return;
    const targetBone = VRM_TO_MORA[vrmBone];
    if (!targetBone) return;

    const srcNode = asset.getObjectByName(boneRaw);
    if (!srcNode || !srcNode.parent) return;

    srcNode.getWorldQuaternion(restInv).invert();
    srcNode.parent.getWorldQuaternion(parentRest);

    if (track.ValueTypeName === 'quaternion' || track.name.endsWith('.quaternion') || prop === 'rotation') {
      const values = new Float32Array(track.values.length);
      for (let i = 0; i < track.values.length; i += 4) {
        quat.fromArray(track.values, i);
        quat.premultiply(parentRest).multiply(restInv);
        // VRM 0.x coordinate inversion
        values[i]     = -quat.x;
        values[i + 1] =  quat.y;
        values[i + 2] = -quat.z;
        values[i + 3] =  quat.w;
      }
      tracks.push(new THREE.QuaternionKeyframeTrack(`${targetBone}.quaternion`, track.times, values));
    } else if ((track.ValueTypeName === 'vector' || track.name.endsWith('.position') || prop === 'translation') && vrmBone === 'hips') {
      const values = new Float32Array(track.values.length);
      for (let i = 0; i < track.values.length; i += 3) {
        values[i]     = 0;
        values[i + 1] = track.values[i + 1] * hipsScale;
        values[i + 2] = 0;
      }
      tracks.push(new THREE.VectorKeyframeTrack(`${targetBone}.position`, track.times, values));
    }
  });

  return new THREE.AnimationClip(targetName, clip.duration, tracks);
}

// Convert all clips
const gltfLoader = new GLTFLoader();
const fbxLoader = new FBXLoader();

async function convertAll() {
  const resultClips = [];

  // 1. Idle (idle_243.glb)
  const idleBuf = fs.readFileSync('app/src/main/assets/motions/idle_243.glb');
  const idleGltf = await new Promise(r => gltfLoader.parse(idleBuf.buffer.slice(idleBuf.byteOffset, idleBuf.byteOffset + idleBuf.byteLength), '', r));
  resultClips.push(retargetClip(idleGltf.scene, idleGltf.animations[0], 'Idle', 'relaxed'));

  // 2. Talk (talk_314.glb)
  const talkBuf = fs.readFileSync('app/src/main/assets/motions/talk_314.glb');
  const talkGltf = await new Promise(r => gltfLoader.parse(talkBuf.buffer.slice(talkBuf.byteOffset, talkBuf.byteOffset + talkBuf.byteLength), '', r));
  resultClips.push(retargetClip(talkGltf.scene, talkGltf.animations[0], 'Talking_0', 'soft'));

  // 3. Wave (wave_290.glb)
  const waveBuf = fs.readFileSync('app/src/main/assets/motions/wave_290.glb');
  const waveGltf = await new Promise(r => gltfLoader.parse(waveBuf.buffer.slice(waveBuf.byteOffset, waveBuf.byteOffset + waveBuf.byteLength), '', r));
  resultClips.push(retargetClip(waveGltf.scene, waveGltf.animations[0], 'Wave', 'open'));

  // 4. Shrug (shrug_317.glb)
  const shrugBuf = fs.readFileSync('app/src/main/assets/motions/shrug_317.glb');
  const shrugGltf = await new Promise(r => gltfLoader.parse(shrugBuf.buffer.slice(shrugBuf.byteOffset, shrugBuf.byteOffset + shrugBuf.byteLength), '', r));
  resultClips.push(retargetClip(shrugGltf.scene, shrugGltf.animations[0], 'Talking_1', 'soft'));

  // 5. Joy (Laughing.fbx)
  const joyBuf = fs.readFileSync('research_repos/anime-ai/humanoid-animations/Laughing.fbx');
  const joyFbx = fbxLoader.parse(joyBuf.buffer.slice(joyBuf.byteOffset, joyBuf.byteOffset + joyBuf.byteLength), '');
  resultClips.push(retargetClip(joyFbx, joyFbx.animations[0], 'Joy', 'relaxed'));

  // 6. Dance (HipHopDancing.fbx)
  const danceBuf = fs.readFileSync('research_repos/anime-ai/humanoid-animations/HipHopDancing.fbx');
  const danceFbx = fbxLoader.parse(danceBuf.buffer.slice(danceBuf.byteOffset, danceBuf.byteOffset + danceBuf.byteLength), '');
  resultClips.push(retargetClip(danceFbx, danceFbx.animations[0], 'Dance', 'relaxed'));

  // 7. Thinking (Thinking.fbx)
  const thinkBuf = fs.readFileSync('research_repos/anime-ai/humanoid-animations/Thinking.fbx');
  const thinkFbx = fbxLoader.parse(thinkBuf.buffer.slice(thinkBuf.byteOffset, thinkBuf.byteOffset + thinkBuf.byteLength), '');
  resultClips.push(retargetClip(thinkFbx, thinkFbx.animations[0], 'Thinking', 'relaxed'));

  // 8. Crying (Crying.fbx)
  const cryBuf = fs.readFileSync('research_repos/anime-ai/humanoid-animations/Crying.fbx');
  const cryFbx = fbxLoader.parse(cryBuf.buffer.slice(cryBuf.byteOffset, cryBuf.byteOffset + cryBuf.byteLength), '');
  resultClips.push(retargetClip(cryFbx, cryFbx.animations[0], 'Crying', 'relaxed'));

  console.log('Successfully retargeted all', resultClips.length, 'clips!');
  resultClips.forEach(c => {
    console.log(`- ${c.name} (${c.duration.toFixed(2)}s, ${c.tracks.length} tracks)`);
  });

  return resultClips;
}

convertAll().catch(console.error);
