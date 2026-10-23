const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const WebSocket = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/ws'));

const assetsDir = path.resolve(__dirname, '../app/src/main/assets');

const mimeTypes = {
  '.html': 'text/html',
  '.js': 'application/javascript',
  '.json': 'application/json',
  '.glb': 'model/gltf-binary',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
};

const server = http.createServer((req, res) => {
  let reqPath = req.url.split('?')[0];
  if (reqPath === '/') reqPath = '/mora_character.html';
  const filePath = path.join(assetsDir, reqPath);

  if (!fs.existsSync(filePath)) {
    console.log('404 NOT FOUND:', reqPath);
    res.writeHead(404);
    res.end('Not found');
    return;
  }

  const ext = path.extname(filePath).toLowerCase();
  const contentType = mimeTypes[ext] || 'application/octet-stream';
  res.writeHead(200, { 'Content-Type': contentType, 'Access-Control-Allow-Origin': '*' });
  fs.createReadStream(filePath).pipe(res);
});

server.listen(8899, async () => {
  console.log('Testing server running on http://127.0.0.1:8899');
  const chromePath = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
  const edgePath = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
  const browserBin = fs.existsSync(chromePath) ? chromePath : edgePath;
  const userDataDir = 'C:\\Users\\Roshan\\AppData\\Local\\Temp\\chrome_mora_test';

  const proc = spawn(browserBin, [
    '--remote-debugging-port=9225',
    '--headless=new',
    '--disable-gpu-sandbox',
    '--no-sandbox',
    `--user-data-dir=${userDataDir}`,
    'http://127.0.0.1:8899/mora_character.html'
  ]);

  await new Promise(r => setTimeout(r, 2000));

  try {
    const listRes = await fetch('http://127.0.0.1:9225/json');
    const list = await listRes.json();
    const page = list.find(p => p.type === 'page');
    console.log('Connected to browser page:', page.title);

    const ws = new WebSocket(page.webSocketDebuggerUrl);
    await new Promise(r => ws.on('open', r));

    ws.on('message', (data) => {
      const msg = JSON.parse(data);
      if (msg.method === 'Runtime.consoleAPICalled') {
        const text = msg.params.args.map(a => a.value || JSON.stringify(a)).join(' ');
        console.log(`[BROWSER CONSOLE ${msg.params.type.toUpperCase()}]:`, text);
      }
      if (msg.method === 'Runtime.exceptionThrown') {
        console.error('[BROWSER EXCEPTION]:', msg.params.exceptionDetails.text, msg.params.exceptionDetails.exception);
      }
    });

    ws.send(JSON.stringify({ id: 1, method: 'Console.enable' }));
    ws.send(JSON.stringify({ id: 2, method: 'Runtime.enable' }));

    // Wait 8 seconds to allow model and animations to load
    await new Promise(r => setTimeout(r, 8000));

    function evalExpr(expr) {
      return new Promise((resolve) => {
        const id = Math.floor(Math.random() * 1000000);
        const handler = (data) => {
          const msg = JSON.parse(data);
          if (msg.id === id) {
            ws.removeListener('message', handler);
            resolve(msg.result && msg.result.result ? msg.result.result.value : null);
          }
        };
        ws.on('message', handler);
        ws.send(JSON.stringify({ id, method: 'Runtime.evaluate', params: { expression: expr, returnByValue: true } }));
      });
    }

    const stateInfo = await evalExpr(`(() => {
      const c = window.MoraCompanion ? window.MoraCompanion.getController() : null;
      if (!c) return { hasController: false };
      return {
        hasController: true,
        hasClips: c.hasClips,
        actions: Object.keys(c.actions),
        activeActionName: c.activeActionName,
        bonesCount: Object.keys(c.bones).length,
        morphsCount: c.morphMeshes.length
      };
    })()`);

    console.log('Mora State in Browser:', JSON.stringify(stateInfo, null, 2));

    ws.close();
  } catch (e) {
    console.error('Test error:', e);
  } finally {
    proc.kill();
    server.close();
    process.exit(0);
  }
});
