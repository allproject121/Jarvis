const fs = require('fs');
const buf = fs.readFileSync('app/src/main/assets/models/mora.glb');
const jsonLen = buf.readUInt32LE(12);
const jsonStr = buf.toString('utf8', 20, 20 + jsonLen);
const gltf = JSON.parse(jsonStr);

// Find mesh 0 (Face)
const faceMesh = gltf.meshes[0];
console.log('Face mesh primitives count:', faceMesh.primitives.length);
faceMesh.primitives.forEach((p, idx) => {
  const posAcc = gltf.accessors[p.attributes.POSITION];
  console.log(`Primitive ${idx} min:`, posAcc.min, 'max:', posAcc.max);
});
