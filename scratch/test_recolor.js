const fs = require('fs');
const { decodePng, encodePng } = require('./png_helper');

// Recolor Eye Iris to Hinata Hyuga's Pale Lavender / Lilac Pearl Eyes
const eyeImg = decodePng(fs.readFileSync('C:/Users/Roshan/.gemini/antigravity/brain/027c6672-b4f2-4696-badc-0e02f6ddd130/sample_a_9_F00_000_00_EyeIris_00.png'));
const data = eyeImg.data;

for (let i = 0; i < data.length; i += 4) {
  const r = data[i];
  const g = data[i + 1];
  const b = data[i + 2];
  const a = data[i + 3];
  if (a > 0) {
    const lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
    // Hinata Byakugan / Pale Lavender gradient:
    // Base shadows: [120, 105, 155]
    // Midtones: [180, 170, 218]
    // Highlights: [245, 240, 255]
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
    data[i] = Math.min(255, Math.round(nr));
    data[i + 1] = Math.min(255, Math.round(ng));
    data[i + 2] = Math.min(255, Math.round(nb));
  }
}

const outEye = encodePng(eyeImg.width, eyeImg.height, data);
fs.writeFileSync('C:/Users/Roshan/.gemini/antigravity/brain/027c6672-b4f2-4696-badc-0e02f6ddd130/hinata_EyeIris_lavender.png', outEye);
console.log('Saved hinata_EyeIris_lavender.png');
