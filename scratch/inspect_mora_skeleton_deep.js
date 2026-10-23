global.self = global;
global.window = global;
const fs = require('fs');
const path = require('path');
const THREE = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three'));
const { GLTFLoader } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/loaders/GLTFLoader.js'));

const gltfLoader = new GLTFLoader();
const buf = fs.readFileSync('app/src/main/assets/models/mora.glb');

gltfLoader.parse(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength), '', (gltf) => {
  console.log('Mora GLTF loaded.');
  const scene = gltf.scene || gltf.scenes[0];
  const bones = [];
  const skinnedMeshes = [];

  scene.traverse(node => {
    if (node.isBone) {
      bones.push({
        name: node.name,
        parent: node.parent ? node.parent.name : null,
        pos: [node.position.x, node.position.y, node.position.z].map(v => Number(v.toFixed(4))),
        rot: [node.quaternion.x, node.quaternion.y, node.quaternion.z, node.quaternion.w].map(v => Number(v.toFixed(4)))
      });
    }
    if (node.isSkinnedMesh) {
      skinnedMeshes.push({
        name: node.name,
        bonesCount: node.skeleton ? node.skeleton.bones.length : 0,
        rootBone: node.skeleton && node.skeleton.bones.length > 0 ? node.skeleton.bones[0].name : 'none'
      });
    }
  });

  console.log(`Total bones found: ${bones.length}`);
  console.log('Skinned meshes:', skinnedMeshes);

  const keyBones = [
    'J_Bip_C_Hips', 'J_Bip_C_Spine', 'J_Bip_C_Chest', 'J_Bip_C_UpperChest', 'J_Bip_C_Neck', 'J_Bip_C_Head',
    'J_Bip_L_Shoulder', 'J_Bip_L_UpperArm', 'J_Bip_L_LowerArm', 'J_Bip_L_Hand',
    'J_Bip_R_Shoulder', 'J_Bip_R_UpperArm', 'J_Bip_R_LowerArm', 'J_Bip_R_Hand',
    'J_Bip_L_UpperLeg', 'J_Bip_L_LowerLeg', 'J_Bip_L_Foot', 'J_Bip_L_ToeBase',
    'J_Bip_R_UpperLeg', 'J_Bip_R_LowerLeg', 'J_Bip_R_Foot', 'J_Bip_R_ToeBase'
  ];

  console.log('\n--- KEY BONE HIERARCHY & REST TRANSFORMS ---');
  keyBones.forEach(k => {
    const b = bones.find(x => x.name === k);
    if (b) {
      console.log(`${b.name.padEnd(20)} | parent: ${(b.parent||'').padEnd(20)} | pos: [${b.pos.join(', ')}] | rot: [${b.rot.join(', ')}]`);
    } else {
      console.log(`MISSING: ${k}`);
    }
  });
});
