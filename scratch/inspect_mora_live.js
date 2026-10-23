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
    res.writeHead(404);
    res.end('Not found');
    return;
  }
  const ext = path.extname(filePath).toLowerCase();
  res.writeHead(200, { 'Content-Type': mimeTypes[ext] || 'application/octet-stream', 'Access-Control-Allow-Origin': '*' });
  fs.createReadStream(filePath).pipe(res);
});

server.listen(8905, async () => {
  const browserBin = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
  const userDataDir = 'C:\\Users\\Roshan\\AppData\\Local\\Temp\\chrome_mora_inspect';

  const proc = spawn(browserBin, [
    '--remote-debugging-port=9230',
    '--headless=new',
    '--disable-gpu-sandbox',
    '--no-sandbox',
    `--user-data-dir=${userDataDir}`,
    'http://127.0.0.1:8905/mora_character.html'
  ]);

  await new Promise(r => setTimeout(r, 2000));
  const listRes = await fetch('http://127.0.0.1:9230/json');
  const list = await listRes.json();
  const page = list.find(p => p.type === 'page');

  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise(r => ws.on('open', r));

  ws.send(JSON.stringify({ id: 1, method: 'Runtime.enable' }));

  // Wait 7 seconds for model to load fully
  await new Promise(r => setTimeout(r, 7000));

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

  const res1 = await evalExpr(`(() => {
    try {
      const c = window.MoraCompanion ? window.MoraCompanion.getController() : null;
      if (!c) return { hasController: false };
      return {
        hasController: true,
        bonesCount: Object.keys(c.bones).length,
        morphMeshes: c.morphMeshes.length,
        morphMapKeys: Object.keys(c.morphMap).map(k => k + ':' + c.morphMap[k].length)
      };
    } catch (e) {
      return { error: e.toString() };
    }
  })()`);

  console.log('Result 1 (Controller Inspection):', JSON.stringify(res1, null, 2));

  // Now trigger speaking and check morph target values over 1 second
  await evalExpr(`window.JarvisOrb.setState("speaking"); window.JarvisOrb.setExternalAudio(0.9);`);
  await new Promise(r => setTimeout(r, 800));

  const res2 = await evalExpr(`(() => {
    try {
      const c = window.MoraCompanion.getController();
      const report = {};
      for (const [k, grp] of Object.entries(c.morphMap)) {
        if (grp && grp.length > 0) {
          report[k] = grp[0].mesh.morphTargetInfluences[grp[0].idx];
        }
      }
      return {
        state: "speaking",
        action: c.activeActionName,
        morphs: report
      };
    } catch (e) {
      return { error: e.toString() };
    }
  })()`);

  console.log('\nResult 2 (Speaking Morphs):', JSON.stringify(res2, null, 2));

  // Now trigger joy and check
  await evalExpr(`window.JarvisOrb.triggerReaction("joy");`);
  await new Promise(r => setTimeout(r, 600));

  const res3 = await evalExpr(`(() => {
    try {
      const c = window.MoraCompanion.getController();
      const report = {};
      for (const [k, grp] of Object.entries(c.morphMap)) {
        if (grp && grp.length > 0) {
          report[k] = grp[0].mesh.morphTargetInfluences[grp[0].idx];
        }
      }
      return {
        reaction: "joy",
        action: c.activeActionName,
        morphs: report
      };
    } catch (e) {
      return { error: e.toString() };
    }
  })()`);

  console.log('\nResult 3 (Joy Morphs):', JSON.stringify(res3, null, 2));

  ws.close();
  proc.kill();
  server.close();
  process.exit(0);
});
