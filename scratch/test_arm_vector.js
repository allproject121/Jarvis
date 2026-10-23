global.self = global;
global.window = global;
const fs = require('fs');
const path = require('path');
const THREE = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three'));
const { FBXLoader } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/loaders/FBXLoader.js'));
const { GLTFLoader } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/loaders/GLTFLoader.js'));

const fbxLoader = new FBXLoader();
const gltfLoader = new GLTFLoader();

const fbxBuf = fs.readFileSync('research_repos/anime-ai/humanoid-animations/Waving.fbx');
const fbx = fbxLoader.parse(fbxBuf.buffer.slice(fbxBuf.byteOffset, fbxBuf.byteOffset + fbxBuf.byteLength), '');
fbx.updateMatrixWorld(true);

const moraBuf = fs.readFileSync('app/src/main/assets/models/mora.glb');
gltfLoader.parse(moraBuf.buffer.slice(moraBuf.byteOffset, moraBuf.byteOffset + moraBuf.byteLength), '', (gltf) => {
  const mora = gltf.scene;
  mora.updateMatrixWorld(true);

  // In Waving clip at t = 1.0s:
  const clip = fbx.animations[0];
  const mixer = new THREE.AnimationMixer(fbx);
  const action = mixer.clipAction(clip);
  action.play();
  mixer.setTime(1.0);
  fbx.updateMatrixWorld(true);

  const mRightArm = fbx.getObjectByName('mixamorigRightArm');
  const mRightHand = fbx.getObjectByName('mixamorigRightHand');
  const mPosArm = new THREE.Vector3();
  const mPosHand = new THREE.Vector3();
  mRightArm.getWorldPosition(mPosArm);
  mRightHand.getWorldPosition(mPosHand);

  console.log('=== MIXAMO WAVING (t=1.0s) ===');
  console.log('Mixamo Right Arm World Pos:', mPosArm);
  console.log('Mixamo Right Hand World Pos:', mPosHand);
  const mDir = mPosHand.clone().sub(mPosArm).normalize();
  console.log('Mixamo Arm Vector (Hand - Arm):', mDir);
  console.log('Mixamo Facing: Mixamo faces +Z. Right arm is on -X side. Arm vector points UP (+Y) and SLIGHTLY FORWARD (+Z):');
  console.log('  Up (+Y):', mDir.y.toFixed(2), '| Forward (+Z):', mDir.z.toFixed(2), '| Side (X):', mDir.x.toFixed(2));

  // Now evaluate Mora's arm vector when using our retargeted clip:
  eval(fs.readFileSync('app/src/main/assets/mora_clips.js', 'utf8'));
  const moraClips = global.MORA_RETARGETED_CLIPS;
  const waveClip = THREE.AnimationClip.parse(moraClips.find(c => c.name === 'wave'));

  const moraMixer = new THREE.AnimationMixer(mora);
  const moraAction = moraMixer.clipAction(waveClip);
  moraAction.play();
  moraMixer.setTime(1.0);
  mora.updateMatrixWorld(true);

  const moraRightArm = mora.getObjectByName('J_Bip_R_UpperArm');
  const moraRightHand = mora.getObjectByName('J_Bip_R_Hand');
  const moraPosArm = new THREE.Vector3();
  const moraPosHand = new THREE.Vector3();
  moraRightArm.getWorldPosition(moraPosArm);
  moraRightHand.getWorldPosition(moraPosHand);

  console.log('\n=== MORA RETARGETED WAVING (t=1.0s) ===');
  console.log('Mora Right Arm World Pos:', moraPosArm);
  console.log('Mora Right Hand World Pos:', moraPosHand);
  const moraDir = moraPosHand.clone().sub(moraPosArm).normalize();
  console.log('Mora Arm Vector (Hand - Arm):', moraDir);
  console.log('Mora Facing: Mora faces -Z (before characterPivot Math.PI).');
  console.log('  Up (+Y):', moraDir.y.toFixed(2), '| Forward (-Z):', (-moraDir.z).toFixed(2), '| Side (X):', moraDir.x.toFixed(2));
});
