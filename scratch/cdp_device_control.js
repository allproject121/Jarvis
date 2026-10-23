const path = require('path');
const WebSocket = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/ws'));
const http = require('http');

async function getWsUrl() {
  return new Promise((resolve, reject) => {
    http.get('http://127.0.0.1:9222/json', (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        const list = JSON.parse(data);
        const page = list.find(p => p.type === 'page' && p.url.includes('orb.html'));
        if (page) resolve(page.webSocketDebuggerUrl);
        else reject(new Error('orb.html page not found'));
      });
    }).on('error', reject);
  });
}

function sendCommand(ws, method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = Math.floor(Math.random() * 100000);
    const handler = (msg) => {
      const data = JSON.parse(msg);
      if (data.id === id) {
        ws.removeListener('message', handler);
        if (data.error) reject(data.error);
        else resolve(data.result);
      }
    };
    ws.on('message', handler);
    ws.send(JSON.stringify({ id, method, params }));
  });
}

async function evalJs(ws, expression) {
  const res = await sendCommand(ws, 'Runtime.evaluate', {
    expression,
    returnByValue: true
  });
  console.log('Raw eval result:', JSON.stringify(res));
  return res.result ? res.result.value : res;
}

async function main() {
  const wsUrl = await getWsUrl();
  console.log('Connecting to:', wsUrl);
  const ws = new WebSocket(wsUrl);

  await new Promise(res => ws.on('open', res));
  console.log('Connected to WebView CDP!');

  const info = await evalJs(ws, `
    ({
      category: window.Jarvis3DModelBridge ? window.Jarvis3DModelBridge.getModelCategory() : null,
      diagnostics: window.Jarvis3DModelBridge ? window.Jarvis3DModelBridge.getModelDiagnostics() : null
    })
  `);

  console.log('Current WebView 3D State:');
  console.log(JSON.stringify(info, null, 2));

  ws.close();
}

main().catch(console.error);
