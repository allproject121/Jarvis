const fs = require('fs');
const buf = fs.readFileSync('app/src/main/assets/models/mora_animations.glb');
const jsonLen = buf.readUInt32LE(12);
const jsonStr = buf.toString('utf8', 20, 20 + jsonLen);
const gltf = JSON.parse(jsonStr);
const binBuf = buf.slice(20 + jsonLen + 8);

const idleAnim = gltf.animations.find(a => a.name.toLowerCase().includes('idle'));
const hipsChannel = idleAnim.channels.find(c => {
  const n = gltf.nodes[c.target.node];
  return n && n.name === 'J_Bip_C_Hips' && c.target.path === 'rotation';
});
const sampler = idleAnim.samplers[hipsChannel.sampler];
const acc = gltf.accessors[sampler.output];
const bv = gltf.bufferViews[acc.bufferView];

const offset = (bv.byteOffset || 0) + (acc.byteOffset || 0);
const quats = [];
for (let i = 0; i < 5; i++) {
  const o = offset + i * 16;
  quats.push([
    binBuf.readFloatLE(o),
    binBuf.readFloatLE(o + 4),
    binBuf.readFloatLE(o + 8),
    binBuf.readFloatLE(o + 12)
  ]);
}
console.log('First 5 Hips Quaternions in Idle:', quats);
