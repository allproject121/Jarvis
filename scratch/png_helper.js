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

function paeth(a, b, c) {
  const p = a + b - c;
  const pa = Math.abs(p - a);
  const pb = Math.abs(p - b);
  const pc = Math.abs(p - c);
  if (pa <= pb && pa <= pc) return a;
  if (pb <= pc) return b;
  return c;
}

function decodePng(buf) {
  let pos = 8;
  let ihdr = null;
  const idats = [];
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos);
    const type = buf.slice(pos + 4, pos + 8).toString('ascii');
    const data = buf.slice(pos + 8, pos + 8 + len);
    pos += 12 + len;
    if (type === 'IHDR') {
      ihdr = {
        width: data.readUInt32BE(0),
        height: data.readUInt32BE(4),
        depth: data[8],
        colorType: data[9]
      };
    } else if (type === 'IDAT') {
      idats.push(data);
    }
  }

  const decompressed = zlib.inflateSync(Buffer.concat(idats));
  const w = ihdr.width;
  const h = ihdr.height;
  const bpp = 4;
  const rawData = Buffer.alloc(w * h * bpp);

  let srcPos = 0;
  for (let y = 0; y < h; y++) {
    const filter = decompressed[srcPos++];
    const rowStart = y * w * bpp;
    const prevRowStart = (y - 1) * w * bpp;

    for (let x = 0; x < w * bpp; x++) {
      const filt = decompressed[srcPos++];
      const left = x >= bpp ? rawData[rowStart + x - bpp] : 0;
      const up = y > 0 ? rawData[prevRowStart + x] : 0;
      const upLeft = (y > 0 && x >= bpp) ? rawData[prevRowStart + x - bpp] : 0;

      let val = 0;
      if (filter === 0) val = filt;
      else if (filter === 1) val = filt + left;
      else if (filter === 2) val = filt + up;
      else if (filter === 3) val = filt + Math.floor((left + up) / 2);
      else if (filter === 4) val = filt + paeth(left, up, upLeft);
      rawData[rowStart + x] = val & 0xFF;
    }
  }
  return { width: w, height: h, data: rawData };
}

function encodePng(width, height, rawData) {
  const bpp = 4;
  const scanlines = Buffer.alloc(height * (1 + width * bpp));
  for (let y = 0; y < height; y++) {
    const dstOffset = y * (1 + width * bpp);
    scanlines[dstOffset] = 0; // Filter 0 (None)
    rawData.copy(scanlines, dstOffset + 1, y * width * bpp, (y + 1) * width * bpp);
  }

  const idatData = zlib.deflateSync(scanlines, { level: 9 });

  const ihdrData = Buffer.alloc(13);
  ihdrData.writeUInt32BE(width, 0);
  ihdrData.writeUInt32BE(height, 4);
  ihdrData[8] = 8; // 8-bit
  ihdrData[9] = 6; // RGBA
  ihdrData[10] = 0;
  ihdrData[11] = 0;
  ihdrData[12] = 0;

  function makeChunk(type, data) {
    const h = Buffer.alloc(8);
    h.writeUInt32BE(data.length, 0);
    h.write(type, 4, 'ascii');
    const c = Buffer.alloc(4);
    c.writeUInt32BE(crc32(Buffer.concat([Buffer.from(type, 'ascii'), data])), 0);
    return Buffer.concat([h, data, c]);
  }

  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]),
    makeChunk('IHDR', ihdrData),
    makeChunk('IDAT', idatData),
    Buffer.from([0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82])
  ]);
}

module.exports = { decodePng, encodePng };
