const fs = require('fs');
const { decodePng, encodePng } = require('./png_helper');

console.log('Building Hinata 3D Companion Model...');

// 1. Read AvatarSample_A.vrm
const srcBuf = fs.readFileSync('scratch/AvatarSample_A.vrm');
const jsonLen = srcBuf.readUInt32LE(12);
const jsonStr = srcBuf.slice(20, 20 + jsonLen).toString('utf8');
const json = JSON.parse(jsonStr);
const binOffset = 20 + jsonLen + 8; // skip chunk length + chunk type
const srcBin = srcBuf.slice(binOffset);

// Read original images into memory
const images = [];
json.images.forEach((img, i) => {
  const bv = json.bufferViews[img.bufferView];
  const data = srcBin.slice(bv.byteOffset || 0, (bv.byteOffset || 0) + bv.byteLength);
  images.push({ name: img.name, mimeType: img.mimeType, data: Buffer.from(data) });
});

console.log('Original images count:', images.length);

// 2. Load our recolored textures
// Eyes: Pale lavender
const eyeBuf = fs.readFileSync('C:/Users/Roshan/.gemini/antigravity/brain/027c6672-b4f2-4696-badc-0e02f6ddd130/hinata_EyeIris_lavender.png');
images[9].data = eyeBuf;
console.log('Replaced Image 9 (EyeIris) with pale lavender texture:', eyeBuf.length, 'bytes');

// Hair: Dark purple / indigo lavender
const hair1 = fs.readFileSync('C:/Users/Roshan/.gemini/antigravity/brain/027c6672-b4f2-4696-badc-0e02f6ddd130/hinata_lavender_20_F00_000_Hair_00_01.png');
const hair2 = fs.readFileSync('C:/Users/Roshan/.gemini/antigravity/brain/027c6672-b4f2-4696-badc-0e02f6ddd130/hinata_lavender_24_F00_000_Hair_00_02.png');
const hair3 = fs.readFileSync('C:/Users/Roshan/.gemini/antigravity/brain/027c6672-b4f2-4696-badc-0e02f6ddd130/hinata_lavender_25_F00_000_Hair_00_03.png');
const hair4 = fs.readFileSync('C:/Users/Roshan/.gemini/antigravity/brain/027c6672-b4f2-4696-badc-0e02f6ddd130/hinata_lavender_26_F00_000_Hair_00_04.png');

images[20].data = hair1;
images[24].data = hair2;
images[25].data = hair3;
images[26].data = hair4;
console.log('Replaced Images 20, 24, 25, 26 with dark lavender hair textures');

// Tops: Cream knit cardigan with lavender ribbons
const topsBuf = fs.readFileSync('C:/Users/Roshan/.gemini/antigravity/brain/027c6672-b4f2-4696-badc-0e02f6ddd130/hinata_Tops_01.png');
images[17].data = topsBuf;
console.log('Replaced Image 17 (Tops) with lavender-ribbon cardigan texture');

// Thumbnail: Hinata portrait
const thumbBuf = fs.readFileSync('C:/Users/Roshan/.gemini/antigravity/brain/027c6672-b4f2-4696-badc-0e02f6ddd130/hinata_portrait.png');
images[27].data = thumbBuf;
console.log('Replaced Image 27 (Thumbnail) with Hinata portrait');

// Optimize unused heavy normal map Image 16 (Body_00_nml, 2.1 MB) with 1x1 normal pixel
// since unlit shaders ignore normal maps completely!
const dummyPng1x1 = encodePng(1, 1, Buffer.from([128, 128, 255, 255]));
images[16].data = dummyPng1x1;
images[12].data = dummyPng1x1; // Face_00_nml
images[21].data = dummyPng1x1; // Hair_00_nml
console.log('Optimized unused normal maps (saved ~3 MB)');

// 3. Rebuild binary buffer
// First: separate bufferViews into geometry bufferViews (used by accessors) and image bufferViews
const imageBvIndices = new Set(json.images.map(img => img.bufferView));
console.log('Image bufferViews count:', imageBvIndices.size);

// Re-pack bufferViews
let newBinParts = [];
let currentOffset = 0;

// To maintain 4-byte alignment
function pad4(n) {
  return (n + 3) & ~3;
}

