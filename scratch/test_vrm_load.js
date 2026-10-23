global.self = global;
global.window = global;
const fs = require('fs');
const path = require('path');
const THREE = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three'));
const { GLTFLoader } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/loaders/GLTFLoader.js'));
const { VRMLoaderPlugin } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/@pixiv/three-vrm'));

const loader = new GLTFLoader();
loader.register((parser) => new VRMLoaderPlugin(parser));

const buf = fs.readFileSync(path.resolve(__dirname, '../app/src/main/assets/models/mora.glb'));
loader.parse(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength), '', (gltf) => {
  const vrm = gltf.userData.vrm;
  console.log('VRM loaded successfully!');
  console.log('VRM Meta Version:', vrm.meta ? vrm.meta.metaVersion : 'undefined');
  console.log('VRM Humanoid:', !!vrm.humanoid);
  console.log('VRM Expression Manager:', !!vrm.expressionManager);
  console.log('VRM SpringBone Manager:', !!vrm.springBoneManager);
  if (vrm.humanoid) {
    const bones = ['hips', 'spine', 'chest', 'upperChest', 'neck', 'head', 'leftUpperArm', 'leftLowerArm', 'leftHand', 'rightUpperArm', 'rightLowerArm', 'rightHand', 'leftUpperLeg', 'leftLowerLeg', 'leftFoot', 'rightUpperLeg', 'rightLowerLeg', 'rightFoot'];
    bones.forEach(b => {
      const node = vrm.humanoid.getNormalizedBoneNode(b);
      const rawNode = vrm.humanoid.getRawBoneNode(b);
      console.log(`Bone ${b.padEnd(14)} | Normalized: ${node ? node.name : 'null'} | Raw: ${rawNode ? rawNode.name : 'null'}`);
    });
  }
}, (err) => console.error('Error:', err));
