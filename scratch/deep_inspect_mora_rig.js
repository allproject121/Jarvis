global.self = global;
global.window = global;
const fs = require('fs');
const path = require('path');
const THREE = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three'));
const { GLTFLoader } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/loaders/GLTFLoader.js'));

const gltfLoader = new GLTFLoader();
const buf = fs.readFileSync('app/src/main/assets/models/mora.glb');

gltfLoader.parse(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength), '', (gltf) => {
  const root = gltf.scene;
  console.log('=== INSPECTING MORA.GLB SKINNED MESHES & SKELETON ===');

  let skinnedMeshCount = 0;
  root.traverse(n => {
    if (n.isSkinnedMesh) {
      skinnedMeshCount++;
      console.log(`\nSkinnedMesh: ${n.name}`);
      console.log(`  Geometry: vertices=${n.geometry.attributes.position.count}`);
      console.log(`  Skeleton bones: ${n.skeleton.bones.length}`);
      console.log(`  BoneInverses: ${n.skeleton.boneInverses.length}`);
      
      // Check bone inverses for NaN or invalid matrices
      let hasInvalidInv = false;
      n.skeleton.boneInverses.forEach((inv, i) => {
        inv.elements.forEach(val => {
          if (!Number.isFinite(val)) hasInvalidInv = true;
        });
      });
      console.log(`  BoneInverses valid: ${!hasInvalidInv}`);

      // Check skinIndex and skinWeight
      const sIndex = n.geometry.attributes.skinIndex;
      const sWeight = n.geometry.attributes.skinWeight;
      console.log(`  SkinIndex attribute: ${!!sIndex}, SkinWeight attribute: ${!!sWeight}`);
    }
  });
  console.log(`\nTotal SkinnedMeshes: ${skinnedMeshCount}`);

  // Inspect bone hierarchy
  console.log('\n=== BONE HIERARCHY ===');
  const hips = root.getObjectByName('J_Bip_C_Hips');
  function printBoneTree(b, depth = 0) {
    const indent = '  '.repeat(depth);
    const p = b.position;
    const q = b.quaternion;
    console.log(`${indent}${b.name} (pos: [${p.x.toFixed(3)}, ${p.y.toFixed(3)}, ${p.z.toFixed(3)}], rot: [${q.x.toFixed(3)}, ${q.y.toFixed(3)}, ${q.z.toFixed(3)}, ${q.w.toFixed(3)}])`);
    b.children.forEach(c => {
      if (c.isBone || c.name.startsWith('J_')) printBoneTree(c, depth + 1);
    });
  }
  if (hips) printBoneTree(hips);
  else console.log('Hips not found!');
});
