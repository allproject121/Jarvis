const fs = require('fs');

const buf = fs.readFileSync('app/src/main/assets/models/mora.glb');
const jsonLen = buf.readUInt32LE(12);
const jsonStr = buf.toString('utf8', 20, 20 + jsonLen);
const gltf = JSON.parse(jsonStr);

function printBone(idx, depth = 0) {
  const node = gltf.nodes[idx];
  const indent = '  '.repeat(depth);
  const pos = node.translation ? node.translation.map(v => Number(v.toFixed(4))) : [0,0,0];
  const rot = node.rotation ? node.rotation.map(v => Number(v.toFixed(4))) : [0,0,0,1];
  console.log(`${indent}${node.name} (pos: [${pos}], rot: [${rot}])`);
  if (node.children) {
    node.children.forEach(c => printBone(c, depth + 1));
  }
}

// Find Hips node
const hipsIdx = gltf.nodes.findIndex(n => n.name === 'J_Bip_C_Hips');
console.log('=== Mora Bone Tree from Hips ===');
printBone(hipsIdx);
