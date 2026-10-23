const fs = require('fs');
const buf = fs.readFileSync('app/src/main/assets/models/mora.glb');
const jsonLen = buf.readUInt32LE(12);
const jsonStr = buf.toString('utf8', 20, 20 + jsonLen);
const gltf = JSON.parse(jsonStr);

console.log('Scenes:', gltf.scenes);
console.log('Skins:', JSON.stringify(gltf.skins, null, 2));
console.log('Nodes count:', gltf.nodes.length);
if (gltf.skins && gltf.skins.length > 0) {
  gltf.skins.forEach((skin, sIdx) => {
    console.log(`Skin ${sIdx}: skeleton root = ${skin.skeleton}, joints count = ${skin.joints ? skin.joints.length : 0}`);
    if (skin.joints) {
      const jointNames = skin.joints.slice(0, 10).map(j => gltf.nodes[j].name);
      console.log(`First 10 joints:`, jointNames);
    }
  });
}

// Check which nodes use which skins
gltf.nodes.forEach((node, idx) => {
  if (node.skin !== undefined || node.mesh !== undefined) {
    console.log(`Node ${idx} (${node.name}): mesh=${node.mesh}, skin=${node.skin}, children=${node.children ? node.children.length : 0}`);
  }
});
