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
  // Fingers
  leftThumb1: 'J_Bip_L_Thumb1',
  leftThumb2: 'J_Bip_L_Thumb2',
  leftThumb3: 'J_Bip_L_Thumb3',
  leftIndex1: 'J_Bip_L_Index1',
  leftIndex2: 'J_Bip_L_Index2',
  leftIndex3: 'J_Bip_L_Index3',
  leftMiddle1: 'J_Bip_L_Middle1',
  leftMiddle2: 'J_Bip_L_Middle2',
  leftMiddle3: 'J_Bip_L_Middle3',
  leftRing1: 'J_Bip_L_Ring1',
  leftRing2: 'J_Bip_L_Ring2',
  leftRing3: 'J_Bip_L_Ring3',
  leftLittle1: 'J_Bip_L_Little1',
  leftLittle2: 'J_Bip_L_Little2',
  leftLittle3: 'J_Bip_L_Little3',
  rightThumb1: 'J_Bip_R_Thumb1',
  rightThumb2: 'J_Bip_R_Thumb2',
  rightThumb3: 'J_Bip_R_Thumb3',
  rightIndex1: 'J_Bip_R_Index1',
  rightIndex2: 'J_Bip_R_Index2',
  rightIndex3: 'J_Bip_R_Index3',
  rightMiddle1: 'J_Bip_R_Middle1',
  rightMiddle2: 'J_Bip_R_Middle2',
  rightMiddle3: 'J_Bip_R_Middle3',
  rightRing1: 'J_Bip_R_Ring1',
  rightRing2: 'J_Bip_R_Ring2',
  rightRing3: 'J_Bip_R_Ring3',
  rightLittle1: 'J_Bip_R_Little1',
  rightLittle2: 'J_Bip_R_Little2',
  rightLittle3: 'J_Bip_R_Little3'
};

const BONE_RIG_MAP = {
  Hips: 'hips', Spine02: 'spine', Spine01: 'chest', Spine: 'upperChest', neck: 'neck', Head: 'head',
  LeftShoulder: 'leftShoulder', LeftArm: 'leftUpperArm', LeftForeArm: 'leftLowerArm', LeftHand: 'leftHand',
  RightShoulder: 'rightShoulder', RightArm: 'rightUpperArm', RightForeArm: 'rightLowerArm', RightHand: 'rightHand',
  LeftUpLeg: 'leftUpperLeg', LeftLeg: 'leftLowerLeg', LeftFoot: 'leftFoot', LeftToeBase: 'leftToes',
  RightUpLeg: 'rightUpperLeg', RightLeg: 'rightLowerLeg', RightFoot: 'rightFoot', RightToeBase: 'rightToes',
  mixamorigHips: 'hips', mixamorigSpine: 'spine', mixamorigSpine1: 'chest', mixamorigSpine2: 'upperChest',
  mixamorigNeck: 'neck', mixamorigHead: 'head', mixamorigLeftShoulder: 'leftShoulder',
  mixamorigLeftArm: 'leftUpperArm', mixamorigLeftForeArm: 'leftLowerArm', mixamorigLeftHand: 'leftHand',
  mixamorigRightShoulder: 'rightShoulder', mixamorigRightArm: 'rightUpperArm', mixamorigRightForeArm: 'rightLowerArm',
  mixamorigRightHand: 'rightHand', mixamorigLeftUpLeg: 'leftUpperLeg', mixamorigLeftLeg: 'leftLowerLeg',
  mixamorigLeftFoot: 'leftFoot', mixamorigLeftToeBase: 'leftToes', mixamorigRightUpLeg: 'rightUpperLeg',
  mixamorigRightLeg: 'rightLowerLeg', mixamorigRightFoot: 'rightFoot', mixamorigRightToeBase: 'rightToes'
};

// Finger curls
const RELAXED = { 1: 0.35, 2: 0.45, 3: 0.30 };
const SOFT    = { 1: 0.15, 2: 0.20, 3: 0.12 };
const OPEN    = { 1: 0.05, 2: 0.06, 3: 0.04 };
const HAND_SHAPES = { relaxed: RELAXED, soft: SOFT, open: OPEN };
const THUMB_SHAPES = {
  relaxed: { 1: 0.10, 2: 0.20, 3: 0.20 },
  soft:    { 1: 0.06, 2: 0.12, 3: 0.12 },
  open:    { 1: 0.02, 2: 0.05, 3: 0.05 }
};

