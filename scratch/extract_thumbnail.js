const fs = require('fs');

async function extractVRMThumbnail(url, outPath) {
  console.log('Downloading header for', url);
  // Download first 500KB to get header and thumbnail
  const res = await fetch(url);
  const buf = Buffer.from(await res.arrayBuffer());
  
  const jsonChunkLength = buf.readUInt32LE(12);
  const jsonStr = buf.slice(20, 20 + jsonChunkLength).toString('utf8');
  const json = JSON.parse(jsonStr);

  const binChunkOffset = 20 + jsonChunkLength + 8; // skip chunk length + chunk type
  const textureIndex = json.extensions?.VRM?.meta?.texture;
  console.log('Thumbnail texture index:', textureIndex);

  if (textureIndex !== undefined && json.textures && json.textures[textureIndex]) {
    const imageIndex = json.textures[textureIndex].source;
    const imgInfo = json.images[imageIndex];
    console.log('Image info:', imgInfo);
    if (imgInfo.bufferView !== undefined) {
      const bv = json.bufferViews[imgInfo.bufferView];
      const offset = binChunkOffset + (bv.byteOffset || 0);
      const imgBuf = buf.slice(offset, offset + bv.byteLength);
      fs.writeFileSync(outPath, imgBuf);
      console.log('Saved thumbnail to', outPath, 'bytes:', imgBuf.length);
      return;
    }
  }
  console.log('No embedded thumbnail bufferView found.');
}

(async () => {
  await extractVRMThumbnail(
    'https://raw.githubusercontent.com/webaverse/avatar-models/master/model18.vrm',
    'C:/Users/Roshan/.gemini/antigravity/brain/027c6672-b4f2-4696-badc-0e02f6ddd130/thumb_model18.png'
  );
  await extractVRMThumbnail(
    'https://raw.githubusercontent.com/madjin/vrm-samples/master/Avatar_Orion.vrm',
    'C:/Users/Roshan/.gemini/antigravity/brain/027c6672-b4f2-4696-badc-0e02f6ddd130/thumb_orion.png'
  );
})();
