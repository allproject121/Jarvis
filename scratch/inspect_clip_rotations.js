const fs = require('fs');
const content = fs.readFileSync('app/src/main/assets/mora_clips.js', 'utf8');
const jsonMatch = content.match(/window\.MORA_RETARGETED_CLIPS\s*=\s*(\[[\s\S]*\]);/);
const clips = JSON.parse(jsonMatch[1]);

clips.forEach(clip => {
  console.log(`\n=================== CLIP: ${clip.name} (Duration: ${clip.duration.toFixed(2)}s) ===================`);
  const headTrack = clip.tracks.find(t => t.name === 'J_Bip_C_Head.quaternion');
  const neckTrack = clip.tracks.find(t => t.name === 'J_Bip_C_Neck.quaternion');
  const lArm = clip.tracks.find(t => t.name === 'J_Bip_L_UpperArm.quaternion');
  const rArm = clip.tracks.find(t => t.name === 'J_Bip_R_UpperArm.quaternion');
  const lForeArm = clip.tracks.find(t => t.name === 'J_Bip_L_LowerArm.quaternion');
  const rForeArm = clip.tracks.find(t => t.name === 'J_Bip_R_LowerArm.quaternion');
  const lHand = clip.tracks.find(t => t.name === 'J_Bip_L_Hand.quaternion');
  const rHand = clip.tracks.find(t => t.name === 'J_Bip_R_Hand.quaternion');

  console.log('Head track keys:', headTrack ? headTrack.times.length : 'none');
  if (headTrack) {
    console.log('  Head t=0:', headTrack.values.slice(0, 4).map(v => v.toFixed(3)));
    console.log('  Head t=mid:', headTrack.values.slice(Math.floor(headTrack.times.length/2)*4, Math.floor(headTrack.times.length/2)*4 + 4).map(v => v.toFixed(3)));
  }
  console.log('Neck track keys:', neckTrack ? neckTrack.times.length : 'none');
  if (neckTrack) {
    console.log('  Neck t=0:', neckTrack.values.slice(0, 4).map(v => v.toFixed(3)));
  }
  console.log('L_UpperArm t=0:', lArm ? lArm.values.slice(0, 4).map(v => v.toFixed(3)) : 'none');
  console.log('R_UpperArm t=0:', rArm ? rArm.values.slice(0, 4).map(v => v.toFixed(3)) : 'none');
  console.log('L_LowerArm t=0:', lForeArm ? lForeArm.values.slice(0, 4).map(v => v.toFixed(3)) : 'none');
  console.log('R_LowerArm t=0:', rForeArm ? rForeArm.values.slice(0, 4).map(v => v.toFixed(3)) : 'none');
  console.log('L_Hand t=0:', lHand ? lHand.values.slice(0, 4).map(v => v.toFixed(3)) : 'none');
  console.log('R_Hand t=0:', rHand ? rHand.values.slice(0, 4).map(v => v.toFixed(3)) : 'none');
});
