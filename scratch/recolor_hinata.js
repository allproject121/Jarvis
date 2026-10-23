const fs = require('fs');
const zlib = require('zlib');

function crc32(buf) {
  let table = new Uint32Array(256);
  for (let i = 0; i < 256; i++) {
    let c = i;
    for (let k = 0; k < 8; k++) {
      c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
    }
    table[i] = c;
  }
  let crc = -1;
  for (let i = 0; i < buf.length; i++) {
    crc = (crc >>> 8) ^ table[(crc ^ buf[i]) & 0xFF];
  }
  return (crc ^ -1) >>> 0;
}

function processPng(inPath, outPath, pixelTransform) {
  const buf = fs.readFileSync(inPath);
  let pos = 8;
  let ihdrBuf = null;
  let ihdr = null;
  const idatParts = [];
  const otherChunks = [];

  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos);
    const type = buf.slice(pos + 4, pos + 8).toString('ascii');
    const data = buf.slice(pos + 8, pos + 8 + len);
    const fullChunk = buf.slice(pos, pos + 12 + len);
    pos += 12 + len;

    if (type === 'IHDR') {
      ihdrBuf = fullChunk;
      ihdr = {
        width: data.readUInt32BE(0),
        height: data.readUInt32BE(4),
        depth: data[8],
        colorType: data[9]
      };
    } else if (type === 'IDAT') {
      idatParts.push(data);
    } else if (type !== 'IEND') {
      otherChunks.push(fullChunk);
    }
  }

  const decompressed = zlib.inflateSync(Buffer.concat(idatParts));
  const w = ihdr.width;
  const h = ihdr.height;
  const bpp = 4; // RGBA
  const stride = 1 + w * bpp;

  // Transform pixels scanline by scanline
  for (let y = 0; y < h; y++) {
    const rowOffset = y * stride;
    // scanline filter byte is decompressed[rowOffset] (filter 0 or we keep it if modifying in-place)
    // Note: if filter != 0, unfilter first to get true RGB
    // Let's check filter byte:
    const filter = decompressed[rowOffset];
    if (filter === 0) {
      for (let x = 0; x < w; x++) {
        const pxOffset = rowOffset + 1 + x * 4;
        const r = decompressed[pxOffset];
        const g = decompressed[pxOffset + 1];
        const b = decompressed[pxOffset + 2];
        const a = decompressed[pxOffset + 3];
        if (a > 0) {
          const res = pixelTransform(r, g, b, a, x, y, w, h);
          decompressed[pxOffset] = res[0];
          decompressed[pxOffset + 1] = res[1];
          decompressed[pxOffset + 2] = res[2];
          decompressed[pxOffset + 3] = res[3];
        }
      }
    } else {
      // Handle unfiltered scanline reconstruction if non-zero filter
      // For standard VRoid textures, let's see if filters are used
    }
  }

  const newIdatData = zlib.deflateSync(decompressed, { level: 9 });
  const idatHeader = Buffer.alloc(8);
  idatHeader.writeUInt32BE(newIdatData.length, 0);
  idatHeader.write('IDAT', 4, 'ascii');
  const idatCrc = Buffer.alloc(4);
  idatCrc.writeUInt32BE(crc32(Buffer.concat([Buffer.from('IDAT'), newIdatData])), 0);

  const iend = Buffer.from([0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82]);

  const outBuf = Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]),
    ihdrBuf,
    ...otherChunks,
    idatHeader,
    newIdatData,
    idatCrc,
    iend
  ]);

  fs.writeFileSync(outPath, outBuf);
  console.log('Saved', outPath, outBuf.length, 'bytes');
}

// 1. Pale Lavender Eyes (Hinata Byakugan / Lavender aesthetic)
processPng(
  'C:/Users/Roshan/.gemini/antigravity/brain/027c6672-b4f2-4696-badc-0e02f6ddd130/sample_a_9_F00_000_00_EyeIris_00.png',
  'C:/Users/Roshan/.gemini/antigravity/brain/027c6672-b4f2-4696-badc-0e02f6ddd130/hinata_EyeIris.png',
  (r, g, b, a, x, y, w, h) => {
    // Calculate luminance
    const lum = 0.299 * r + 0.587 * g + 0.114 * b;
    const norm = lum / 255.0; // 0 to 1
    
    // Map to soft lavender / lilac / pearl iris
    // Deep tones (norm near 0): soft lavender-violet [85, 75, 115]
    // Mid tones (norm ~0.5): pale lilac lavender [175, 165, 205]
    // Bright highlights (norm near 1): pearl lavender white [240, 235, 252]
    let newR, newG, newB;
    if (norm < 0.45) {
      const t = norm / 0.45;
      newR = 75 + t * (155 - 75);
      newG = 65 + t * (145 - 65);
      newB = 110 + t * (195 - 110);
    } else {
      const t = (norm - 0.45) / 0.55;
      newR = 155 + t * (242 - 155);
      newG = 145 + t * (238 - 145);
      newB = 195 + t * (255 - 195);
    }
    return [Math.min(255, Math.round(newR)), Math.min(255, Math.round(newG)), Math.min(255, Math.round(newB)), a];
  }
);

// 2. Dark Lavender / Indigo-Purple Hair
['sample_a_20_F00_000_Hair_00_01.png', 'sample_a_24_F00_000_Hair_00_02.png', 'sample_a_25_F00_000_Hair_00_03.png', 'sample_a_26_F00_000_Hair_00_04.png'].forEach(file => {
  const inPath = 'C:/Users/Roshan/.gemini/antigravity/brain/027c6672-b4f2-4696-badc-0e02f6ddd130/' + file;
  const outPath = inPath.replace('sample_a_', 'hinata_');
  processPng(inPath, outPath, (r, g, b, a, x, y, w, h) => {
    const lum = 0.299 * r + 0.587 * g + 0.114 * b;
    const norm = lum / 255.0;
    // Dark indigo/purple hair:
    // Shadow: deep indigo-navy [28, 22, 48]
    // Mid: rich dark lavender [62, 50, 95]
    // Gloss highlight: soft lavender sheen [160, 145, 195]
    let newR, newG, newB;
    if (norm < 0.4) {
      const t = norm / 0.4;
      newR = 28 + t * (58 - 28);
      newG = 22 + t * (46 - 22);
      newB = 48 + t * (90 - 48);
    } else {
      const t = (norm - 0.4) / 0.6;
      newR = 58 + t * (175 - 58);
      newG = 46 + t * (158 - 46);
      newB = 90 + t * (210 - 90);
    }
    return [Math.min(255, Math.round(newR)), Math.min(255, Math.round(newG)), Math.min(255, Math.round(newB)), a];
  });
});
