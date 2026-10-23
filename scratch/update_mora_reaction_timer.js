const fs = require('fs');
const filePath = 'app/src/main/assets/mora_character.html';
let content = fs.readFileSync(filePath, 'utf8');

const isCRLF = content.includes('\r\n');
let normalized = content.replace(/\r\n/g, '\n');

const target = `    function triggerMoraReaction(reaction) {
      activeReaction = (reaction || "").toLowerCase();
      reactionTimer = 2.4;
      console.log("Mora reaction triggered:", activeReaction);
    }`;

const replacement = `    function triggerMoraReaction(reaction) {
      activeReaction = (reaction || "").toLowerCase();
      if (activeReaction === "dance") reactionTimer = 7.5;
      else if (activeReaction === "joy" || activeReaction === "laugh") reactionTimer = 6.5;
      else if (activeReaction === "wave") reactionTimer = 4.1;
      else if (activeReaction === "teach" || activeReaction === "learn") reactionTimer = 3.0;
      else reactionTimer = 2.5;
      console.log("Mora reaction triggered:", activeReaction, "duration:", reactionTimer);
    }`;

if (!normalized.includes(target)) {
  console.error('Target not found in mora_character.html');
  process.exit(1);
}

let updated = normalized.replace(target, replacement);
if (isCRLF) updated = updated.replace(/\n/g, '\r\n');
fs.writeFileSync(filePath, updated, 'utf8');
console.log('Successfully updated mora_character.html reactionTimer');
