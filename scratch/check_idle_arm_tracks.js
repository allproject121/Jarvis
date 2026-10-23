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
            const ctrl = window.MoraCompanion.getController();
            const idle = ctrl.actions['idle'];
            if (!idle) return 'no idle';
            const clip = idle.getClip();
            const armTracks = clip.tracks.filter(t => t.name.toLowerCase().includes('upperarm'));
            return {
              totalTracks: clip.tracks.length,
              armTracks: armTracks.map(t => t.name)
            };
          })()`,
          returnByValue: true
        }
      }));
    });
    ws.on('message', msg => {
      console.log('Idle arm tracks:', JSON.stringify(JSON.parse(msg).result.result.value, null, 2));
      ws.close();
    });
  });
});
