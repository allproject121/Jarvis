const fs = require('fs');
const path = require('path');

function inspectFolder(dir) {
  if (!fs.existsSync(dir)) return;
  const files = fs.readdirSync(dir);
  console.log(`=== ${dir} ===`);
  files.forEach(f => {
    const full = path.join(dir, f);
    const stat = fs.statSync(full);
    if (stat.isFile()) {
      console.log(`  ${f} (${stat.size} bytes)`);
    }
  });
}

inspectFolder('app/src/main/assets/models');
inspectFolder('app/src/main/assets/motions');
inspectFolder('research_repos/Anima/frontend/public/motions');
inspectFolder('research_repos/anime-ai/humanoid-animations');
