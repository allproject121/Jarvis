const fs = require('fs');
const buf = fs.readFileSync('app/src/main/assets/models/hinata_animations.glb');
const jsonLen = buf.readUInt32LE(12);
const jsonStr = buf.toString('utf8', 20, 20 + jsonLen);
const gltf = JSON.parse(jsonStr);

// Look at the animations and their leg channels
gltf.animations.forEach(anim => {
  console.log(`\nAnim: ${anim.name}`);
  const legChannels = anim.channels.filter(c => {
    const node = gltf.nodes[c.target.node];
    return node && (node.name.includes('UpperLeg') || node.name.includes('LowerLeg') || node.name.includes('Hips'));
  });
  legChannels.forEach(c => {
    const node = gltf.nodes[c.target.node];
    console.log(`  channel: ${node.name}.${c.target.path}`);
  });
});
