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
            let skinned = null;
            m.traverse(n => { if (n.isSkinnedMesh && !skinned) skinned = n; });
            return {
              threeRevision: THREE.REVISION,
              materialSkinning: skinned.material ? skinned.material.skinning : undefined,
              materialType: skinned.material ? skinned.material.type : undefined,
              defines: skinned.material ? skinned.material.defines : undefined,
              boneMatricesLength: skinned.skeleton ? skinned.skeleton.boneMatrices.length : 0,
              firstFewBoneMatrices: skinned.skeleton ? Array.from(skinned.skeleton.boneMatrices.slice(0, 16)) : []
            };
          })()`,
          returnByValue: true
        }
      }));
    });
    ws.on('message', msg => {
      console.log('Skinning check:', JSON.stringify(JSON.parse(msg).result.result.value, null, 2));
      ws.close();
    });
  });
});
