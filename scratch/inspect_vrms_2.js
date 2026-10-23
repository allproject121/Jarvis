const fs = require('fs');

async function inspectVRM(url) {
  const headerRes = await fetch(url, { headers: { Range: 'bytes=0-19' } });
  const hBuf = Buffer.from(await headerRes.arrayBuffer());
  const totalLength = hBuf.readUInt32LE(8);
  const jsonChunkLength = hBuf.readUInt32LE(12);

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
  console.log('Human bones count:', json.extensions?.VRM?.humanoid?.humanBones?.length);
  console.log('Meshes count:', json.meshes?.length);
}

(async () => {
  const models = [
    'https://raw.githubusercontent.com/madjin/vrm-samples/master/vroid/fem_vroid.vrm',
    'https://raw.githubusercontent.com/madjin/vrm-samples/master/vroid/stable/AvatarSample_A.vrm',
    'https://raw.githubusercontent.com/madjin/vrm-samples/master/vroid/stable/AvatarSample_B.vrm',
    'https://raw.githubusercontent.com/madjin/vrm-samples/master/vroid/beta/Victoria_Rubin.vrm',
    'https://raw.githubusercontent.com/madjin/vrm-samples/master/vroid/beta/Vita.vrm',
    'https://raw.githubusercontent.com/madjin/vrm-samples/master/vroid/beta/Sendagaya_Shibu.vrm'
  ];
  for (const m of models) {
    try {
      await inspectVRM(m);
    } catch(e) {
      console.error('Error with', m, e.message);
    }
  }
})();
