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
  if (reqPath === '/favicon.ico') {
    res.writeHead(204);
    res.end();
    return;
  }
  if (reqPath === '/') reqPath = '/mora_character.html';
  const filePath = path.join(assetsDir, reqPath);
  if (!fs.existsSync(filePath)) {
    res.writeHead(404);
    res.end();
    return;
  }
  const ext = path.extname(filePath).toLowerCase();
  res.writeHead(200, { 'Content-Type': mimeTypes[ext] || 'application/octet-stream', 'Access-Control-Allow-Origin': '*' });
  fs.createReadStream(filePath).pipe(res);
});

server.listen(8928, async () => {
  const browserBin = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
  const userDataDir = 'C:\\Users\\Roshan\\AppData\\Local\\Temp\\chrome_mora_speak2';

  const proc = spawn(browserBin, [
    '--remote-debugging-port=9248',
    '--headless=new',
    '--disable-gpu-sandbox',
    '--no-sandbox',
    '--window-size=480,840',
    `--user-data-dir=${userDataDir}`,
    'http://127.0.0.1:8928/mora_character.html'
  ]);

  await new Promise(r => setTimeout(r, 2000));
  const listRes = await fetch('http://127.0.0.1:9248/json');
  const list = await listRes.json();
  const page = list.find(p => p.type === 'page');

  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise(r => ws.on('open', r));

  ws.send(JSON.stringify({ id: 1, method: 'Runtime.enable' }));
  ws.send(JSON.stringify({ id: 2, method: 'Page.enable' }));

  function evalExpr(expr) {
    return new Promise((resolve) => {
      const id = Math.floor(Math.random() * 1000000);
      const handler = (data) => {
        const msg = JSON.parse(data);
        if (msg.id === id) {
          ws.removeListener('message', handler);
          resolve(msg.result);
        }
      };
      ws.on('message', handler);
      ws.send(JSON.stringify({ id, method: 'Runtime.evaluate', params: { expression: expr, returnByValue: true } }));
    });
  }

  function captureScreenshot(filename) {
    return new Promise((resolve) => {
      const id = Math.floor(Math.random() * 1000000);
      const handler = (data) => {
        const msg = JSON.parse(data);
        if (msg.id === id) {
          ws.removeListener('message', handler);
          if (msg.result && msg.result.data) {
            const buf = Buffer.from(msg.result.data, 'base64');
            const dest = path.resolve(__dirname, '../artifacts/mora_enhanced_verification', filename);
            fs.writeFileSync(dest, buf);
            console.log('Saved screenshot:', dest);
          }
          resolve();
        }
      };
      ws.on('message', handler);
      ws.send(JSON.stringify({ id, method: 'Page.captureScreenshot', params: { format: 'png' } }));
    });
  }

  // Poll until controller ready
  for (let i = 0; i < 20; i++) {
    const chk = await evalExpr(`Boolean(window.MoraCompanion && window.MoraCompanion.getController())`);
    if (chk && chk.result && chk.result.value) break;
    await new Promise(r => setTimeout(r, 400));
  }

  // Trigger speaking with voice volume
  console.log('Setting state to speaking...');
  await evalExpr(`window.JarvisOrb.setState("speaking"); window.JarvisOrb.setExternalAudio(0.95);`);

  // Wait until mouth peak opening
  for (let i = 0; i < 25; i++) {
    await new Promise(r => setTimeout(r, 100));
    const vals = await evalExpr(`(() => {
      const c = window.MoraCompanion.getController();
      const getVal = (grp) => grp && grp[0] ? grp[0].mesh.morphTargetInfluences[grp[0].idx] : 0;
      return {
        a: getVal(c.morphMap.mthA),
        o: getVal(c.morphMap.mthO),
        e: getVal(c.morphMap.mthE),
        i: getVal(c.morphMap.mthI)
      };
    })()`);
    if (vals && vals.result && vals.result.value) {
      const { a, o, e } = vals.result.value;
      if (a > 0.15 || o > 0.15 || e > 0.15) {
        console.log('Mouth opening captured:', { a, o, e });
        await captureScreenshot('02_speaking_mouth_open.png');
        break;
      }
    }
  }

  ws.close();
  proc.kill();
  server.close();
  process.exit(0);
});
