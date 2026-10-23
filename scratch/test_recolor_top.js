const fs = require('fs');
const { decodePng, encodePng } = require('./png_helper');

const inPath = 'C:/Users/Roshan/.gemini/antigravity/brain/027c6672-b4f2-4696-badc-0e02f6ddd130/sample_a_17_F00_006_01_Tops_01.png';
const img = decodePng(fs.readFileSync(inPath));
const data = img.data;

for (let i = 0; i < data.length; i += 4) {
  const r = data[i];
  const g = data[i + 1];
  const b = data[i + 2];
  const a = data[i + 3];
  if (a > 0) {
    // Check if pixel is part of the ribbon (teal/cyan hue: g > r + 15 and b > r + 15)
    if (b > r + 10 && g > r - 10 && r < 120) {
      const lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
      // Recolor ribbon to deep indigo / lavender purple:
      let nr, ng, nb;
      if (lum < 0.4) {
        const t = lum / 0.4;
        nr = 35 + t * (75 - 35);
        ng = 28 + t * (60 - 28);
        nb = 65 + t * (120 - 65);
      } else {
        const t = (lum - 0.4) / 0.6;
        nr = 75 + t * (170 - 75);
        ng = 60 + t * (150 - 60);
        nb = 120 + t * (210 - 120);
      }
      data[i] = Math.min(255, Math.round(nr));
      data[i + 1] = Math.min(255, Math.round(ng));
      data[i + 2] = Math.min(255, Math.round(nb));
    }
  }
}

const outPath = 'C:/Users/Roshan/.gemini/antigravity/brain/027c6672-b4f2-4696-badc-0e02f6ddd130/hinata_Tops_01.png';
fs.writeFileSync(outPath, encodePng(img.width, img.height, data));
console.log('Saved', outPath);