function getFingerTracks(duration, leftShapeName = 'relaxed', rightShapeName = 'relaxed') {
  const tracks = [];
  const sides = [
    { name: 'left', shape: HAND_SHAPES[leftShapeName] || RELAXED, thumb: THUMB_SHAPES[leftShapeName] || THUMB_SHAPES.relaxed },
    { name: 'right', shape: HAND_SHAPES[rightShapeName] || RELAXED, thumb: THUMB_SHAPES[rightShapeName] || THUMB_SHAPES.relaxed }
  ];

  for (const s of sides) {
    const sign = s.name === 'left' ? -1 : 1;
    // 4 fingers
    for (const finger of ['Index', 'Middle', 'Ring', 'Little']) {
      for (const joint of [1, 2, 3]) {
        const boneKey = `${s.name}${finger}${joint}`;
        const moraBone = VRM_TO_MORA[boneKey];
        if (moraBone) {
          const angle = s.shape[joint] * sign;
          const euler = new THREE.Euler(0, 0, angle);
          const q = new THREE.Quaternion().setFromEuler(euler);
          // VRM 0.x flip
          q.x = -q.x; q.z = -q.z;
          tracks.push(new THREE.QuaternionKeyframeTrack(
            `${moraBone}.quaternion`,
            [0, duration],
            [Number(q.x.toFixed(5)), Number(q.y.toFixed(5)), Number(q.z.toFixed(5)), Number(q.w.toFixed(5)),
             Number(q.x.toFixed(5)), Number(q.y.toFixed(5)), Number(q.z.toFixed(5)), Number(q.w.toFixed(5))]
          ));
        }
      }
    }
    // Thumb
    for (const joint of [1, 2, 3]) {
      const boneKey = `${s.name}Thumb${joint}`;
      const moraBone = VRM_TO_MORA[boneKey];
      if (moraBone) {
        const angle = s.thumb[joint];
        const euler = new THREE.Euler(0, -sign * angle, sign * angle * 0.5);
        const q = new THREE.Quaternion().setFromEuler(euler);
        q.x = -q.x; q.z = -q.z;
        tracks.push(new THREE.QuaternionKeyframeTrack(
          `${moraBone}.quaternion`,
          [0, duration],
          [Number(q.x.toFixed(5)), Number(q.y.toFixed(5)), Number(q.z.toFixed(5)), Number(q.w.toFixed(5)),
           Number(q.x.toFixed(5)), Number(q.y.toFixed(5)), Number(q.z.toFixed(5)), Number(q.w.toFixed(5))]
        ));
      }
    }
  }

  return tracks;
}

const MORA_HIPS_Y = 0.8794;

function retargetClip(asset, clip, targetName, leftHand = 'relaxed', rightHand = 'relaxed') {
  asset.updateMatrixWorld(true);

  const hipsNode = asset.getObjectByName('mixamorigHips') || asset.getObjectByName('Hips');
  let hipsHeight = 1.0;
  if (hipsNode) {
    const p = new THREE.Vector3();
    hipsNode.getWorldPosition(p);
    // If authored in centimeters (> 5.0), convert to meters
    hipsHeight = p.y > 5.0 ? p.y * 0.01 : (p.y > 0 ? p.y : 1.0);
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
      const values = [];
      for (let i = 0; i < track.values.length; i += 4) {
        quat.fromArray(track.values, i);
        quat.premultiply(parentRest).multiply(restInv);
        // VRM 0.x coordinate inversion
        values.push(
          Number((-quat.x).toFixed(5)),
          Number((quat.y).toFixed(5)),
          Number((-quat.z).toFixed(5)),
          Number((quat.w).toFixed(5))
        );
      }
      const times = Array.from(track.times).map(t => Number(t.toFixed(4)));
      tracks.push(new THREE.QuaternionKeyframeTrack(`${targetBone}.quaternion`, times, values));
    } else if ((track.ValueTypeName === 'vector' || track.name.endsWith('.position') || prop === 'translation') && vrmBone === 'hips') {
      const values = [];
      for (let i = 0; i < track.values.length; i += 3) {
        let rawY = track.values[i + 1];
        if (rawY > 5.0) rawY *= 0.01; // convert centimeters to meters
        const yVal = Number((rawY * hipsScale).toFixed(5));
        values.push(0, yVal, -0.0043);
      }
      const times = Array.from(track.times).map(t => Number(t.toFixed(4)));
      tracks.push(new THREE.VectorKeyframeTrack(`${targetBone}.position`, times, values));
    }
  });

  // Add posed fingers
  const fingerTracks = getFingerTracks(clip.duration, leftHand, rightHand);
  tracks.push(...fingerTracks);

  return new THREE.AnimationClip(targetName, clip.duration, tracks);
}

const gltfLoader = new GLTFLoader();
const fbxLoader = new FBXLoader();

