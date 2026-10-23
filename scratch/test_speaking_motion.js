const path = require('path');
const { execSync } = require('child_process');
const WebSocket = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/ws'));
const http = require('http');

try {
  const adb = 'C:\\Android\\Sdk\\platform-tools\\adb.exe';
  const out = execSync(`"${adb}" -s 10BG1D01DA007QG shell "cat /proc/net/unix | grep webview_devtools"`).toString();
  const match = out.match(/@(webview_devtools_remote_\d+)/);
  if (match) {
    execSync(`"${adb}" -s 10BG1D01DA007QG forward tcp:9222 localabstract:${match[1]}`);
    console.log('Forwarded', match[1]);
  }
} catch (e) {}

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
            window.MoraCompanion.setState('speaking');
            window.MoraCompanion.setExternalAudio(0.9);
            const ctrl = window.MoraCompanion.getController();
            ctrl.fadeToAction('talking_0', 0.2);
            return {
              state: 'speaking',
              action: ctrl.activeActionName,
              mouthA: ctrl.morphMap.mouthA[0] ? ctrl.morphMap.mouthA[0].mesh.morphTargetInfluences[ctrl.morphMap.mouthA[0].idx] : null
            };
          })()`,
          returnByValue: true
        }
      }));
    });
    ws.on('message', msg => {
      console.log('Speaking test:', JSON.parse(msg).result.result.value);
      ws.close();
    });
  });
});
