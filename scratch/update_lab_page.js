const fs = require('fs');
let s = fs.readFileSync('research_repos/Anima/frontend/src/pages/LabPage.tsx', 'utf8').replace(/\r\n/g, '\n');

const target = `        current.motion!.autonomous = false
        setPlaying(''); setExpression('neutral')`;

const replacement = `        current.motion!.autonomous = false
        setPlaying(''); setExpression('neutral')
        const params = new URLSearchParams(window.location.search);
        const m = params.get('motion');
        const em = params.get('emotion');
        const aud = parseFloat(params.get('audio') || '0');
        if (aud > 0) current.audioLevel = aud;
        if (em) current.setEmotion(em as EmotionName);
        if (m) playFile(m.split('_')[0] as MotionName, m);`;

if (!s.includes(target)) {
  console.error("Target not found");
  process.exit(1);
}

s = s.replace(target, replacement);
fs.writeFileSync('research_repos/Anima/frontend/src/pages/LabPage.tsx', s, 'utf8');
console.log('Updated LabPage.tsx successfully');
