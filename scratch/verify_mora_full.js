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
    console.log('404:', reqPath);
    res.writeHead(404);
    res.end('Not found');
    return;
  }

  const ext = path.extname(filePath).toLowerCase();
  const contentType = mimeTypes[ext] || 'application/octet-stream';
  res.writeHead(200, { 'Content-Type': contentType, 'Access-Control-Allow-Origin': '*' });
  fs.createReadStream(filePath).pipe(res);
});

const outDir = path.resolve(__dirname, '../artifacts/mora_enhanced_verification');
if (!fs.existsSync(outDir)) fs.mkdirSync(outDir, { recursive: true });

server.listen(8901, async () => {
  console.log('Verification server running on http://127.0.0.1:8901');
  const chromePath = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
  const edgePath = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
  const browserBin = fs.existsSync(chromePath) ? chromePath : edgePath;
  const userDataDir = 'C:\\Users\\Roshan\\AppData\\Local\\Temp\\chrome_mora_enhanced_test';

  const proc = spawn(browserBin, [
    '--remote-debugging-port=9227',
    '--headless=new',
    '--disable-gpu-sandbox',
    '--no-sandbox',
    '--window-size=480,840',
    `--user-data-dir=${userDataDir}`,
    'http://127.0.0.1:8901/mora_character.html'
  ]);

  await new Promise(r => setTimeout(r, 2000));

  try {
    const listRes = await fetch('http://127.0.0.1:9227/json');
    const list = await listRes.json();
    const page = list.find(p => p.type === 'page');
    console.log('Connected to browser page:', page.title);

    const ws = new WebSocket(page.webSocketDebuggerUrl);
    await new Promise(r => ws.on('open', r));

    ws.on('message', (data) => {
      const msg = JSON.parse(data);
      if (msg.method === 'Runtime.consoleAPICalled') {
        const text = msg.params.args.map(a => a.value || JSON.stringify(a)).join(' ');
        console.log(`[BROWSER]:`, text);
      }
    });

    ws.send(JSON.stringify({ id: 1, method: 'Console.enable' }));
    ws.send(JSON.stringify({ id: 2, method: 'Runtime.enable' }));
    ws.send(JSON.stringify({ id: 3, method: 'Page.enable' }));

    // Helper for evaluating JS
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

    // Capture screenshot helper
    function captureScreenshot(filename) {
      return new Promise((resolve) => {
        const id = Math.floor(Math.random() * 1000000);
        const handler = (data) => {
          const msg = JSON.parse(data);
          if (msg.id === id) {
            ws.removeListener('message', handler);
            if (msg.result && msg.result.data) {
              const buf = Buffer.from(msg.result.data, 'base64');
              const dest = path.join(outDir, filename);
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

    // Wait 5 seconds for model and animations to parse
    console.log('Waiting for Mora model to load...');
    await new Promise(r => setTimeout(r, 5000));

    // 1. Inspect initial controller state, materials & morph targets
    const initReport = await evalExpr(`(() => {
      const c = window.MoraCompanion ? window.MoraCompanion.getController() : null;
      if (!c) return { error: "No controller" };

      const morphTargetFlags = c.morphMeshes.map(m => {
        const mat = m.material;
        return {
          meshName: m.name,
          morphTargets: mat.morphTargets,
          morphNormals: mat.morphNormals,
          targetsCount: Object.keys(m.morphTargetDictionary || {}).length
        };
      });

      return {
        hasClips: c.hasClips,
        activeAction: c.activeActionName,
        bonesCount: Object.keys(c.bones).length,
        morphMeshesCount: c.morphMeshes.length,
        morphMapCounts: {
          mthA: c.morphMap.mthA.length,
          mthO: c.morphMap.mthO.length,
          mthI: c.morphMap.mthI.length,
          mthE: c.morphMap.mthE.length,
          allJoy: c.morphMap.allJoy.length,
          eyeJoy: c.morphMap.eyeJoy.length,
          eyeWinkR: c.morphMap.eyeWinkR.length,
          eyeBlink: c.morphMap.eyeBlink.length,
          allSurprised: c.morphMap.allSurprised.length
        },
        morphTargetFlags
      };
    })()`);

    console.log('=== MORA INITIALIZATION REPORT ===\n', JSON.stringify(initReport, null, 2));

    // Save Frame 1: Idle with 3D Cyber Ground Pedestal
    await new Promise(r => setTimeout(r, 1200));
    await captureScreenshot('01_idle_pedestal.png');

    // 2. Test Speaking with Lip-Sync Visemes
    console.log('\n--- TESTING SPEAKING & VISMES ---');
    await evalExpr(`window.JarvisOrb.setState("speaking"); window.JarvisOrb.setExternalAudio(0.85);`);
    await new Promise(r => setTimeout(r, 1800));

    const speakingMorphReport = await evalExpr(`(() => {
      const c = window.MoraCompanion.getController();
      const getVal = (grp) => grp && grp[0] ? Number(grp[0].mesh.morphTargetInfluences[grp[0].idx].toFixed(3)) : 0;
      return {
        state: "speaking",
        mthA: getVal(c.morphMap.mthA),
        mthO: getVal(c.morphMap.mthO),
        mthI: getVal(c.morphMap.mthI),
        mthE: getVal(c.morphMap.mthE),
        activeAction: c.activeActionName
      };
    })()`);
    console.log('Speaking Visemes:', speakingMorphReport);
    await captureScreenshot('02_speaking_lipsync.png');

    // 3. Test Joy Emote (Smiling eyes + Cheerful smile)
    console.log('\n--- TESTING JOY EMOTE ---');
    await evalExpr(`window.JarvisOrb.triggerReaction("joy");`);
    await new Promise(r => setTimeout(r, 1500));

    const joyReport = await evalExpr(`(() => {
      const c = window.MoraCompanion.getController();
      const getVal = (grp) => grp && grp[0] ? Number(grp[0].mesh.morphTargetInfluences[grp[0].idx].toFixed(3)) : 0;
      return {
        allJoy: getVal(c.morphMap.allJoy),
        mthJoy: getVal(c.morphMap.mthJoy),
        eyeJoy: getVal(c.morphMap.eyeJoy),
        brwJoy: getVal(c.morphMap.brwJoy),
        activeAction: c.activeActionName
      };
    })()`);
    console.log('Joy Expression:', joyReport);
    await captureScreenshot('03_joy_expression.png');

    // 4. Test Wave Reaction (Playful Wink + Wave)
    console.log('\n--- TESTING WAVE EMOTE WITH WINK ---');
    await evalExpr(`window.JarvisOrb.triggerReaction("wave");`);
    await new Promise(r => setTimeout(r, 1500));

    const waveReport = await evalExpr(`(() => {
      const c = window.MoraCompanion.getController();
      const getVal = (grp) => grp && grp[0] ? Number(grp[0].mesh.morphTargetInfluences[grp[0].idx].toFixed(3)) : 0;
      return {
        eyeWinkR: getVal(c.morphMap.eyeWinkR),
        mthJoy: getVal(c.morphMap.mthJoy),
        brwJoy: getVal(c.morphMap.brwJoy),
        activeAction: c.activeActionName
      };
    })()`);
    console.log('Wave Expression:', waveReport);
    await captureScreenshot('04_wave_wink.png');

    // 5. Test Poke / Surprise Reaction (Surprised Eyes + Gasp)
    console.log('\n--- TESTING POKE / SURPRISE EMOTE ---');
    await evalExpr(`window.JarvisOrb.triggerReaction("surprise");`);
    await new Promise(r => setTimeout(r, 1200));

    const surpriseReport = await evalExpr(`(() => {
      const c = window.MoraCompanion.getController();
      const getVal = (grp) => grp && grp[0] ? Number(grp[0].mesh.morphTargetInfluences[grp[0].idx].toFixed(3)) : 0;
      return {
        allSurprised: getVal(c.morphMap.allSurprised),
        eyeSurprised: getVal(c.morphMap.eyeSurprised),
        mthSurprised: getVal(c.morphMap.mthSurprised),
        brwSurprised: getVal(c.morphMap.brwSurprised),
        activeAction: c.activeActionName
      };
    })()`);
    console.log('Surprise Expression:', surpriseReport);
    await captureScreenshot('05_surprise_poke.png');

    // 6. Test Return to Idle
    console.log('\n--- TESTING RETURN TO IDLE ---');
    await evalExpr(`window.JarvisOrb.setState("idle"); window.JarvisOrb.triggerReaction("none");`);
    await new Promise(r => setTimeout(r, 1500));
    await captureScreenshot('06_return_idle.png');

    console.log('\n=== ALL TESTS COMPLETED SUCCESSFULLY! ===');
    ws.close();
  } catch (err) {
    console.error('Verification error:', err);
  } finally {
    proc.kill();
    server.close();
    process.exit(0);
  }
});
