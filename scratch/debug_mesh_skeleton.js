const path = require('path');
const WebSocket = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/ws'));
const http = require('http');

http.get('http://127.0.0.1:9222/json', (res) => {
  let data = ''; res.on('data', c => data += c);
  res.on('end', () => {
    const list = JSON.parse(data);
    const page = list.find(p => p.type === 'page' && p.url.includes('mora_character.html'));
    if (!page) { console.error('Page not found'); process.exit(1); }
    const ws = new WebSocket(page.webSocketDebuggerUrl);
    ws.on('open', () => {
      ws.send(JSON.stringify({
        id: 1, method: 'Runtime.evaluate',
        params: {
          expression: `(() => {
            const m = window.MoraCompanion.getModel();
            const meshes = [];
            m.traverse(n => {
              if (n.isSkinnedMesh) {
                meshes.push({
                  name: n.name,
                  bindMode: n.bindMode,
                  skeletonBonesCount: n.skeleton ? n.skeleton.bones.length : 0,
                  boneTexture: !!(n.skeleton && n.skeleton.boneTexture),
                  matrixAutoUpdate: n.matrixAutoUpdate,
                  matrixWorldNeedsUpdate: n.matrixWorldNeedsUpdate,
                  bones0Name: n.skeleton && n.skeleton.bones[0] ? n.skeleton.bones[0].name : null,
                  rArmBoneIdx: n.skeleton ? n.skeleton.bones.findIndex(b => b.name === 'J_Bip_R_UpperArm') : -1
                });
              }
            });
            return {
              meshes,
              activeActionName: window.MoraCompanion.getController().activeActionName,
              activeActionRunning: window.MoraCompanion.getController().activeAction ? window.MoraCompanion.getController().activeAction.isRunning() : false,
              activeActionWeight: window.MoraCompanion.getController().activeAction ? window.MoraCompanion.getController().activeAction.getEffectiveWeight() : 0,
              timeScale: window.MoraCompanion.getController().activeAction ? window.MoraCompanion.getController().activeAction.getEffectiveTimeScale() : 0
            };
          })()`,
          returnByValue: true
        }
      }));
    });
    ws.on('message', msg => {
      console.log('Result:', JSON.stringify(JSON.parse(msg).result.result.value, null, 2));
      ws.close();
    });
  });
});
