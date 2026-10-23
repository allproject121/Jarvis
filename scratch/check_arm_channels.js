const fs = require('fs');
const buf = fs.readFileSync('app/src/main/assets/models/mora_animations.glb');
const jsonLen = buf.readUInt32LE(12);
const jsonStr = buf.toString('utf8', 20, 20 + jsonLen);
const gltf = JSON.parse(jsonStr);

const idleAnim = gltf.animations.find(a => a.name.toLowerCase().includes('idle'));
const armChannels = idleAnim.channels.filter(c => {
  const n = gltf.nodes[c.target.node];
  return n && n.name.toLowerCase().includes('arm');
});
console.log('Arm channels in Idle:');
armChannels.forEach(c => {
  const n = gltf.nodes[c.target.node];
  console.log(`  node=${n.name}, path=${c.target.path}`);
});
