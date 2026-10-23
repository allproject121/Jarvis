const fs = require('fs');
const buf = fs.readFileSync('app/src/main/assets/models/hinata_animations.glb');
console.log('Size:', buf.length);
// GLTF JSON is at byte offset 12 (4 bytes length, 4 bytes type 'JSON')
const jsonLen = buf.readUInt32LE(12);
const jsonBuf = buf.slice(20, 20 + jsonLen);
const gltf = JSON.parse(jsonBuf.toString('utf8'));
console.log('GLTF animations count:', gltf.animations ? gltf.animations.length : 0);
if (gltf.animations) {
  gltf.animations.forEach((a, i) => {
    console.log(`Animation [${i}]: name="${a.name}" channels=${a.channels ? a.channels.length : 0}`);
  });
}
