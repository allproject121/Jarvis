const http = require('http');
const path = require('path');
const { execSync } = require('child_process');
const WebSocket = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/ws'));

const adb = 'C:\\Android\\Sdk\\platform-tools\\adb.exe';

try {
  const out = execSync(`"${adb}" -s 10BG1D01DA007QG shell cat /proc/net/unix`).toString();
  const match = out.match(/@(webview_devtools_remote_\d+)/);
  if (match) {
    execSync(`"${adb}" -s 10BG1D01DA007QG forward tcp:9222 localabstract:${match[1]}`);
    console.log('Forwarded', match[1], 'to tcp:9222');
  } else {
    console.warn('No webview socket found in cat /proc/net/unix');
  }
} catch (e) {
  console.error('Error during forward:', e.message);
}

setTimeout(() => {
  http.get('http://127.0.0.1:9222/json', (res) => {
    let data = '';
    res.on('data', chunk => data += chunk);
    res.on('end', () => {
      const list = JSON.parse(data);
      console.log('Open webview pages:', list.map(p => ({ title: p.title, url: p.url })));
      const page = list.find(p => p.url && p.url.includes('mora_character.html'));
      if (!page) {
        console.error('mora_character.html page not found in devtools list');
        process.exit(1);
      }
      const ws = new WebSocket(page.webSocketDebuggerUrl);
      ws.on('open', () => {
        console.log('Connected to Mora WebView on device!');
        const evalCmd = {
          id: 1,
          method: 'Runtime.evaluate',
          params: {
            expression: `(() => {
              const ctrl = window.MoraCompanion ? window.MoraCompanion.getController() : null;
              const model = window.MoraCompanion ? window.MoraCompanion.getModel() : null;
              if (!ctrl) return { error: 'No controller yet' };
              const actions = Object.keys(ctrl.actions);
              const activeAction = ctrl.activeActionName;
              const hips = ctrl.bones.hips;
              const hipsPos = hips ? [Number(hips.position.x.toFixed(4)), Number(hips.position.y.toFixed(4)), Number(hips.position.z.toFixed(4))] : null;
              return {
                hasController: true,
                actionsCount: actions.length,
                actions: actions,
                activeAction: activeAction,
                hipsPos: hipsPos,
                hasMorphs: ctrl.hasMorphs,
                morphMeshesCount: ctrl.morphMeshes.length
              };
            })()`,
            returnByValue: true
          }
        };
        ws.send(JSON.stringify(evalCmd));
      });

      ws.on('message', (msg) => {
        const resp = JSON.parse(msg);
        console.log('Runtime eval result:', JSON.stringify(resp.result.result.value, null, 2));
        ws.close();
        process.exit(0);
      });
    });
  }).on('error', (err) => {
    console.error('HTTP error:', err.message);
    process.exit(1);
  });
}, 500);
