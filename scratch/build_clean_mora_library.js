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
  mixamorigRightToeBase: 'J_Bip_R_ToeBase',
  // Fingers
  mixamorigLeftHandThumb1: 'J_Bip_L_Thumb1',
  mixamorigLeftHandThumb2: 'J_Bip_L_Thumb2',
  mixamorigLeftHandThumb3: 'J_Bip_L_Thumb3',
  mixamorigLeftHandIndex1: 'J_Bip_L_Index1',
  mixamorigLeftHandIndex2: 'J_Bip_L_Index2',
  mixamorigLeftHandIndex3: 'J_Bip_L_Index3',
  mixamorigLeftHandMiddle1: 'J_Bip_L_Middle1',
  mixamorigLeftHandMiddle2: 'J_Bip_L_Middle2',
  mixamorigLeftHandMiddle3: 'J_Bip_L_Middle3',
  mixamorigLeftHandRing1: 'J_Bip_L_Ring1',
  mixamorigLeftHandRing2: 'J_Bip_L_Ring2',
  mixamorigLeftHandRing3: 'J_Bip_L_Ring3',
  mixamorigLeftHandPinky1: 'J_Bip_L_Little1',
  mixamorigLeftHandPinky2: 'J_Bip_L_Little2',
  mixamorigLeftHandPinky3: 'J_Bip_L_Little3',
  mixamorigRightHandThumb1: 'J_Bip_R_Thumb1',
  mixamorigRightHandThumb2: 'J_Bip_R_Thumb2',
  mixamorigRightHandThumb3: 'J_Bip_R_Thumb3',
  mixamorigRightHandIndex1: 'J_Bip_R_Index1',
  mixamorigRightHandIndex2: 'J_Bip_R_Index2',
  mixamorigRightHandIndex3: 'J_Bip_R_Index3',
  mixamorigRightHandMiddle1: 'J_Bip_R_Middle1',
  mixamorigRightHandMiddle2: 'J_Bip_R_Middle2',
  mixamorigRightHandMiddle3: 'J_Bip_R_Middle3',
  mixamorigRightHandRing1: 'J_Bip_R_Ring1',
  mixamorigRightHandRing2: 'J_Bip_R_Ring2',
  mixamorigRightHandRing3: 'J_Bip_R_Ring3',
  mixamorigRightHandPinky1: 'J_Bip_R_Little1',
  mixamorigRightHandPinky2: 'J_Bip_R_Little2',
  mixamorigRightHandPinky3: 'J_Bip_R_Little3'
};

const MORA_HIPS_Y = 0.8794;

// Synthesize soft, natural anime finger curl tracks if not present in clip
function getPosedFingerTracks(duration, leftShape = 'relaxed', rightShape = 'relaxed') {
  const tracks = [];
  const RELAXED = { 1: 0.30, 2: 0.38, 3: 0.25 };
  const SOFT    = { 1: 0.15, 2: 0.20, 3: 0.12 };
  const OPEN    = { 1: 0.05, 2: 0.06, 3: 0.04 };
  const SHAPES = { relaxed: RELAXED, soft: SOFT, open: OPEN };

  const THUMB_RELAXED = { 1: 0.08, 2: 0.16, 3: 0.16 };
  const THUMB_SOFT    = { 1: 0.05, 2: 0.10, 3: 0.10 };
  const THUMB_OPEN    = { 1: 0.02, 2: 0.04, 3: 0.04 };
  const THUMBS = { relaxed: THUMB_RELAXED, soft: THUMB_SOFT, open: THUMB_OPEN };

  const sides = [
    { name: 'L', shape: SHAPES[leftShape] || RELAXED, thumb: THUMBS[leftShape] || THUMB_RELAXED, sign: -1 },
    { name: 'R', shape: SHAPES[rightShape] || RELAXED, thumb: THUMBS[rightShape] || THUMB_RELAXED, sign: 1 }
  ];

  for (const s of sides) {
    for (const finger of ['Index', 'Middle', 'Ring', 'Little']) {
      for (const j of [1, 2, 3]) {
        const bName = `J_Bip_${s.name}_${finger}${j}`;
        const angle = s.shape[j] * s.sign;
        const euler = new THREE.Euler(0, 0, angle);
        const q = new THREE.Quaternion().setFromEuler(euler);
        q.x = -q.x; q.z = -q.z;
        tracks.push(new THREE.QuaternionKeyframeTrack(
          `${bName}.quaternion`,
          [0, duration],
          [Number(q.x.toFixed(5)), Number(q.y.toFixed(5)), Number(q.z.toFixed(5)), Number(q.w.toFixed(5)),
           Number(q.x.toFixed(5)), Number(q.y.toFixed(5)), Number(q.z.toFixed(5)), Number(q.w.toFixed(5))]
        ));
      }
    }
    for (const j of [1, 2, 3]) {
      const bName = `J_Bip_${s.name}_Thumb${j}`;
      const angle = s.thumb[j];
      const euler = new THREE.Euler(0, -s.sign * angle, s.sign * angle * 0.4);
      const q = new THREE.Quaternion().setFromEuler(euler);
      q.x = -q.x; q.z = -q.z;
      tracks.push(new THREE.QuaternionKeyframeTrack(
        `${bName}.quaternion`,
        [0, duration],
        [Number(q.x.toFixed(5)), Number(q.y.toFixed(5)), Number(q.z.toFixed(5)), Number(q.w.toFixed(5)),
         Number(q.x.toFixed(5)), Number(q.y.toFixed(5)), Number(q.z.toFixed(5)), Number(q.w.toFixed(5))]
      ));
    }
  }
  return tracks;
}

