const fs = require('fs');

function inspectGlb(glbPath) {
  const buf = fs.readFileSync(glbPath);
  const jsonLen = buf.readUInt32LE(12);
  const jsonStr = buf.toString('utf8', 20, 20 + jsonLen);
  const gltf = JSON.parse(jsonStr);
  return gltf;
}

const mora = inspectGlb('app/src/main/assets/models/mora.glb');
const anims = inspectGlb('app/src/main/assets/models/mora_animations.glb');

console.log('=== MORA.GLB ===');
console.log('Nodes count:', mora.nodes.length);
console.log('Skins count:', mora.skins ? mora.skins.length : 0);
if (mora.skins) {
  mora.skins.forEach((s, idx) => {
    console.log(`Skin ${idx}: skeleton root = ${s.skeleton}, joints count = ${s.joints.length}`);
    const jointNames = s.joints.map(j => mora.nodes[j].name);
    console.log(`Joints:`, jointNames);
  });
}

// Print full node hierarchy
function printTree(nodeIdx, depth = 0) {
  const n = mora.nodes[nodeIdx];
  const indent = '  '.repeat(depth);
  console.log(`${indent}[${nodeIdx}] ${n.name || 'unnamed'} (children: ${(n.children || []).length})`);
  if (n.children) {
    n.children.forEach(c => printTree(c, depth + 1));
  }
}

console.log('\n=== MORA HIERARCHY ROOT ===');
if (mora.scenes && mora.scenes[0]) {
  mora.scenes[0].nodes.forEach(rootIdx => printTree(rootIdx));
}

console.log('\n=== MORA_ANIMATIONS.GLB ===');
console.log('Nodes count:', anims.nodes.length);
if (anims.skins) {
  anims.skins.forEach((s, idx) => {
    console.log(`Skin ${idx}: skeleton root = ${s.skeleton}, joints count = ${s.joints.length}`);
    const jointNames = s.joints.map(j => anims.nodes[j].name);
    console.log(`Joints:`, jointNames);
  });
}
console.log('\nAnimations in anims.glb:');
anims.animations.forEach(a => {
  console.log(`- ${a.name} (channels: ${a.channels.length})`);
  // sample some channel targets
  const targets = a.channels.slice(0, 15).map(c => {
    const node = anims.nodes[c.target.node];
    return `${node ? node.name : c.target.node}.${c.target.path}`;
  });
  console.log('  sample channels:', targets);
});
