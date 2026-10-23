const fs = require('fs');
const content = fs.readFileSync('app/src/main/assets/mora_clips.js', 'utf8');
const jsonMatch = content.match(/window\.MORA_RETARGETED_CLIPS\s*=\s*(\[[\s\S]*\]);/);
if (!jsonMatch) {
  console.error('Regex match failed!');
  process.exit(1);
}
const clips = JSON.parse(jsonMatch[1]);
console.log('Found', clips.length, 'clips in mora_clips.js:');
clips.forEach(c => {
  const hipsPos = c.tracks.find(t => t.name === 'J_Bip_C_Hips.position');
  let hipsY = 'none';
  if (hipsPos) {
    hipsY = hipsPos.values.slice(0, 6).filter((_, idx) => idx % 3 === 1).map(v => v.toFixed(3)).join(', ');
  }
  const boneNames = new Set(c.tracks.map(t => t.name.split('.')[0]));
  console.log(`Clip: ${c.name.padEnd(10)} | Dur: ${c.duration.toFixed(2)}s | Tracks: ${c.tracks.length.toString().padEnd(3)} | Bones: ${boneNames.size.toString().padEnd(2)} | HipsY: ${hipsY}`);
});
