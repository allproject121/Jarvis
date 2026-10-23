const fs = require('fs');

function inspectGlb(glbPath) {
  const buf = fs.readFileSync(glbPath);
  const jsonLen = buf.readUInt32LE(12);
  const jsonStr = buf.toString('utf8', 20, 20 + jsonLen);
  return JSON.parse(jsonStr);
}

const gltf = inspectGlb('app/src/main/assets/motions/idle_243.glb');

console.log('=== idle_243.glb Node Transforms ===');
gltf.nodes.forEach((n, i) => {
  const pos = n.translation ? n.translation.map(v => Number(v.toFixed(4))) : [0,0,0];
  const rot = n.rotation ? n.rotation.map(v => Number(v.toFixed(4))) : [0,0,0,1];
  console.log(`[${i}] ${n.name}: pos=[${pos}], rot=[${rot}], children=${JSON.stringify(n.children || [])}`);
});
