const http = require('http');
const path = require('path');
const { execSync } = require('child_process');
const WebSocket = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/ws'));

const adb = 'C:\\Android\\Sdk\\platform-tools\\adb.exe';
const artifactDir = 'C:\\Users\\Roshan\\.gemini\\antigravity\\brain\\027c6672-b4f2-4696-badc-0e02f6ddd130';

function forwardAdb() {
  const out = execSync(`"${adb}" -s 10BG1D01DA007QG shell cat /proc/net/unix`).toString();
  const match = out.match(/@(webview_devtools_remote_\d+)/);
  if (match) {
    execSync(`"${adb}" -s 10BG1D01DA007QG forward tcp:9222 localabstract:${match[1]}`);
    console.log('Forwarded', match[1], 'to tcp:9222');
  }
}

async function evalInMora(ws, code) {
  return new Promise((resolve, reject) => {
    const id = Math.floor(Math.random() * 1000000);
    const handler = (msg) => {
      const resp = JSON.parse(msg);
      if (resp.id === id) {
        ws.removeListener('message', handler);
        resolve(resp.result && resp.result.result ? resp.result.result.value : resp);
      }
    };
    ws.on('message', handler);
    ws.send(JSON.stringify({
      id,
      method: 'Runtime.evaluate',
      params: { expression: code, returnByValue: true }
    }));
  });
}

function captureScreen(filename) {
  const remotePath = `/sdcard/${filename}`;
  const localPath = path.join(artifactDir, filename);
  execSync(`"${adb}" -s 10BG1D01DA007QG shell screencap -p ${remotePath}`);
  execSync(`"${adb}" -s 10BG1D01DA007QG pull ${remotePath} "${localPath}"`);
  console.log('Captured & pulled:', filename);
}

function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

async function run() {
  forwardAdb();
  await sleep(400);

  const page = await new Promise((resolve, reject) => {
    http.get('http://127.0.0.1:9222/json', (res) => {
      let data = ''; res.on('data', c => data += c);
      res.on('end', () => {
        const list = JSON.parse(data);
        const p = list.find(x => x.url && x.url.includes('mora_character.html'));
        if (p) resolve(p); else reject(new Error('Page not found'));
      });
    }).on('error', reject);
  });

  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise(r => ws.on('open', r));
  console.log('WebSocket connected to Mora WebView!');

  const testStates = [
    { name: 'idle', reaction: 'none', state: 'idle', waitMs: 1500, file: 'screen_mora_v3_idle.png' },
    { name: 'wave', reaction: 'wave', state: 'idle', waitMs: 1200, file: 'screen_mora_v3_wave.png' },
    { name: 'joy', reaction: 'joy', state: 'idle', waitMs: 1500, file: 'screen_mora_v3_joy.png' },
    { name: 'dance', reaction: 'dance', state: 'idle', waitMs: 2000, file: 'screen_mora_v3_dance.png' },
    { name: 'thinking', reaction: 'thinking', state: 'thinking', waitMs: 1500, file: 'screen_mora_v3_thinking.png' },
    { name: 'speaking', reaction: 'none', state: 'speaking', waitMs: 1500, file: 'screen_mora_v3_speaking.png' }
  ];

  for (const t of testStates) {
    console.log(`\n=== Testing state: ${t.name} (reaction: ${t.reaction}, state: ${t.state}) ===`);
    const res = await evalInMora(ws, `(() => {
      window.MoraCompanion.setState('${t.state}');
      if ('${t.reaction}' !== 'none') {
        window.MoraCompanion.triggerReaction('${t.reaction}');
      }
      if ('${t.state}' === 'speaking') {
        window.MoraCompanion.setExternalAudio(0.75);
      }
      const ctrl = window.MoraCompanion.getController();
      return {
        activeAction: ctrl.activeActionName,
        running: ctrl.activeAction ? ctrl.activeAction.isRunning() : false,
        weight: ctrl.activeAction ? ctrl.activeAction.getEffectiveWeight() : 0,
        hipsY: ctrl.bones.hips ? ctrl.bones.hips.position.y : 0,
        headPitch: ctrl.bones.head ? ctrl.bones.head.rotation.x : 0
      };
    })()`);
    console.log('State triggered. Controller status:', res);

    await sleep(t.waitMs);
    captureScreen(t.file);
  }

  // Fade back to idle
  await evalInMora(ws, `(() => {
    window.MoraCompanion.setState('idle');
    window.MoraCompanion.setExternalAudio(0);
  })()`);
  console.log('\nReturned to idle.');
  ws.close();
}

run().catch(console.error);
