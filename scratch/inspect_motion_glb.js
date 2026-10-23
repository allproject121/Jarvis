const fs = require('fs');

function inspectGlb(glbPath) {
  const buf = fs.readFileSync(glbPath);
  const jsonLen = buf.readUInt32LE(12);
  const jsonStr = buf.toString('utf8', 20, 20 + jsonLen);
  return JSON.parse(jsonStr);
}

const gltf = inspectGlb('app/src/main/assets/motions/idle_243.glb');
console.log('idle_243.glb nodes:', gltf.nodes.map((n, i) => `${i}: ${n.name}`));
console.log('idle_243.glb animations:', gltf.animations.map(a => ({
  name: a.name,
  channels: a.channels.length,
  sampleTargets: a.channels.slice(0, 10).map(c => `${gltf.nodes[c.target.node].name}.${c.target.path}`)
})));
