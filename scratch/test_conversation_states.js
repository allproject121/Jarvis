const http = require('http');
const path = require('path');
const WebSocket = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/ws'));
const { execSync } = require('child_process');

const adb = 'C:\\Android\\Sdk\\platform-tools\\adb.exe';
const artifactDir = 'C:\\Users\\Roshan\\.gemini\\antigravity\\brain\\027c6672-b4f2-4696-badc-0e02f6ddd130';

function capture(filename) {
  execSync(`"${adb}" -s 10BG1D01DA007QG shell screencap -p /sdcard/${filename}`);
  execSync(`"${adb}" -s 10BG1D01DA007QG pull /sdcard/${filename} "${path.join(artifactDir, filename)}"`);
  console.log('Saved:', filename);
}

function evalInPage(ws, expression) {
  return new Promise((resolve) => {
    const id = Math.floor(Math.random() * 100000);
    const handler = (data) => {
      const msg = JSON.parse(data);
      if (msg.id === id) {
        ws.removeListener('message', handler);
        resolve(msg.result);
      }
    };
    ws.on('message', handler);
    ws.send(JSON.stringify({ id, method: 'Runtime.evaluate', params: { expression, returnByValue: true } }));
  });
}

async function run() {
  try {
    const out = execSync(`"${adb}" -s 10BG1D01DA007QG shell cat /proc/net/unix`).toString();
    const match = out.match(/@(webview_devtools_remote_\d+)/);
    if (match) {
      execSync(`"${adb}" -s 10BG1D01DA007QG forward tcp:9222 localabstract:${match[1]}`);
      console.log('Forwarded', match[1]);
    }
  } catch (e) { console.error('Forward err:', e.message); }

  const page = await new Promise((resolve, reject) => {
    http.get('http://127.0.0.1:9222/json', (res) => {
      let data = ''; res.on('data', c => data += c);
      res.on('end', () => {
        const list = JSON.parse(data);
        const p = list.find(x => x.type === 'page' && x.url.includes('mora_character.html'));
        if (p) resolve(p); else reject(new Error('Mora page not found'));
      });
    }).on('error', reject);
  });

  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise(r => ws.on('open', r));
  console.log('Connected to Mora WebGL page.');

  // 1. Test LISTENING
  console.log('--- Setting LISTENING state ---');
  await evalInPage(ws, `window.MoraCompanion.setState('listening')`);
  await new Promise(r => setTimeout(r, 600));
  capture('screen_mora_state_listening.png');

  // 2. Test THINKING
  console.log('--- Setting THINKING state ---');
  await evalInPage(ws, `window.MoraCompanion.setState('thinking')`);
  await new Promise(r => setTimeout(r, 600));
  capture('screen_mora_state_thinking.png');

  // 3. Test SPEAKING + Audio Reactive Lip Sync
  console.log('--- Setting SPEAKING state with active audio amplitude ---');
  await evalInPage(ws, `
    window.MoraCompanion.setState('speaking');
    window.MoraCompanion.setExternalAudio(0.85);
  `);
  await new Promise(r => setTimeout(r, 800));
  capture('screen_mora_state_speaking.png');

  // Verify mouth morph influences are positive
  const morphDiag = await evalInPage(ws, `(() => {
    const ctrl = window.MoraCompanion.getController();
    const mouthAInfl = ctrl.morphMap.mouthA.map(m => m.mesh.morphTargetInfluences[m.idx]);
    return {
      activeAction: ctrl.activeActionName,
      mouthAValues: mouthAInfl
    };
  })()`);
  console.log('Speaking Morph & Action Diagnosis:', morphDiag.result.value);

  // 4. Test BARGE-IN INTERRUPTION
  console.log('--- Triggering BARGE-IN INTERRUPTION ---');
  await evalInPage(ws, `window.MoraCompanion.interrupt()`);
  await new Promise(r => setTimeout(r, 400));
  capture('screen_mora_state_interrupted.png');

  const afterInterruptDiag = await evalInPage(ws, `(() => {
    const ctrl = window.MoraCompanion.getController();
    const mouthAInfl = ctrl.morphMap.mouthA.map(m => m.mesh.morphTargetInfluences[m.idx]);
    return {
      activeAction: ctrl.activeActionName,
      mouthAValues: mouthAInfl
    };
  })()`);
  console.log('After Interrupt Diagnosis:', afterInterruptDiag.result.value);

  // Reset to idle
  await evalInPage(ws, `window.MoraCompanion.setState('idle')`);
  ws.close();
  console.log('State verification suite complete!');
}

run().catch(console.error);
