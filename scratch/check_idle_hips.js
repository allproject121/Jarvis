const fs = require('fs');
const buf = fs.readFileSync('app/src/main/assets/models/mora_animations.glb');
const jsonLen = buf.readUInt32LE(12);
const jsonStr = buf.toString('utf8', 20, 20 + jsonLen);
const gltf = JSON.parse(jsonStr);

const idleAnim = gltf.animations.find(a => a.name.toLowerCase().includes('idle'));
console.log('Idle animation found:', idleAnim ? idleAnim.name : 'none');

if (idleAnim) {
  const hipsChannel = idleAnim.channels.find(c => {
    const n = gltf.nodes[c.target.node];
    return n && n.name === 'J_Bip_C_Hips' && c.target.path === 'rotation';
  });
  console.log('Hips rotation channel found:', !!hipsChannel);
  if (hipsChannel) {
    const sampler = idleAnim.samplers[hipsChannel.sampler];
    const acc = gltf.accessors[sampler.output];
    console.log('Hips rotation accessor:', acc);
  }
}
