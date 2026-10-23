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

async function testAll() {
  const moraBuf = fs.readFileSync('app/src/main/assets/models/mora.glb');
  const moraGltf = await new Promise(r => gltfLoader.parse(moraBuf.buffer.slice(moraBuf.byteOffset, moraBuf.byteOffset + moraBuf.byteLength), '', r));
  const moraScene = moraGltf.scene;

  const fbxList = [
    { name: 'idle', file: 'research_repos/anime-ai/humanoid-animations/Breathing Idle.fbx' },
    { name: 'wave', file: 'research_repos/anime-ai/humanoid-animations/Waving.fbx' },
    { name: 'joy', file: 'research_repos/anime-ai/humanoid-animations/Laughing.fbx' },
    { name: 'dance', file: 'research_repos/anime-ai/humanoid-animations/dj/dancing/Miku.fbx' },
    { name: 'learn', file: 'research_repos/anime-ai/humanoid-animations/Thankful.fbx' },
    { name: 'think', file: 'research_repos/anime-ai/humanoid-animations/Thinking.fbx' },
    { name: 'talk', file: 'research_repos/anime-ai/humanoid-animations/dj/interaction/Cheering.fbx' }
  ];

  for (const item of fbxList) {
    const fbxBuf = fs.readFileSync(item.file);
    const fbx = fbxLoader.parse(fbxBuf.buffer.slice(fbxBuf.byteOffset, fbxBuf.byteOffset + fbxBuf.byteLength), '');
    fbx.updateMatrixWorld(true);
    const clip = fbx.animations.find(a => a.tracks.length > 0);

    const restInv = new THREE.Quaternion();
    const parentRest = new THREE.Quaternion();
    const quat = new THREE.Quaternion();

    // Reset mora scene
    moraScene.traverse(n => { if (n.isBone) n.quaternion.set(0, 0, 0, 1); });

    // Apply mid-frame (50% progress)
    const midIdx = Math.floor(clip.duration * 15); // ~15 fps index

    Object.entries(MIXAMO_VRM_MAP).forEach(([mName, vName]) => {
      const mNode = fbx.getObjectByName(mName);
      const vNode = moraScene.getObjectByName(vName);
      if (!mNode || !vNode) return;

      mNode.getWorldQuaternion(restInv).invert();
      if (mNode.parent) mNode.parent.getWorldQuaternion(parentRest);
      else parentRest.identity();

      const track = clip.tracks.find(t => t.name.startsWith(mName) && (t.name.includes('quaternion') || t.name.includes('rotation')));
      if (!track) return;

      const trackFrames = track.times.length;
      const fIdx = Math.min(trackFrames - 1, Math.floor(trackFrames / 2));
      quat.fromArray(track.values, fIdx * 4);
      quat.premultiply(parentRest).multiply(restInv);

      // VRM 0.x flip
      const vrmQuat = new THREE.Quaternion(-quat.x, quat.y, -quat.z, quat.w);
      vNode.quaternion.copy(vrmQuat);
    });

    moraScene.updateMatrixWorld(true);

    const lHand = new THREE.Vector3();
    const rHand = new THREE.Vector3();
    const lFoot = new THREE.Vector3();
    const rFoot = new THREE.Vector3();
    const head = new THREE.Vector3();
    moraScene.getObjectByName('J_Bip_L_Hand').getWorldPosition(lHand);
    moraScene.getObjectByName('J_Bip_R_Hand').getWorldPosition(rHand);
    moraScene.getObjectByName('J_Bip_L_Foot').getWorldPosition(lFoot);
    moraScene.getObjectByName('J_Bip_R_Foot').getWorldPosition(rFoot);
    moraScene.getObjectByName('J_Bip_C_Head').getWorldPosition(head);

    const headBone = moraScene.getObjectByName('J_Bip_C_Head');
    const headEuler = new THREE.Euler().setFromQuaternion(headBone.quaternion, 'XYZ');
    const headPitch = (headEuler.x * 180 / Math.PI).toFixed(1);

    console.log(`\n=== Clip: ${item.name.toUpperCase()} (${path.basename(item.file)}) ===`);
    console.log(`Head Pitch: ${headPitch}° | Head Pos: [${head.x.toFixed(2)}, ${head.y.toFixed(2)}, ${head.z.toFixed(2)}]`);
    console.log(`L_Hand: [${lHand.x.toFixed(2)}, ${lHand.y.toFixed(2)}, ${lHand.z.toFixed(2)}] | R_Hand: [${rHand.x.toFixed(2)}, ${rHand.y.toFixed(2)}, ${rHand.z.toFixed(2)}]`);
    console.log(`L_Foot: [${lFoot.x.toFixed(2)}, ${lFoot.y.toFixed(2)}, ${lFoot.z.toFixed(2)}] | R_Foot: [${rFoot.x.toFixed(2)}, ${rFoot.y.toFixed(2)}, ${rFoot.z.toFixed(2)}]`);
  }
}

testAll().catch(console.error);