function retargetFbxClip(fbxAsset, clip, targetName, options = {}) {
  const { leftHand = 'relaxed', rightHand = 'relaxed', loopCount = 1 } = options;
  fbxAsset.updateMatrixWorld(true);

  const hipsNode = fbxAsset.getObjectByName('mixamorigHips');
  let hipsHeight = 100.0;
  if (hipsNode) {
    const p = new THREE.Vector3();
    hipsNode.getWorldPosition(p);
    hipsHeight = p.y > 0 ? p.y : 100.0;
  }
  // Convert cm to meters scale
  const hipsScale = (MORA_HIPS_Y / hipsHeight);

  const tracks = [];
  const restInv = new THREE.Quaternion();
  const parentRest = new THREE.Quaternion();
  const quat = new THREE.Quaternion();

  const originalDuration = clip.duration;
  const finalDuration = originalDuration * loopCount;

  clip.tracks.forEach((track) => {
    const parts = track.name.split('.');
    const mName = parts[0];
    const prop = parts[1];
    const targetBone = MIXAMO_VRM_MAP[mName];
    if (!targetBone) return;

    const mNode = fbxAsset.getObjectByName(mName);
    if (!mNode || !mNode.parent) return;

    mNode.getWorldQuaternion(restInv).invert();
    mNode.parent.getWorldQuaternion(parentRest);

    if (track.ValueTypeName === 'quaternion' || track.name.includes('quaternion') || prop === 'rotation') {
      const values = [];
      const times = [];

      for (let l = 0; l < loopCount; l++) {
        const timeOffset = l * originalDuration;
        for (let i = 0; i < track.times.length; i++) {
          if (l > 0 && i === 0) continue; // avoid duplicate time stamp
          times.push(Number((track.times[i] + timeOffset).toFixed(4)));

          const vIdx = i * 4;
          quat.fromArray(track.values, vIdx);
          quat.premultiply(parentRest).multiply(restInv);

          // VRM 0.x coordinate inversion
          values.push(
            Number((-quat.x).toFixed(5)),
            Number((quat.y).toFixed(5)),
            Number((-quat.z).toFixed(5)),
            Number((quat.w).toFixed(5))
          );
        }
      }

      tracks.push(new THREE.QuaternionKeyframeTrack(`${targetBone}.quaternion`, times, values));
    } else if ((track.ValueTypeName === 'vector' || track.name.includes('position') || prop === 'translation') && mName === 'mixamorigHips') {
      // Root motion: only keep Hips position, scaled to Mora's true height in meters
      const values = [];
      const times = [];

      for (let l = 0; l < loopCount; l++) {
        const timeOffset = l * originalDuration;
        for (let i = 0; i < track.times.length; i++) {
          if (l > 0 && i === 0) continue;
          times.push(Number((track.times[i] + timeOffset).toFixed(4)));

          const vIdx = i * 3;
          let rawY = track.values[vIdx + 1];
          // Scale hips height smoothly around Mora's base 0.8794m
          const yVal = Number((rawY * hipsScale).toFixed(5));
          values.push(0, yVal, -0.0043);
        }
      }

      tracks.push(new THREE.VectorKeyframeTrack(`${targetBone}.position`, times, values));
    }
  });

  // Check if finger tracks were present in source clip
  const hasFingerTracks = tracks.some(t => t.name.includes('Thumb') || t.name.includes('Index'));
  if (!hasFingerTracks) {
    const fingerTracks = getPosedFingerTracks(finalDuration, leftHand, rightHand);
    tracks.push(...fingerTracks);
  }

  return new THREE.AnimationClip(targetName, finalDuration, tracks);
}

