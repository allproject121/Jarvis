const fs = require('fs');

async function inspectVRM(url) {
  // First fetch first 20 bytes to get jsonChunkLength
  const headerRes = await fetch(url, { headers: { Range: 'bytes=0-19' } });
  const hBuf = Buffer.from(await headerRes.arrayBuffer());
  const totalLength = hBuf.readUInt32LE(8);
  const jsonChunkLength = hBuf.readUInt32LE(12);

  // Now fetch full json chunk
  const jsonRes = await fetch(url, { headers: { Range: `bytes=20-${19 + jsonChunkLength}` } });
  const jsonBuf = Buffer.from(await jsonRes.arrayBuffer());
  const json = JSON.parse(jsonBuf.toString('utf8'));

  console.log('====================================');
  console.log('URL:', url);
  console.log('Total file size:', totalLength);
  console.log('Title:', json.extensions?.VRM?.meta?.title);
  console.log('Author:', json.extensions?.VRM?.meta?.author);
  console.log('AllowedUserName:', json.extensions?.VRM?.meta?.allowedUserName);
  console.log('Commercial:', json.extensions?.VRM?.meta?.commercialUssageName);
  console.log('License:', json.extensions?.VRM?.meta?.licenseName);
  console.log('OtherLicenseUrl:', json.extensions?.VRM?.meta?.otherLicenseUrl);
  console.log('BlendShapeGroups:', json.extensions?.VRM?.blendShapeMaster?.blendShapeGroups?.map(g => g.name || g.presetName));
  const humanBones = json.extensions?.VRM?.humanoid?.humanBones?.map(b => b.bone);
  console.log('Human bones count:', humanBones?.length);
  console.log('Nodes count:', json.nodes?.length, 'Meshes count:', json.meshes?.length);
  console.log('Images:', json.images?.length, 'Materials:', json.materials?.length);
}

(async () => {
  const models = [
    'https://raw.githubusercontent.com/webaverse/avatar-models/master/model44.vrm',
    'https://raw.githubusercontent.com/webaverse/avatar-models/master/model18.vrm',
    'https://raw.githubusercontent.com/webaverse/avatar-models/master/model41.vrm',
    'https://raw.githubusercontent.com/webaverse/avatar-models/master/model45.vrm',
    'https://raw.githubusercontent.com/webaverse/avatar-models/master/model47.vrm',
    'https://raw.githubusercontent.com/madjin/vrm-samples/master/Avatar_Orion.vrm'
  ];
  for (const m of models) {
    try {
      await inspectVRM(m);
    } catch(e) {
      console.error('Error with', m, e.message);
    }
  }
})();
