global.self = global;
global.window = global;
const fs = require('fs');
const path = require('path');
const THREE = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three'));
const { GLTFLoader } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/loaders/GLTFLoader.js'));

require('../app/src/main/assets/mora_clips.js');
const gltfLoader = new GLTFLoader();
const buf = fs.readFileSync('app/src/main/assets/models/mora.glb');

gltfLoader.parse(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength), '', (gltf) => {
  const root = gltf.scene;
  const mixer = new THREE.AnimationMixer(root);
  const idleClip = window.MORA_RETARGETED_CLIPS.map(d => THREE.AnimationClip.parse(d)).find(c => c.name === 'idle');
  const action = mixer.clipAction(idleClip);
  action.play();

  // Find all skinned meshes
  const skinnedMeshes = [];
  root.traverse(n => {
    if (n.isSkinnedMesh) skinnedMeshes.push(n);
  });

  console.log('SkinnedMeshes found:', skinnedMeshes.map(m => m.name));

  // Compute lowest vertex world Y at t = 0, 0.5, 1.0, 2.0s
  [0, 0.5, 1.0, 2.0].forEach(t => {
    mixer.setTime(t);
    root.updateMatrixWorld(true);

    let lowestY = Infinity;
    skinnedMeshes.forEach(mesh => {
      const pos = mesh.geometry.attributes.position;
      const skinIndex = mesh.geometry.attributes.skinIndex;
      const skinWeight = mesh.geometry.attributes.skinWeight;
      const bones = mesh.skeleton.bones;
      const boneInverses = mesh.skeleton.boneInverses;

      // Sample first 500 vertices or all vertices to find min Y
      const v = new THREE.Vector3();
      const tempV = new THREE.Vector3();
      const skinMat = new THREE.Matrix4();
      const boneMat = new THREE.Matrix4();

      for (let i = 0; i < pos.count; i++) {
        v.fromBufferAttribute(pos, i);
        // Compute skinned position
        tempV.set(0, 0, 0);
        for (let j = 0; j < 4; j++) {
          const w = skinWeight.getComponent(i, j);
          if (w > 0) {
            const bIdx = skinIndex.getComponent(i, j);
            boneMat.multiplyMatrices(bones[bIdx].matrixWorld, boneInverses[bIdx]);
            tempV.addScaledVector(v.clone().applyMatrix4(boneMat), w);
          }
        }
        if (tempV.y < lowestY) lowestY = tempV.y;
      }
    });
    console.log(`t=${t}s: Lowest vertex Y = ${lowestY.toFixed(4)}`);
  });
});
