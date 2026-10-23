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
            const rArm = m.getObjectByName('J_Bip_R_UpperArm');
            const lArm = m.getObjectByName('J_Bip_L_UpperArm');
            const rForeArm = m.getObjectByName('J_Bip_R_LowerArm');
            const lForeArm = m.getObjectByName('J_Bip_L_LowerArm');
            return {
              rArm: rArm ? { rot: [rArm.rotation.x, rArm.rotation.y, rArm.rotation.z], quat: [rArm.quaternion.x, rArm.quaternion.y, rArm.quaternion.z, rArm.quaternion.w] } : null,
              lArm: lArm ? { rot: [lArm.rotation.x, lArm.rotation.y, lArm.rotation.z], quat: [lArm.quaternion.x, lArm.quaternion.y, lArm.quaternion.z, lArm.quaternion.w] } : null,
              rForeArm: rForeArm ? { rot: [rForeArm.rotation.x, rForeArm.rotation.y, rForeArm.rotation.z] } : null,
              lForeArm: lForeArm ? { rot: [lForeArm.rotation.x, lForeArm.rotation.y, lForeArm.rotation.z] } : null,
            };
          })()`,
          returnByValue: true
        }
      }));
    });
    ws.on('message', msg => {
      console.log('Arm bones:', JSON.stringify(JSON.parse(msg).result.result.value, null, 2));
      ws.close();
    });
  });
});
