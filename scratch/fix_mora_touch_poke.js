const fs = require('fs');
let html = fs.readFileSync('app/src/main/assets/mora_character.html', 'utf8');

// 1. Add onTouchPoke function before onPointerTap
if (!html.includes('function onTouchPoke(')) {
  const target = '    function onPointerTap(e) {';
  const addition = `    let reactionCycle = 0;
    function onTouchPoke() {
      const pool = ["headpat", "joy", "wave", "legs", "pervert"];
      reactionCycle = (reactionCycle + 1) % pool.length;
      triggerMoraReaction(pool[reactionCycle]);
    }

    function onPointerTap(e) {`;
  html = html.replace(target, addition);
  console.log('Added onTouchPoke function');
}

// 2. Add headpat to Joy check and legs to Curious/Surprise check
html = html.replace(
  'else if (activeReaction === "joy" || activeReaction === "laugh") {',
  'else if (activeReaction === "joy" || activeReaction === "laugh" || activeReaction === "headpat") {'
);
html = html.replace(
  'else if (activeReaction === "curious" || activeReaction === "poke" || activeReaction === "surprise" || activeReaction === "blush") {',
  'else if (activeReaction === "curious" || activeReaction === "poke" || activeReaction === "surprise" || activeReaction === "blush" || activeReaction === "legs") {'
);

fs.writeFileSync('app/src/main/assets/mora_character.html', html, 'utf8');
console.log('Successfully updated mora_character.html');