// Generate conversational speaking clip: combines natural breathing idle with rhythmic conversational hand gesture
function createSpeakingClip(idleClip, thankfulClip) {
  // We blend thankful arm gestures with idle breathing
  const tracks = [];
  const duration = 6.0;

  idleClip.tracks.forEach(track => {
    const isArm = track.name.includes('UpperArm') || track.name.includes('LowerArm') || track.name.includes('Hand');
    if (!isArm) {
      // Retain spine, head, neck, hips, legs from idle
      tracks.push(track);
    }
  });

  // For arms, create gentle conversational hand gestures (both hands gesturing forward in front of chest)
  const lUpperArmQ = new THREE.Quaternion().setFromEuler(new THREE.Euler(-0.04, -0.35, 1.15));
  const rUpperArmQ = new THREE.Quaternion().setFromEuler(new THREE.Euler(0.04, 0.35, -1.15));
  const lLowerArmQ1 = new THREE.Quaternion().setFromEuler(new THREE.Euler(0, -0.65, 0));
  const lLowerArmQ2 = new THREE.Quaternion().setFromEuler(new THREE.Euler(0, -0.45, 0));
  const rLowerArmQ1 = new THREE.Quaternion().setFromEuler(new THREE.Euler(0, 0.65, 0));
  const rLowerArmQ2 = new THREE.Quaternion().setFromEuler(new THREE.Euler(0, 0.45, 0));

  // Times: 0s, 1.5s, 3.0s, 4.5s, 6.0s
  const times = [0, 1.5, 3.0, 4.5, 6.0];

  function buildTrack(bName, q1, q2) {
    const vals = [];
    times.forEach((t, idx) => {
      const q = (idx % 2 === 0) ? q1 : q2;
      vals.push(q.x, q.y, q.z, q.w);
    });
    return new THREE.QuaternionKeyframeTrack(`${bName}.quaternion`, times, vals);
  }

  tracks.push(buildTrack('J_Bip_L_UpperArm', lUpperArmQ, lUpperArmQ));
  tracks.push(buildTrack('J_Bip_R_UpperArm', rUpperArmQ, rUpperArmQ));
  tracks.push(buildTrack('J_Bip_L_LowerArm', lLowerArmQ1, lLowerArmQ2));
  tracks.push(buildTrack('J_Bip_R_LowerArm', rLowerArmQ1, rLowerArmQ2));

  // Hands softly open
  const fingerTracks = getPosedFingerTracks(duration, 'soft', 'soft');
  tracks.push(...fingerTracks);

  return new THREE.AnimationClip('talking', duration, tracks);
}