async function run() {
  const clips = [];

  // 1. Idle (idle_243.glb)
  const idleBuf = fs.readFileSync('app/src/main/assets/motions/idle_243.glb');
  const idleGltf = await new Promise(r => gltfLoader.parse(idleBuf.buffer.slice(idleBuf.byteOffset, idleBuf.byteOffset + idleBuf.byteLength), '', r));
  clips.push(retargetClip(idleGltf.scene, idleGltf.animations[0], 'idle', 'relaxed', 'relaxed'));

  // 2. Wave (wave_290.glb)
  const waveBuf = fs.readFileSync('app/src/main/assets/motions/wave_290.glb');
  const waveGltf = await new Promise(r => gltfLoader.parse(waveBuf.buffer.slice(waveBuf.byteOffset, waveBuf.byteOffset + waveBuf.byteLength), '', r));
  clips.push(retargetClip(waveGltf.scene, waveGltf.animations[0], 'wave', 'relaxed', 'open'));

  // 3. Talk (talk_314.glb)
  const talkBuf = fs.readFileSync('app/src/main/assets/motions/talk_314.glb');
  const talkGltf = await new Promise(r => gltfLoader.parse(talkBuf.buffer.slice(talkBuf.byteOffset, talkBuf.byteOffset + talkBuf.byteLength), '', r));
  clips.push(retargetClip(talkGltf.scene, talkGltf.animations[0], 'talking_0', 'soft', 'soft'));

  // 4. Shrug / Teach (shrug_317.glb)
  const shrugBuf = fs.readFileSync('app/src/main/assets/motions/shrug_317.glb');
  const shrugGltf = await new Promise(r => gltfLoader.parse(shrugBuf.buffer.slice(shrugBuf.byteOffset, shrugBuf.byteOffset + shrugBuf.byteLength), '', r));
  clips.push(retargetClip(shrugGltf.scene, shrugGltf.animations[0], 'talking_1', 'open', 'open'));

  // 5. Joy (Laughing.fbx)
  const joyBuf = fs.readFileSync('research_repos/anime-ai/humanoid-animations/Laughing.fbx');
  const joyFbx = fbxLoader.parse(joyBuf.buffer.slice(joyBuf.byteOffset, joyBuf.byteOffset + joyBuf.byteLength), '');
  clips.push(retargetClip(joyFbx, joyFbx.animations[0], 'joy', 'relaxed', 'relaxed'));

  // 6. Dance (HipHopDancing.fbx)
  const danceBuf = fs.readFileSync('research_repos/anime-ai/humanoid-animations/HipHopDancing.fbx');
  const danceFbx = fbxLoader.parse(danceBuf.buffer.slice(danceBuf.byteOffset, danceBuf.byteOffset + danceBuf.byteLength), '');
  clips.push(retargetClip(danceFbx, danceFbx.animations[0], 'dance', 'soft', 'soft'));

  // 7. Thinking (Thinking.fbx)
  const thinkBuf = fs.readFileSync('research_repos/anime-ai/humanoid-animations/Thinking.fbx');
  const thinkFbx = fbxLoader.parse(thinkBuf.buffer.slice(thinkBuf.byteOffset, thinkBuf.byteOffset + thinkBuf.byteLength), '');
  clips.push(retargetClip(thinkFbx, thinkFbx.animations[0], 'thinking', 'relaxed', 'soft'));

  // 8. Crying (Crying.fbx)
  const cryBuf = fs.readFileSync('research_repos/anime-ai/humanoid-animations/Crying.fbx');
  const cryFbx = fbxLoader.parse(cryBuf.buffer.slice(cryBuf.byteOffset, cryBuf.byteOffset + cryBuf.byteLength), '');
  clips.push(retargetClip(cryFbx, cryFbx.animations[0], 'crying', 'relaxed', 'relaxed'));

  console.log('Successfully retargeted all', clips.length, 'clips with posed fingers:');
  clips.forEach(c => {
    console.log(`- ${c.name}: duration ${c.duration.toFixed(2)}s, tracks ${c.tracks.length}`);
  });

  const jsonClips = clips.map(c => THREE.AnimationClip.toJSON(c));
  const jsonStr = JSON.stringify(jsonClips);
  console.log('JSON length:', (jsonStr.length / 1024).toFixed(1), 'KB');

  const jsContent = `// Auto-generated retargeted animations for Mora (VRM 0.0) with posed fingers
window.MORA_RETARGETED_CLIPS = ${jsonStr};
`;
  fs.writeFileSync('app/src/main/assets/mora_clips.js', jsContent);
  console.log('Saved app/src/main/assets/mora_clips.js successfully!');
}

run().catch(console.error);
