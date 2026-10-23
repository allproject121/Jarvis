const fs = require('fs');

function inspectGlb(glbPath) {
  const buf = fs.readFileSync(glbPath);
  const jsonLen = buf.readUInt32LE(12);
  const jsonStr = buf.toString('utf8', 20, 20 + jsonLen);
  const gltf = JSON.parse(jsonStr);
  return gltf;
}

const animsGltf = inspectGlb('app/src/main/assets/models/mora_animations.glb');
const moraGltf = inspectGlb('app/src/main/assets/models/mora.glb');

console.log('=== animsGltf nodes count ===', animsGltf.nodes.length);
console.log('animsGltf node names:', animsGltf.nodes.map((n, i) => `${i}: ${n.name}`));

const idle = animsGltf.animations.find(a => a.name.toLowerCase().includes('idle'));
console.log('\n=== Idle Animation Channels ===');
const channelSummary = {};
idle.channels.forEach(ch => {
  const node = animsGltf.nodes[ch.target.node];
  const nodeName = node ? node.name : ch.target.node;
  const path = ch.target.path; // translation, rotation, scale
  if (!channelSummary[path]) channelSummary[path] = [];
  channelSummary[path].push(nodeName);
});

console.log('Channels by path:');
for (const [path, list] of Object.entries(channelSummary)) {
  console.log(`Path: ${path} (${list.length} bones):`, list.slice(0, 10), '...');
}

// Compare bone rest positions between mora.glb and animsGltf
console.log('\n=== Comparing Mora rest positions vs anims rest positions ===');
const moraBoneMap = {};
moraGltf.nodes.forEach(n => {
  if (n.name) moraBoneMap[n.name] = n;
});

const animsBoneMap = {};
animsGltf.nodes.forEach(n => {
  if (n.name) animsBoneMap[n.name] = n;
});

['J_Bip_C_Hips', 'J_Bip_C_Spine', 'J_Bip_C_Chest', 'J_Bip_L_UpperArm', 'J_Bip_L_LowerArm', 'J_Bip_L_Hand', 'J_Bip_L_UpperLeg', 'J_Bip_L_LowerLeg'].forEach(b => {
  const mNode = moraBoneMap[b];
  const aNode = animsBoneMap[b];
  console.log(`Bone: ${b}`);
  console.log(`  mora.glb: pos = ${JSON.stringify(mNode ? mNode.translation : 'none')}, rot = ${JSON.stringify(mNode ? mNode.rotation : 'none')}`);
  console.log(`  anims.glb: pos = ${JSON.stringify(aNode ? aNode.translation : 'none')}, rot = ${JSON.stringify(aNode ? aNode.rotation : 'none')}`);
});