async function buildAllClips() {
  console.log('Building clean Mora animation library from canonical FBX assets...');
  const clips = [];

  // 1. Idle (Breathing Idle.fbx)
  const idleBuf = fs.readFileSync('research_repos/anime-ai/humanoid-animations/Breathing Idle.fbx');
  const idleFbx = fbxLoader.parse(idleBuf.buffer.slice(idleBuf.byteOffset, idleBuf.byteOffset + idleBuf.byteLength), '');
  const idleSrc = idleFbx.animations.find(a => a.tracks.length > 0);
  const idleClip = retargetFbxClip(idleFbx, idleSrc, 'idle', { leftHand: 'relaxed', rightHand: 'relaxed' });
  clips.push(idleClip);

  // 2. Wave (Waving.fbx - loop 6 times for 3.18s smooth wave)
  const waveBuf = fs.readFileSync('research_repos/anime-ai/humanoid-animations/Waving.fbx');
  const waveFbx = fbxLoader.parse(waveBuf.buffer.slice(waveBuf.byteOffset, waveBuf.byteOffset + waveBuf.byteLength), '');
  const waveSrc = waveFbx.animations.find(a => a.tracks.length > 0);
  const waveClip = retargetFbxClip(waveFbx, waveSrc, 'wave', { leftHand: 'relaxed', rightHand: 'open', loopCount: 6 });
  clips.push(waveClip);

  // 3. Joy (Excited.fbx)
  const joyBuf = fs.readFileSync('research_repos/anime-ai/humanoid-animations/Excited.fbx');
  const joyFbx = fbxLoader.parse(joyBuf.buffer.slice(joyBuf.byteOffset, joyBuf.byteOffset + joyBuf.byteLength), '');
  const joySrc = joyFbx.animations.find(a => a.tracks.length > 0);
  const joyClip = retargetFbxClip(joyFbx, joySrc, 'joy', { leftHand: 'soft', rightHand: 'soft' });
  clips.push(joyClip);

  // 4. Learn / Teach (Thankful.fbx - graceful polite bow & gesture)
  const thankBuf = fs.readFileSync('research_repos/anime-ai/humanoid-animations/Thankful.fbx');
  const thankFbx = fbxLoader.parse(thankBuf.buffer.slice(thankBuf.byteOffset, thankBuf.byteOffset + thankBuf.byteLength), '');
  const thankSrc = thankFbx.animations.find(a => a.tracks.length > 0);
  const learnClip = retargetFbxClip(thankFbx, thankSrc, 'learn', { leftHand: 'soft', rightHand: 'soft' });
  clips.push(learnClip);

  // 5. Dance (Miku.fbx - authentic idol anime dance)
  const danceBuf = fs.readFileSync('research_repos/anime-ai/humanoid-animations/dj/dancing/Miku.fbx');
  const danceFbx = fbxLoader.parse(danceBuf.buffer.slice(danceBuf.byteOffset, danceBuf.byteOffset + danceBuf.byteLength), '');
  const danceSrc = danceFbx.animations.find(a => a.tracks.length > 0);
  const danceClip = retargetFbxClip(danceFbx, danceSrc, 'dance', { leftHand: 'soft', rightHand: 'soft' });
  clips.push(danceClip);

  // 6. Thinking (Thinking.fbx)
  const thinkBuf = fs.readFileSync('research_repos/anime-ai/humanoid-animations/Thinking.fbx');
  const thinkFbx = fbxLoader.parse(thinkBuf.buffer.slice(thinkBuf.byteOffset, thinkBuf.byteOffset + thinkBuf.byteLength), '');
  const thinkSrc = thinkFbx.animations.find(a => a.tracks.length > 0);
  const thinkClip = retargetFbxClip(thinkFbx, thinkSrc, 'thinking', { leftHand: 'relaxed', rightHand: 'soft' });
  clips.push(thinkClip);

  // 7. Conversational Speaking (Gentle conversational gesturing)
  const talkClip = createSpeakingClip(idleClip, learnClip);
  clips.push(talkClip);

  console.log(`\nSuccessfully created ${clips.length} coherent clips:`);
  clips.forEach(c => {
    console.log(`- ${c.name.padEnd(12)} | Duration: ${c.duration.toFixed(2)}s | Tracks: ${c.tracks.length}`);
  });

  const jsonClips = clips.map(c => THREE.AnimationClip.toJSON(c));
  const jsonStr = JSON.stringify(jsonClips);

  const jsFile = `// Auto-generated clean canonical animation library for Mora (VRM 0.0)
window.MORA_RETARGETED_CLIPS = ${jsonStr};
`;
  fs.writeFileSync('app/src/main/assets/mora_clips.js', jsFile);
  console.log(`Saved app/src/main/assets/mora_clips.js (${(jsFile.length / 1024).toFixed(1)} KB)`);
}

buildAllClips().catch(console.error);
