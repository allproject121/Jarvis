const http = require('http');
const path = require('path');
const WebSocket = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/ws'));
const { execSync } = require('child_process');

const adb = 'C:\\Android\\Sdk\\platform-tools\\adb.exe';
try {
  const out = execSync(`"${adb}" -s 10BG1D01DA007QG shell cat /proc/net/unix`).toString();
  const match = out.match(/@(webview_devtools_remote_\d+)/);
  if (match) {
    execSync(`"${adb}" -s 10BG1D01DA007QG forward tcp:9222 localabstract:${match[1]}`);
    console.log('Forwarded', match[1]);
  }
} catch (e) { console.error('Forward err:', e.message); }

http.get('http://127.0.0.1:9222/json', (res) => {
  let data = ''; res.on('data', c => data += c);
  res.on('end', () => {
    const list = JSON.parse(data);
    const page = list.find(p => p.type === 'page' && p.url.includes('mora_character.html'));
    if (!page) { console.log('Mora page not found', list); return; }
    const ws = new WebSocket(page.webSocketDebuggerUrl);
    ws.on('open', () => {
      ws.send(JSON.stringify({
        id: 1, method: 'Runtime.evaluate',
        params: {
          expression: `(() => {
            const ctrl = window.MoraCompanion ? window.MoraCompanion.getController() : null;
            const model = window.MoraCompanion ? window.MoraCompanion.getModel() : null;
            const hips = model ? model.getObjectByName('J_Bip_C_Hips') : null;
            const cam = window._cam || null;
            return {
              hasCtrl: !!ctrl,
              hasModel: !!model,
              modelVisible: model ? model.visible : false,
              modelParent: model && model.parent ? model.parent.type : null,
              modelPos: model ? model.position : null,
              modelScale: model ? model.scale : null,
              hipsPos: hips ? hips.position : null,
              hipsWorldPos: hips ? hips.getWorldPosition(new THREE.Vector3()) : null,
              activeAction: ctrl ? ctrl.activeActionName : null,
              actionRunning: ctrl && ctrl.activeAction ? ctrl.activeAction.isRunning() : false,
              actionWeight: ctrl && ctrl.activeAction ? ctrl.activeAction.getEffectiveWeight() : 0
            };
          })()`,
          returnByValue: true
        }
      }));
    });
    ws.on('message', msg => {
      console.log('Mora scene state:', JSON.parse(msg).result.result.value);
      ws.close();
    });
  });
});
