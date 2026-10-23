const fs = require('fs');

const buf = fs.readFileSync('app/src/main/assets/models/mora.glb');
const jsonLen = buf.readUInt32LE(12);
const jsonStr = buf.toString('utf8', 20, 20 + jsonLen);
const gltf = JSON.parse(jsonStr);

const humanBones = gltf.extensions.VRM.humanoid.humanBones;
console.log('=== Mora VRM Humanoid Bones ===');
const vrmToMoraMap = {};
humanBones.forEach(hb => {
  const node = gltf.nodes[hb.node];
  vrmToMoraMap[hb.bone] = node.name;
  console.log(`${hb.bone.padEnd(20)} -> node ${String(hb.node).padEnd(4)}: ${node.name}`);
});

console.log('\nTotal mapped VRM bones:', Object.keys(vrmToMoraMap).length);
