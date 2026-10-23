const fs = require('fs');
const files = ['idle_243.glb', 'idle_249.glb', 'idle_252.glb', 'shrug_317.glb', 'talk_314.glb', 'wave_290.glb'];

files.forEach(f => {
  const p = 'app/src/main/assets/motions/' + f;
  if (!fs.existsSync(p)) return;
  const buf = fs.readFileSync(p);
  const jsonLen = buf.readUInt32LE(12);
  const jsonStr = buf.toString('utf8', 20, 20 + jsonLen);
  const gltf = JSON.parse(jsonStr);
  console.log(`\n=== ${f} ===`);
  console.log('Nodes count:', gltf.nodes ? gltf.nodes.length : 0);
  if (gltf.animations) {
    gltf.animations.forEach(a => {
      console.log(`  Anim: "${a.name}", channels: ${a.channels.length}, samplers: ${a.samplers.length}`);
      const targets = a.channels.slice(0, 10).map(c => {
        const n = gltf.nodes[c.target.node];
        return `${n ? n.name : c.target.node}.${c.target.path}`;
      });
      console.log('  First 10 targets:', targets);
    });
  }
});
