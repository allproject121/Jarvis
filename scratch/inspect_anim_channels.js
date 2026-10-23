const fs = require('fs');
const buf = fs.readFileSync('app/src/main/assets/models/hinata_animations.glb');
const jsonLen = buf.readUInt32LE(12);
const jsonStr = buf.toString('utf8', 20, 20 + jsonLen);
const gltf = JSON.parse(jsonStr);

console.log('Animations count in hinata_animations.glb:', gltf.animations.length);
gltf.animations.forEach((anim, aIdx) => {
  console.log(`Anim ${aIdx} name: "${anim.name}", channels: ${anim.channels.length}, samplers: ${anim.samplers.length}`);
  const sampleChannels = anim.channels.slice(0, 5).map(c => {
    const targetNode = gltf.nodes[c.target.node];
    return `${targetNode ? targetNode.name : 'node_' + c.target.node}.${c.target.path}`;
  });
  console.log(`  Sample channels:`, sampleChannels);
});
