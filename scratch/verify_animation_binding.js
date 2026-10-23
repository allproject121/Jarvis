const fs = require('fs');

const animJsonBuf = (() => {
  const fd = fs.openSync('app/src/main/assets/models/animations.glb', 'r');
  const chunkHeader = Buffer.alloc(8);
  fs.readSync(fd, chunkHeader, 0, 8, 12);
  const chunkLen = chunkHeader.readUInt32LE(0);
  const jsonBuf = Buffer.alloc(chunkLen);
  fs.readSync(fd, jsonBuf, 0, chunkLen, 20);
  fs.closeSync(fd);
  return JSON.parse(jsonBuf.toString('utf8'));
})();

console.log('Total animation clips in animations.glb:', animJsonBuf.animations.length);

const avatarJsonBuf = (() => {
  const fd = fs.openSync('app/src/main/assets/models/jett.glb', 'r');
  const chunkHeader = Buffer.alloc(8);
  fs.readSync(fd, chunkHeader, 0, 8, 12);
  const chunkLen = chunkHeader.readUInt32LE(0);
  const jsonBuf = Buffer.alloc(chunkLen);
  fs.readSync(fd, jsonBuf, 0, chunkLen, 20);
  fs.closeSync(fd);
  return JSON.parse(jsonBuf.toString('utf8'));
})();

const avatarNodeNames = new Set(avatarJsonBuf.nodes.map(n => n.name));
console.log('Avatar node count:', avatarNodeNames.size);

// Check if animation targets match avatar node names
animJsonBuf.animations.forEach((anim, i) => {
  const targets = new Set();
  anim.channels.forEach(ch => {
    const targetNodeIndex = ch.target.node;
    const targetNodeName = animJsonBuf.nodes[targetNodeIndex] ? animJsonBuf.nodes[targetNodeIndex].name : null;
    if (targetNodeName) targets.add(targetNodeName);
  });
  
  let matchCount = 0;
  targets.forEach(t => {
    if (avatarNodeNames.has(t)) matchCount++;
  });
  console.log(`Clip [${i}] "${anim.name}": ${targets.size} animated target nodes. Matching in avatar: ${matchCount}/${targets.size}`);
});
