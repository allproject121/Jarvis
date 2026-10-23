const fs = require('fs');
const path = require('path');

const buf = fs.readFileSync('app/src/main/assets/models/mora.glb');
const jsonLen = buf.readUInt32LE(12);
const jsonStr = buf.toString('utf8', 20, 20 + jsonLen);
const gltf = JSON.parse(jsonStr);

// Check index finger bone positions
const lIndex1 = gltf.nodes.find(n => n.name === 'J_Bip_L_Index1');
const lIndex2 = gltf.nodes.find(n => n.name === 'J_Bip_L_Index2');
const lIndex3 = gltf.nodes.find(n => n.name === 'J_Bip_L_Index3');

console.log('J_Bip_L_Index1 translation:', lIndex1 ? lIndex1.translation : null);
console.log('J_Bip_L_Index2 translation:', lIndex2 ? lIndex2.translation : null);
console.log('J_Bip_L_Index3 translation:', lIndex3 ? lIndex3.translation : null);