// Preserve all non-image bufferViews in their original binary order
const oldBvOffsets = json.bufferViews.map((bv, idx) => ({ idx, bv, isImg: imageBvIndices.has(idx) }));

// First copy all geometry/accessor bufferViews
oldBvOffsets.filter(x => !x.isImg).forEach(item => {
  const oldBv = item.bv;
  const data = srcBin.slice(oldBv.byteOffset || 0, (oldBv.byteOffset || 0) + oldBv.byteLength);
  
  // Align to 4 bytes
  const alignedOffset = pad4(currentOffset);
  if (alignedOffset > currentOffset) {
    newBinParts.push(Buffer.alloc(alignedOffset - currentOffset));
    currentOffset = alignedOffset;
  }
  
  oldBv.byteOffset = currentOffset;
  newBinParts.push(data);
  currentOffset += data.length;
});

// Now append new image data for image bufferViews
json.images.forEach((img, i) => {
  const bv = json.bufferViews[img.bufferView];
  const imgData = images[i].data;
  
  const alignedOffset = pad4(currentOffset);
  if (alignedOffset > currentOffset) {
    newBinParts.push(Buffer.alloc(alignedOffset - currentOffset));
    currentOffset = alignedOffset;
  }
  
  bv.byteOffset = currentOffset;
  bv.byteLength = imgData.length;
  newBinParts.push(imgData);
  currentOffset += imgData.length;
});

// Final pad to 4 bytes
const finalAligned = pad4(currentOffset);
if (finalAligned > currentOffset) {
  newBinParts.push(Buffer.alloc(finalAligned - currentOffset));
  currentOffset = finalAligned;
}

const newBinBuffer = Buffer.concat(newBinParts);
console.log('New BIN buffer size:', newBinBuffer.length, '(' + (newBinBuffer.length / 1024 / 1024).toFixed(2) + ' MB)');

// Update buffer[0].byteLength
json.buffers[0].byteLength = newBinBuffer.length;

// Update metadata
if (json.extensions && json.extensions.VRM && json.extensions.VRM.meta) {
  json.extensions.VRM.meta.title = 'Hinata Hyuga AI Companion';
  json.extensions.VRM.meta.author = 'Jarvis AI (Original Anime Companion)';
  json.extensions.VRM.meta.contactInformation = 'Local Jarvis Assistant';
  json.extensions.VRM.meta.reference = 'Inspired by Hinata Hyuga; CC-compliant base';
}

// Convert JSON to Buffer and pad to 4 bytes with spaces
let newJsonStr = JSON.stringify(json);
let newJsonBuf = Buffer.from(newJsonStr, 'utf8');
const jsonPaddedLen = pad4(newJsonBuf.length);
if (jsonPaddedLen > newJsonBuf.length) {
  const padding = Buffer.alloc(jsonPaddedLen - newJsonBuf.length, 0x20); // space
  newJsonBuf = Buffer.concat([newJsonBuf, padding]);
}

// Build GLB
// 12 bytes header + (8 + jsonLen) + (8 + binLen)
const totalGlbLen = 12 + (8 + newJsonBuf.length) + (8 + newBinBuffer.length);
const header = Buffer.alloc(12);
header.writeUInt32LE(0x46546C67, 0); // 'glTF'
header.writeUInt32LE(2, 4);          // version 2
header.writeUInt32LE(totalGlbLen, 8);

const jsonChunkHeader = Buffer.alloc(8);
jsonChunkHeader.writeUInt32LE(newJsonBuf.length, 0);
jsonChunkHeader.writeUInt32LE(0x4E4F534A, 4); // 'JSON'

const binChunkHeader = Buffer.alloc(8);
binChunkHeader.writeUInt32LE(newBinBuffer.length, 0);
binChunkHeader.writeUInt32LE(0x004E4942, 4); // 'BIN\0'

const glbBuf = Buffer.concat([
  header,
  jsonChunkHeader,
  newJsonBuf,
  binChunkHeader,
  newBinBuffer
]);

const outGlbPath = 'app/src/main/assets/models/hinata.glb';
fs.writeFileSync(outGlbPath, glbBuf);
console.log('Successfully created', outGlbPath, glbBuf.length, 'bytes (' + (glbBuf.length / 1024 / 1024).toFixed(2) + ' MB)');
