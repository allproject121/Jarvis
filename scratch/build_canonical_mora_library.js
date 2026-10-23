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

const MESHY_VRM_MAP = {
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
  const RELAXED = { 1: 0.28, 2: 0.35, 3: 0.22 };
  const SOFT    = { 1: 0.14, 2: 0.18, 3: 0.10 };
  const OPEN    = { 1: 0.05, 2: 0.06, 3: 0.04 };
  const SHAPES = { relaxed: RELAXED, soft: SOFT, open: OPEN };

  const THUMB_RELAXED = { 1: 0.08, 2: 0.15, 3: 0.15 };
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
        // VRM 0.x flip
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

function retargetClip(sourceAsset, clip, targetName, rigMap, mora, options = {}) {
  const { leftHand = 'relaxed', rightHand = 'relaxed', dampHipsY = false } = options;
  sourceAsset.updateMatrixWorld(true);

  const restInv = new THREE.Quaternion();
  const parentRest = new THREE.Quaternion();
  const q = new THREE.Quaternion();
  const tracks = [];

  // Check hips baseline for root motion
  let hipsBaselineY = 0;
  const hipsTrack = clip.tracks.find(t => (t.name === 'Hips.position' || t.name === 'mixamorigHips.position'));
  if (hipsTrack && hipsTrack.values.length >= 2) {
    hipsBaselineY = hipsTrack.values[1];
  }

  for (const track of clip.tracks) {
    const parts = track.name.split('.');
    const bName = parts[0];
    const prop = parts[1];
    const targetBone = rigMap[bName];
    if (!targetBone) continue;

    const vrmNode = mora.getObjectByName(targetBone);
    const src = sourceAsset.getObjectByName(bName);
    if (!vrmNode || !src) continue;

    src.getWorldQuaternion(restInv).invert();
    if (src.parent) src.parent.getWorldQuaternion(parentRest);
    else parentRest.identity();

    if (track.ValueTypeName === 'quaternion' || prop === 'quaternion' || prop === 'rotation') {
      const values = [];
      const times = [];

      for (let i = 0; i < track.times.length; i++) {
        times.push(Number(track.times[i].toFixed(4)));
        const vIdx = i * 4;
        q.fromArray(track.values, vIdx);
        // Exact mathematical transform: W_parent * q * W_rest^-1
        q.premultiply(parentRest).multiply(restInv);
        // VRM 0.x coordinate inversion (mirror across Y): x = -x, z = -z
        values.push(
          Number((-q.x).toFixed(5)),
          Number((q.y).toFixed(5)),
          Number((-q.z).toFixed(5)),
          Number((q.w).toFixed(5))
        );
      }
      tracks.push(new THREE.QuaternionKeyframeTrack(`${vrmNode.name}.quaternion`, times, values));
    } else if ((prop === 'position' || prop === 'translation') && (bName === 'Hips' || bName === 'mixamorigHips')) {
      // Anchored vertical breathing / motion
      const values = [];
      const times = [];
      const scale = hipsBaselineY > 50 ? 0.01 : (hipsBaselineY > 5 ? 0.1 : 1.0);

      for (let i = 0; i < track.times.length; i++) {
        times.push(Number(track.times[i].toFixed(4)));
        const vIdx = i * 3;
        const rawY = track.values[vIdx + 1];
        let dy = (rawY - hipsBaselineY) * scale;
        if (dampHipsY) {
          dy = Math.max(-0.025, Math.min(0.010, dy * 0.25));
        }
        const finalY = Number((MORA_HIPS_Y + dy).toFixed(5));
        values.push(0, finalY, -0.0043);
      }
      tracks.push(new THREE.VectorKeyframeTrack(`${vrmNode.name}.position`, times, values));
    }
  }

  // If no finger tracks, synthesize natural anime finger curl
  const hasFingers = tracks.some(t => t.name.includes('Thumb') || t.name.includes('Index'));
  if (!hasFingers) {
    tracks.push(...getPosedFingerTracks(clip.duration, leftHand, rightHand));
  }

  return new THREE.AnimationClip(targetName, clip.duration, tracks);
}

async function buildAll() {
  const moraBuf = fs.readFileSync('app/src/main/assets/models/mora.glb');
  const moraGltf = await new Promise(r => gltfLoader.parse(moraBuf.buffer.slice(moraBuf.byteOffset, moraBuf.byteOffset + moraBuf.byteLength), '', r));
  const mora = moraGltf.scene;
  mora.updateMatrixWorld(true);

  const library = [];

  // 1. IDLE (idle_243.glb)
  {
    console.log('Retargeting 1/7: idle (idle_243.glb)...');
    const buf = fs.readFileSync('research_repos/Anima/frontend/public/motions/idle_243.glb');
    const gltf = await new Promise(r => gltfLoader.parse(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength), '', r));
    const clip = retargetClip(gltf.scene, gltf.animations[0], 'idle', MESHY_VRM_MAP, mora, { leftHand: 'relaxed', rightHand: 'relaxed' });
    library.push(clip);
  }

  // 2. JOY (Excited.fbx)
  {
    console.log('Retargeting 2/7: joy (Excited.fbx)...');
    const buf = fs.readFileSync('research_repos/anime-ai/humanoid-animations/Excited.fbx');
    const fbx = fbxLoader.parse(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength), '');
    const clip = retargetClip(fbx, fbx.animations[0], 'joy', MIXAMO_VRM_MAP, mora, { leftHand: 'open', rightHand: 'open' });
    library.push(clip);
  }

  // 3. LEARN / TEACH (Thankful.fbx)
  {
    console.log('Retargeting 3/7: learn (Thankful.fbx)...');
    const buf = fs.readFileSync('research_repos/anime-ai/humanoid-animations/Thankful.fbx');
    const fbx = fbxLoader.parse(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength), '');
    const clip = retargetClip(fbx, fbx.animations[0], 'learn', MIXAMO_VRM_MAP, mora, { leftHand: 'soft', rightHand: 'soft' });
    library.push(clip);
  }

  // 4. DANCE (Miku.fbx)
  {
    console.log('Retargeting 4/8: dance (Miku.fbx)...');
    const buf = fs.readFileSync('research_repos/anime-ai/humanoid-animations/dj/dancing/Miku.fbx');
    const fbx = fbxLoader.parse(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength), '');
    const clip = retargetClip(fbx, fbx.animations[0], 'dance', MIXAMO_VRM_MAP, mora, { leftHand: 'soft', rightHand: 'soft', dampHipsY: true });
    library.push(clip);
  }

  // 5. TALKING (talk_314.glb)
  {
    console.log('Retargeting 5/8: talking (talk_314.glb)...');
    const buf = fs.readFileSync('research_repos/Anima/frontend/public/motions/talk_314.glb');
    const gltf = await new Promise(r => gltfLoader.parse(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength), '', r));
    const clip = retargetClip(gltf.scene, gltf.animations[0], 'talking', MESHY_VRM_MAP, mora, { leftHand: 'relaxed', rightHand: 'soft' });
    library.push(clip);
  }

  // 6. WAVE (wave_290.glb)
  {
    console.log('Retargeting 6/8: wave (wave_290.glb)...');
    const buf = fs.readFileSync('research_repos/Anima/frontend/public/motions/wave_290.glb');
    const gltf = await new Promise(r => gltfLoader.parse(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength), '', r));
    const clip = retargetClip(gltf.scene, gltf.animations[0], 'wave', MESHY_VRM_MAP, mora, { leftHand: 'relaxed', rightHand: 'open' });
    library.push(clip);
  }

  // 7. THINKING (shrug_317.glb)
  {
    console.log('Retargeting 7/8: thinking (shrug_317.glb)...');
    const buf = fs.readFileSync('research_repos/Anima/frontend/public/motions/shrug_317.glb');
    const gltf = await new Promise(r => gltfLoader.parse(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength), '', r));
    const clip = retargetClip(gltf.scene, gltf.animations[0], 'thinking', MESHY_VRM_MAP, mora, { leftHand: 'soft', rightHand: 'soft' });
    library.push(clip);
  }

  // 8. PERVERT / DEFENSIVE TSUNDERE (mora_animations.glb Angry)
  {
    console.log('Retargeting 8/8: pervert (mora_animations.glb Angry)...');
    const animBuf = fs.readFileSync('app/src/main/assets/models/mora_animations.glb');
    const animGltf = await new Promise(r => gltfLoader.parse(animBuf.buffer.slice(animBuf.byteOffset, animBuf.byteOffset + animBuf.byteLength), '', r));
    const angry = animGltf.animations.find(a => a.name === 'Angry');
    const maxTime = 4.2;
    const validTracks = [];
    for (const tr of angry.tracks) {
      const bName = tr.name.split('.')[0];
      if (!mora.getObjectByName(bName)) continue;
      if (tr.name.endsWith('.scale')) continue;
      if (tr.name.endsWith('.position') && !bName.includes('Hips')) continue;

      const times = [], vals = [];
      const stride = tr.name.endsWith('.position') ? 3 : 4;
      for (let i = 0; i < tr.times.length; i++) {
        if (tr.times[i] <= maxTime) {
          times.push(Number(tr.times[i].toFixed(4)));
          for (let s = 0; s < stride; s++) vals.push(Number(tr.values[i * stride + s].toFixed(5)));
        }
      }
      if (times.length > 0) {
        if (tr.name.endsWith('.position')) {
          const anchoredVals = [];
          for (let i = 0; i < times.length; i++) {
            anchoredVals.push(0, MORA_HIPS_Y, -0.0043);
          }
          validTracks.push(new THREE.VectorKeyframeTrack(tr.name, times, anchoredVals));
        } else {
          validTracks.push(new THREE.QuaternionKeyframeTrack(tr.name, times, vals));
        }
      }
    }
    const pervertClip = new THREE.AnimationClip('pervert', maxTime, validTracks);
    library.push(pervertClip);
  }

  console.log('\n--- VERIFYING RETARGETED ANIMATION CLIPS ON MORA ---');
  const mixer = new THREE.AnimationMixer(mora);
  library.forEach(clip => {
    const action = mixer.clipAction(clip);
    action.play();
    mixer.update(0.5);
    mora.updateMatrixWorld(true);

    const lHand = mora.getObjectByName('J_Bip_L_Hand');
    const rHand = mora.getObjectByName('J_Bip_R_Hand');
    const head = mora.getObjectByName('J_Bip_C_Head');
    const lp = new THREE.Vector3(), rp = new THREE.Vector3(), hp = new THREE.Vector3();
    lHand.getWorldPosition(lp);
    rHand.getWorldPosition(rp);
    head.getWorldPosition(hp);

    console.log(`[CLIP ${clip.name.padEnd(9)}] Duration: ${clip.duration.toFixed(2)}s | Tracks: ${clip.tracks.length} | Head Y: ${hp.y.toFixed(3)} | LHand: [${lp.x.toFixed(2)}, ${lp.y.toFixed(2)}, ${lp.z.toFixed(2)}] | RHand: [${rp.x.toFixed(2)}, ${rp.y.toFixed(2)}, ${rp.z.toFixed(2)}]`);
    action.stop();
  });

  // Serialize to JSON and write app/src/main/assets/mora_clips.js
  const serialized = library.map(c => c.toJSON());
  const jsContent = `/**
 * Canonical Retargeted Animations Library for REDMAGIC Mora
 * Rig: VRoid VRM 0.0 Humanoid Skeleton (J_Bip_* hierarchy)
 * Built with mathematically verified W_parent * q * W_rest^-1 orientation transforms.
 */
(function() {
  window.MORA_RETARGETED_CLIPS = ${JSON.stringify(serialized)};
  console.log("Canonical Mora retargeted animation library loaded:", window.MORA_RETARGETED_CLIPS.length, "clips");
})();
`;

  fs.writeFileSync('app/src/main/assets/mora_clips.js', jsContent, 'utf8');
  console.log(`\nSuccessfully wrote app/src/main/assets/mora_clips.js (${(Buffer.byteLength(jsContent, 'utf8') / 1024).toFixed(1)} KB)`);
}

buildAll().catch(console.error);
