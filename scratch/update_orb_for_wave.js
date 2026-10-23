const fs = require('fs');
let s = fs.readFileSync('app/src/main/assets/orb.html', 'utf8');

// 1. Filter out translation tracks in addAnimaMotion so hips don't blow up
const target1 = `    for (const track of clip.tracks) {
      const parts = track.name.split('.');
      const rawBone = parts[0];
      const prop = parts.slice(1).join('.');`;

const repl1 = `    for (const track of clip.tracks) {
      const parts = track.name.split('.');
      const rawBone = parts[0];
      const prop = parts.slice(1).join('.');
      if (prop !== "quaternion") continue; // Crucial: Only retarget skeletal joint rotations (quaternion) to prevent hips translation blowup`;

if (!s.includes(target1) && !s.includes(target1.replace(/\n/g, '\r\n'))) {
  console.error("target1 not found in orb.html");
  process.exit(1);
}

if (s.includes(target1)) {
  s = s.replace(target1, repl1);
} else {
  s = s.replace(target1.replace(/\n/g, '\r\n'), repl1.replace(/\n/g, '\r\n'));
}

// 2. Expose setRotY and model getters on window.JarvisOrb
const target2 = `  triggerReaction: triggerReaction,
  triggerTouchReaction: triggerTouchReaction,
  triggerWave: () => triggerReaction("wave"),`;

const repl2 = `  triggerReaction: triggerReaction,
  triggerTouchReaction: triggerTouchReaction,
  triggerWave: () => triggerReaction("wave"),
  setRotY: (y) => {
    customModelRotY = Number(y) || 0;
    if (customModelGroup) customModelGroup.rotation.y = customModelRotY;
  },
  getEngine: () => customModelEngine,
  getInner: () => customModelInner,
  getGroup: () => customModelGroup,`;

if (s.includes(target2)) {
  s = s.replace(target2, repl2);
} else if (s.includes(target2.replace(/\n/g, '\r\n'))) {
  s = s.replace(target2.replace(/\n/g, '\r\n'), repl2.replace(/\n/g, '\r\n'));
}

fs.writeFileSync('app/src/main/assets/orb.html', s, 'utf8');
console.log('Successfully updated orb.html');
