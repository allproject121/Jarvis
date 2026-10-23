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
            const c = window.MoraCompanion.getController();
            const a = c ? c.actions['idle'] : null;
            const rArm = c ? c.bones.rightUpperArm : null;
            return {
              tracks: a ? a._clip.tracks.map(t => t.name) : [],
              rArmRotation: rArm ? { x: rArm.rotation.x, y: rArm.rotation.y, z: rArm.rotation.z } : null,
              rArmQuaternion: rArm ? { x: rArm.quaternion.x, y: rArm.quaternion.y, z: rArm.quaternion.z, w: rArm.quaternion.w } : null
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
