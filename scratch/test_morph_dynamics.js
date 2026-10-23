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

server.listen(8910, async () => {
  const browserBin = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
  const userDataDir = 'C:\\Users\\Roshan\\AppData\\Local\\Temp\\chrome_mora_test_morphs';

  const proc = spawn(browserBin, [
    '--remote-debugging-port=9235',
    '--headless=new',
    '--disable-gpu-sandbox',
    '--no-sandbox',
    '--window-size=480,840',
    `--user-data-dir=${userDataDir}`,
    'http://127.0.0.1:8910/mora_character.html'
  ]);

  await new Promise(r => setTimeout(r, 2000));
  const listRes = await fetch('http://127.0.0.1:9235/json');
  const list = await listRes.json();
  const page = list.find(p => p.type === 'page');

  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise(r => ws.on('open', r));

  ws.send(JSON.stringify({ id: 1, method: 'Runtime.enable' }));
  ws.send(JSON.stringify({ id: 2, method: 'Page.enable' }));

  // Helper for evaluating JS
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

  // Poll until moraController is loaded
  let loaded = false;
  for (let i = 0; i < 30; i++) {
    const chk = await evalExpr(`Boolean(window.MoraCompanion && window.MoraCompanion.getController())`);
    if (chk && chk.result && chk.result.value === true) {
      loaded = true;
      console.log('Mora controller loaded after', (i + 1) * 0.5, 'seconds');
      break;
    }
    await new Promise(r => setTimeout(r, 500));
  }

  if (!loaded) {
    console.error('Mora controller failed to load within 15 seconds!');
    ws.close(); proc.kill(); server.close(); process.exit(1);
  }

  // 1. Inspect Controller morph mappings
  const report1 = await evalExpr(`(() => {
    const c = window.MoraCompanion.getController();
    return {
      bones: Object.keys(c.bones).length,
      morphMeshes: c.morphMeshes.length,
      categories: Object.fromEntries(Object.entries(c.morphMap).map(([k, v]) => [k, v.length]))
    };
  })()`);
  console.log('\n--- CONTROLLER MORPH REPORT ---');
  console.log(JSON.stringify(report1.result.value, null, 2));

  // 2. Trigger speaking and watch mouth influences over 1.5s
  console.log('\n--- TESTING SPEAKING VISMES ---');
  await evalExpr(`window.JarvisOrb.setState("speaking"); window.JarvisOrb.setExternalAudio(0.95);`);

  // Sample values at 3 points in time
  for (let s = 1; s <= 3; s++) {
    await new Promise(r => setTimeout(r, 350));
    const sample = await evalExpr(`(() => {
      const c = window.MoraCompanion.getController();
      const getVal = (grp) => grp && grp[0] ? Number(grp[0].mesh.morphTargetInfluences[grp[0].idx].toFixed(3)) : 0;
      return {
        step: ${s},
        action: c.activeActionName,
        mthA: getVal(c.morphMap.mthA),
        mthO: getVal(c.morphMap.mthO),
        mthI: getVal(c.morphMap.mthI),
        mthE: getVal(c.morphMap.mthE)
      };
    })()`);
    console.log('Speaking Sample', s, ':', sample.result.value);
  }

  // 3. Test Joy reaction
  console.log('\n--- TESTING JOY REACTION ---');
  await evalExpr(`window.JarvisOrb.triggerReaction("joy");`);
  await new Promise(r => setTimeout(r, 500));
  const joyRes = await evalExpr(`(() => {
    const c = window.MoraCompanion.getController();
    const getVal = (grp) => grp && grp[0] ? Number(grp[0].mesh.morphTargetInfluences[grp[0].idx].toFixed(3)) : 0;
    return {
      reaction: "joy",
      action: c.activeActionName,
      allJoy: getVal(c.morphMap.allJoy),
      mthJoy: getVal(c.morphMap.mthJoy),
      eyeJoy: getVal(c.morphMap.eyeJoy),
      brwJoy: getVal(c.morphMap.brwJoy)
    };
  })()`);
  console.log('Joy Expression Sample:', joyRes.result.value);

  // 4. Test Wave reaction
  console.log('\n--- TESTING WAVE REACTION ---');
  await evalExpr(`window.JarvisOrb.triggerReaction("wave");`);
  await new Promise(r => setTimeout(r, 500));
  const waveRes = await evalExpr(`(() => {
    const c = window.MoraCompanion.getController();
    const getVal = (grp) => grp && grp[0] ? Number(grp[0].mesh.morphTargetInfluences[grp[0].idx].toFixed(3)) : 0;
    return {
      reaction: "wave",
      action: c.activeActionName,
      eyeWinkR: getVal(c.morphMap.eyeWinkR),
      mthJoy: getVal(c.morphMap.mthJoy)
    };
  })()`);
  console.log('Wave Expression Sample:', waveRes.result.value);

  // 5. Test Surprise reaction
  console.log('\n--- TESTING SURPRISE REACTION ---');
  await evalExpr(`window.JarvisOrb.triggerReaction("surprise");`);
  await new Promise(r => setTimeout(r, 500));
  const surpRes = await evalExpr(`(() => {
    const c = window.MoraCompanion.getController();
    const getVal = (grp) => grp && grp[0] ? Number(grp[0].mesh.morphTargetInfluences[grp[0].idx].toFixed(3)) : 0;
    return {
      reaction: "surprise",
      action: c.activeActionName,
      allSurprised: getVal(c.morphMap.allSurprised),
      eyeSurprised: getVal(c.morphMap.eyeSurprised),
      mthSurprised: getVal(c.morphMap.mthSurprised)
    };
  })()`);
  console.log('Surprise Expression Sample:', surpRes.result.value);

  ws.close();
  proc.kill();
  server.close();
  process.exit(0);
});
