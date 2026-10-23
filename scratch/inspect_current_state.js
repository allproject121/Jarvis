const path = require('path');
const { execSync } = require('child_process');
const WebSocket = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/ws'));
const http = require('http');

const adb = 'C:\\Android\\Sdk\\platform-tools\\adb.exe';
const out = execSync(`"${adb}" -s 10BG1D01DA007QG shell "cat /proc/net/unix | grep webview_devtools"`).toString();
const match = out.match(/@(webview_devtools_remote_\d+)/);
if (match) {
  execSync(`"${adb}" -s 10BG1D01DA007QG forward tcp:9222 localabstract:${match[1]}`);
  console.log('Forwarded', match[1]);
}

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
            const hips = m.getObjectByName('J_Bip_C_Hips');
            const head = m.getObjectByName('J_Bip_C_Head');
            const ctrl = window.MoraCompanion.getController();
            return {
              modelRotY: m.rotation.y,
              hipsQuat: hips ? [hips.quaternion.x, hips.quaternion.y, hips.quaternion.z, hips.quaternion.w] : null,
              headQuat: head ? [head.quaternion.x, head.quaternion.y, head.quaternion.z, head.quaternion.w] : null,
              activeActionName: ctrl.activeActionName,
              activeActionRunning: ctrl.activeAction ? ctrl.activeAction.isRunning() : false,
              actionsLoaded: Object.keys(ctrl.actions)
            };
          })()`,
          returnByValue: true
        }
      }));
    });
    ws.on('message', msg => {
      console.log('Current state:', JSON.stringify(JSON.parse(msg).result.result.value, null, 2));
      ws.close();
    });
  });
});
