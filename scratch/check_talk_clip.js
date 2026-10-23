global.self = global;
global.window = global;
const fs = require('fs');
const path = require('path');
const THREE = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three'));
const { GLTFLoader } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/loaders/GLTFLoader.js'));

const gltfLoader = new GLTFLoader();

async function checkTalkClip() {
  const moraBuf = fs.readFileSync('app/src/main/assets/models/mora.glb');
  const moraGltf = await new Promise(r => gltfLoader.parse(moraBuf.buffer.slice(moraBuf.byteOffset, moraBuf.byteOffset + moraBuf.byteLength), '', r));
  const moraScene = moraGltf.scene;

  const talkBuf = fs.readFileSync('app/src/main/assets/motions/talk_314.glb');
  const talkGltf = await new Promise(r => gltfLoader.parse(talkBuf.buffer.slice(talkBuf.byteOffset, talkBuf.byteOffset + talkBuf.byteLength), '', r));
  const clip = talkGltf.animations[0];

  const BONE_RIG_MAP = {
    Hips: 'J_Bip_C_Hips', Spine02: 'J_Bip_C_Spine', Spine01: 'J_Bip_C_Chest', Spine: 'J_Bip_C_UpperChest', neck: 'J_Bip_C_Neck', Head: 'J_Bip_C_Head',
    LeftShoulder: 'J_Bip_L_Shoulder', LeftArm: 'J_Bip_L_UpperArm', LeftForeArm: 'J_Bip_L_LowerArm', LeftHand: 'J_Bip_L_Hand',
    RightShoulder: 'J_Bip_R_Shoulder', RightArm: 'J_Bip_R_UpperArm', RightForeArm: 'J_Bip_R_LowerArm', RightHand: 'J_Bip_R_Hand',
    LeftUpLeg: 'J_Bip_L_UpperLeg', LeftLeg: 'J_Bip_L_LowerLeg', LeftFoot: 'J_Bip_L_Foot', LeftToeBase: 'J_Bip_L_ToeBase',
    RightUpLeg: 'J_Bip_R_UpperLeg', RightLeg: 'J_Bip_R_LowerLeg', RightFoot: 'J_Bip_R_Foot', RightToeBase: 'J_Bip_R_ToeBase'
  };

  const restInv = new THREE.Quaternion();
  const parentRest = new THREE.Quaternion();
  const quat = new THREE.Quaternion();

  for (let t = 0; t <= clip.duration; t += 1.0) {
    moraScene.traverse(n => { if (n.isBone) n.quaternion.set(0, 0, 0, 1); });

    clip.tracks.forEach(track => {
      const boneRaw = track.name.split('.')[0];
      const targetBone = BONE_RIG_MAP[boneRaw];
      if (!targetBone) return;
      const srcNode = talkGltf.scene.getObjectByName(boneRaw);
      const vNode = moraScene.getObjectByName(targetBone);
      if (!srcNode || !vNode) return;

      srcNode.getWorldQuaternion(restInv).invert();
      if (srcNode.parent) srcNode.parent.getWorldQuaternion(parentRest);
      else parentRest.identity();

      // Find sample near time t
      let idx = 0;
      for (let i = 0; i < track.times.length; i++) {
        if (track.times[i] <= t) idx = i;
        else break;
      }
      quat.fromArray(track.values, idx * 4);
      quat.premultiply(parentRest).multiply(restInv);
      vNode.quaternion.set(-quat.x, quat.y, -quat.z, quat.w);
    });

    moraScene.updateMatrixWorld(true);
    const lHand = new THREE.Vector3();
    const rHand = new THREE.Vector3();
    moraScene.getObjectByName('J_Bip_L_Hand').getWorldPosition(lHand);
    moraScene.getObjectByName('J_Bip_R_Hand').getWorldPosition(rHand);
    console.log(`t=${t.toFixed(1)}s | L_Hand: [${lHand.x.toFixed(2)}, ${lHand.y.toFixed(2)}, ${lHand.z.toFixed(2)}] | R_Hand: [${rHand.x.toFixed(2)}, ${rHand.y.toFixed(2)}, ${rHand.z.toFixed(2)}]`);
  }
}

checkTalkClip().catch(console.error);
