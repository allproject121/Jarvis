const fs = require('fs');
const { decodePng, encodePng } = require('./png_helper');

const inPath = 'C:/Users/Roshan/.gemini/antigravity/brain/027c6672-b4f2-4696-badc-0e02f6ddd130/thumb_sample_a.png';
const img = decodePng(fs.readFileSync(inPath));
const data = img.data;

for (let y = 0; y < img.height; y++) {
  for (let x = 0; x < img.width; x++) {
    const idx = (y * img.width + x) * 4;
    const r = data[idx];
    const g = data[idx + 1];
    const b = data[idx + 2];
    const a = data[idx + 3];

    // Detect hair: brown tones (r > g && g > b && r < 140, or top/sides region)
    // Eyes region: y between 480 and 700, x between 250 and 750
    const inEyeRegion = (y > 480 && y < 700 && ((x > 250 && x < 420) || (x > 600 && x < 770)));
    
    // Check if pixel is eye iris (brown iris inside eye region)
    if (inEyeRegion && r > 50 && g > 25 && b > 10 && r > b + 15 && !(r > 220 && g > 220 && b > 220)) {
      const lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
      let nr, ng, nb;
      if (lum < 0.5) {
        const t = lum / 0.5;
        nr = 95 + t * (180 - 95);
        ng = 85 + t * (170 - 85);
        nb = 145 + t * (218 - 145);
      } else {
        const t = (lum - 0.5) / 0.5;
        nr = 180 + t * (248 - 180);
        ng = 170 + t * (244 - 170);
        nb = 218 + t * (255 - 218);
      }
      data[idx] = Math.min(255, Math.round(nr));
      data[idx + 1] = Math.min(255, Math.round(ng));
      data[idx + 2] = Math.min(255, Math.round(nb));
    }
    // Check if hair: brown shades where r > b and not skin (skin has r > 210, g > 170, b > 150)
    else if ((r > b + 10 || (r > 30 && g < 100 && b < 100)) && (r < 185 || g < 150) && (y < 520 || x < 280 || x > 740 || (y > 750 && x < 350) || (y > 750 && x > 670))) {
      // Exclude sky background (b > 180, g > 150, b > r)
      if (!(b > 150 && b > r + 30)) {
        const lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        let nr, ng, nb;
        if (lum < 0.4) {
          const t = lum / 0.4;
          nr = 24 + t * (52 - 24);
          ng = 20 + t * (44 - 20);
          nb = 44 + t * (82 - 44);
        } else {
          const t = (lum - 0.4) / 0.6;
          nr = 52 + t * (158 - 52);
          ng = 44 + t * (146 - 44);
          nb = 82 + t * (205 - 82);
        }
        data[idx] = Math.min(255, Math.round(nr));
        data[idx + 1] = Math.min(255, Math.round(ng));
        data[idx + 2] = Math.min(255, Math.round(nb));
      }
    }
  }
}

const outPath = 'C:/Users/Roshan/.gemini/antigravity/brain/027c6672-b4f2-4696-badc-0e02f6ddd130/hinata_portrait.png';
fs.writeFileSync(outPath, encodePng(img.width, img.height, data));
console.log('Saved', outPath);
