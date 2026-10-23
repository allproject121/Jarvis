const path = require('path');
const WebSocket = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/ws'));
const http = require('http');

http.get('http://127.0.0.1:9222/json', (res) => {
  let data = ''; res.on('data', c => data += c);
  res.on('end', () => {
    const list = JSON.parse(data);
    const page = list.find(p => p.type === 'page' && p.url.includes('mora_character.html'));
    const ws = new WebSocket(page.webSocketDebuggerUrl);
    ws.on('open', () => {
      ws.send(JSON.stringify({
        id: 1, method: 'Runtime.evaluate',
        params: {
          expression: `(() => {
            const m = window.MoraCompanion.getModel();
            let bodyMesh = null;
            m.traverse(n => { if (n.isSkinnedMesh) bodyMesh = n; });
            if (!bodyMesh) return 'no skinned mesh found';
            const sk = bodyMesh.skeleton;
            const rArmBone = sk.bones.find(b => b.name === 'J_Bip_R_UpperArm');
            return {
              skinnedMeshName: bodyMesh.name,
              bonesInSkeleton: sk.bones.length,
              rArmBoneFound: !!rArmBone,
              rArmBoneRotation: rArmBone ? { x: rArmBone.rotation.x, y: rArmBone.rotation.y, z: rArmBone.rotation.z } : null,
              rArmBoneQuaternion: rArmBone ? { x: rArmBone.quaternion.x, y: rArmBone.quaternion.y, z: rArmBone.quaternion.z, w: rArmBone.quaternion.w } : null
            };
          })()`,
          returnByValue: true
        }
      }));
    });
    ws.on('message', msg => {
      console.log('Result:', JSON.parse(msg).result.result.value);
      ws.close();
    });
  });
});
