global.self = global;
global.window = global;
const fs = require('fs');
const path = require('path');
const THREE = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three'));
const { GLTFLoader } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/loaders/GLTFLoader.js'));
const { FBXLoader } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/loaders/FBXLoader.js'));
const SkeletonUtils = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/utils/SkeletonUtils.js'));

const gltfLoader = new GLTFLoader();
const fbxLoader = new FBXLoader();

const moraBuf = fs.readFileSync('app/src/main/assets/models/mora.glb');
const fbxBuf = fs.readFileSync('research_repos/anime-ai/humanoid-animations/Waving.fbx');

gltfLoader.parse(moraBuf.buffer.slice(moraBuf.byteOffset, moraBuf.byteOffset + moraBuf.byteLength), '', (moraGltf) => {
  const moraModel = moraGltf.scene;
  moraModel.updateMatrixWorld(true);

  let moraSkinnedMesh = null;
  moraModel.traverse(n => {
    if (n.isSkinnedMesh && !moraSkinnedMesh) moraSkinnedMesh = n;
  });

  const fbx = fbxLoader.parse(fbxBuf.buffer.slice(fbxBuf.byteOffset, fbxBuf.byteOffset + fbxBuf.byteLength), '');
  fbx.updateMatrixWorld(true);

  const fbxBones = [];
  fbx.traverse(n => {
    if (n.isBone) fbxBones.push(n);
  });
  fbx.skeleton = new THREE.Skeleton(fbxBones);

  console.log('Mora SkinnedMesh:', moraSkinnedMesh.name, 'Bones:', moraSkinnedMesh.skeleton.bones.length);
  console.log('FBX Bones:', fbxBones.length);

  const namesMap = {
    'J_Bip_C_Hips': 'mixamorigHips',
    'J_Bip_C_Spine': 'mixamorigSpine',
    'J_Bip_C_Chest': 'mixamorigSpine1',
    'J_Bip_C_UpperChest': 'mixamorigSpine2',
    'J_Bip_C_Neck': 'mixamorigNeck',
    'J_Bip_C_Head': 'mixamorigHead',
    'J_Bip_L_Shoulder': 'mixamorigLeftShoulder',
    'J_Bip_L_UpperArm': 'mixamorigLeftArm',
    'J_Bip_L_LowerArm': 'mixamorigLeftForeArm',
    'J_Bip_L_Hand': 'mixamorigLeftHand',
    'J_Bip_R_Shoulder': 'mixamorigRightShoulder',
    'J_Bip_R_UpperArm': 'mixamorigRightArm',
    'J_Bip_R_LowerArm': 'mixamorigRightForeArm',
    'J_Bip_R_Hand': 'mixamorigRightHand',
    'J_Bip_L_UpperLeg': 'mixamorigLeftUpLeg',
    'J_Bip_L_LowerLeg': 'mixamorigLeftLeg',
    'J_Bip_L_Foot': 'mixamorigLeftFoot',
    'J_Bip_L_ToeBase': 'mixamorigLeftToeBase',
    'J_Bip_R_UpperLeg': 'mixamorigRightUpLeg',
    'J_Bip_R_LowerLeg': 'mixamorigRightLeg',
    'J_Bip_R_Foot': 'mixamorigRightFoot',
    'J_Bip_R_ToeBase': 'mixamorigRightToeBase'
  };

  const clip = fbx.animations[0];
  console.log('Source Clip:', clip.name, 'Duration:', clip.duration);

  try {
    const retargeted = SkeletonUtils.retargetClip(moraSkinnedMesh, fbx, clip, {
      names: namesMap,
      hip: 'mixamorigHips',
      scale: 0.01
    });
    console.log('Retargeted successfully! Tracks:', retargeted.tracks.length);

    // Apply to Mora and evaluate hand positions!
    const mixer = new THREE.AnimationMixer(moraModel);
    const act = mixer.clipAction(retargeted);
    act.play();

    const rHand = moraModel.getObjectByName('J_Bip_R_Hand');
    const lHand = moraModel.getObjectByName('J_Bip_L_Hand');
    const pR = new THREE.Vector3(), pL = new THREE.Vector3();

    [0, 0.25, 0.5].forEach(t => {
      mixer.setTime(t);
      moraModel.updateMatrixWorld(true);
      rHand.getWorldPosition(pR);
      lHand.getWorldPosition(pL);
      console.log(`t=${t}s | Right Hand Pos: (${pR.x.toFixed(2)}, ${pR.y.toFixed(2)}, ${pR.z.toFixed(2)}) | Left Hand Pos: (${pL.x.toFixed(2)}, ${pL.y.toFixed(2)}, ${pL.z.toFixed(2)})`);
    });
  } catch (err) {
    console.error('Retarget error:', err);
  }
});
