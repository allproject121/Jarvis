const fs = require('fs');
const { decodePng, encodePng } = require('./png_helper');

['sample_a_20_F00_000_Hair_00_01.png', 'sample_a_24_F00_000_Hair_00_02.png', 'sample_a_25_F00_000_Hair_00_03.png', 'sample_a_26_F00_000_Hair_00_04.png'].forEach(file => {
  const inPath = 'C:/Users/Roshan/.gemini/antigravity/brain/027c6672-b4f2-4696-badc-0e02f6ddd130/' + file;
  const img = decodePng(fs.readFileSync(inPath));
  const data = img.data;

  for (let i = 0; i < data.length; i += 4) {
    const r = data[i];
    const g = data[i + 1];
    const b = data[i + 2];
    const a = data[i + 3];
    if (a > 0) {
      const lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
      // Hinata's dark navy / deep purple-indigo hair:
      // Dark shadow: deep indigo-navy [24, 20, 44]
      // Mid: rich dark lavender-purple [52, 44, 82]
      // Highlight sheen: soft lavender gloss [148, 136, 185]
      let nr, ng, nb;
      if (lum < 0.45) {
        const t = lum / 0.45;
        nr = 20 + t * (52 - 20);
        ng = 16 + t * (44 - 16);
        nb = 40 + t * (82 - 40);
      } else {
        const t = (lum - 0.45) / 0.55;
        nr = 52 + t * (158 - 52);
        ng = 44 + t * (146 - 44);
        nb = 82 + t * (205 - 82);
      }
      data[i] = Math.min(255, Math.round(nr));
      data[i + 1] = Math.min(255, Math.round(ng));
      data[i + 2] = Math.min(255, Math.round(nb));
    }
  }

  const outPath = inPath.replace('sample_a_', 'hinata_lavender_');
  fs.writeFileSync(outPath, encodePng(img.width, img.height, data));
  console.log('Saved', outPath);
});
