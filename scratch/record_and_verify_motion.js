const http = require('http');
const path = require('path');
const { spawn, execSync } = require('child_process');
const WebSocket = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/ws'));

const adb = 'C:\\Android\\Sdk\\platform-tools\\adb.exe';
const artifactDir = 'C:\\Users\\Roshan\\.gemini\\antigravity\\brain\\027c6672-b4f2-4696-badc-0e02f6ddd130';

function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

function captureSnapshot(name) {
  const remote = `/sdcard/${name}`;
  const local = path.join(artifactDir, name);
  try {
    execSync(`"${adb}" -s 10BG1D01DA007QG shell screencap -p ${remote}`);
    execSync(`"${adb}" -s 10BG1D01DA007QG pull ${remote} "${local}"`);
    console.log(`[SNAPSHOT] Saved ${name}`);
  } catch (e) {
    console.error(`Snapshot error for ${name}:`, e.message);
  }
}

async function evalInMora(ws, expr) {
  return new Promise((resolve) => {
    const id = Math.floor(Math.random() * 100000);
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

async function main() {
  console.log('1. Ensuring Mora is in foreground...');
  execSync(`"${adb}" -s 10BG1D01DA007QG shell am start -n com.jarvis.assistant/.ui.character.MoraCharacterActivity`);
  await sleep(1000);

  console.log('2. Forwarding DevTools port...');
  const unixList = execSync(`"${adb}" -s 10BG1D01DA007QG shell cat /proc/net/unix`).toString();
  const match = unixList.match(/@(webview_devtools_remote_\d+)/);
  if (!match) throw new Error('Webview devtools not found');
  execSync(`"${adb}" -s 10BG1D01DA007QG forward tcp:9222 localabstract:${match[1]}`);

  const page = await new Promise((resolve, reject) => {
    http.get('http://127.0.0.1:9222/json', (res) => {
      let data = ''; res.on('data', c => data += c);
      res.on('end', () => {
        const list = JSON.parse(data);
        const p = list.find(x => x.url && x.url.includes('mora_character.html'));
        if (p) resolve(p); else reject(new Error('Mora page not found'));
      });
    }).on('error', reject);
  });

  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise(r => ws.on('open', r));
  console.log('Connected to Mora WebView on device via WebSocket!');

  console.log('3. Starting background screenrecord on phone (16s)...');
  const recordProc = spawn(adb, [
    '-s', '10BG1D01DA007QG',
    'shell', 'screenrecord',
    '--size', '720x1600',
    '--bit-rate', '4000000',
    '--time-limit', '16',
    '/sdcard/mora_motion_live.mp4'
  ]);

  recordProc.on('error', (err) => console.error('Record proc error:', err));
  await sleep(1000);

  // --- Phase 1: Idle (0 - 3s) ---
  console.log('\n--- Phase 1: Natural Anatomical Idle ---');
  await evalInMora(ws, `window.MoraCompanion.setState('idle')`);
  await sleep(1500);
  captureSnapshot('mora_live_idle.png');

  // --- Phase 2: Wave (3 - 6s) ---
  console.log('\n--- Phase 2: Wave Greeting ---');
  await evalInMora(ws, `window.MoraCompanion.triggerReaction('wave')`);
  await sleep(1500);
  captureSnapshot('mora_live_wave.png');

  // --- Phase 3: Joy (6 - 9s) ---
  console.log('\n--- Phase 3: Joyous Celebration ---');
  await evalInMora(ws, `window.MoraCompanion.triggerReaction('joy')`);
  await sleep(1500);
  captureSnapshot('mora_live_joy.png');

  // --- Phase 4: Dance (9 - 12s) ---
  console.log('\n--- Phase 4: Full-Body Hip-Hop Dance ---');
  await evalInMora(ws, `window.MoraCompanion.triggerReaction('dance')`);
  await sleep(1500);
  captureSnapshot('mora_live_dance.png');

  // --- Phase 5: Thinking (12 - 14s) ---
  console.log('\n--- Phase 5: Contemplative Thinking Pose ---');
  await evalInMora(ws, `window.MoraCompanion.setState('thinking'); window.MoraCompanion.triggerReaction('thinking');`);
  await sleep(1200);
  captureSnapshot('mora_live_thinking.png');

  // --- Phase 6: Speaking Lip-Sync (14 - 16s) ---
  console.log('\n--- Phase 6: Real-Time Audio Speaking & Lip-Sync ---');
  await evalInMora(ws, `window.MoraCompanion.setState('speaking'); window.MoraCompanion.setExternalAudio(0.85);`);
  await sleep(1200);
  captureSnapshot('mora_live_speaking.png');

  // Reset to idle
  await evalInMora(ws, `window.MoraCompanion.setState('idle'); window.MoraCompanion.setExternalAudio(0);`);
  ws.close();

  console.log('4. Waiting for video recording to finalize on device...');
  await new Promise(r => recordProc.on('close', r));
  await sleep(1000);

  console.log('5. Pulling recorded MP4 from device...');
  execSync(`"${adb}" -s 10BG1D01DA007QG pull /sdcard/mora_motion_live.mp4 "${path.join(artifactDir, 'mora_motion_live.mp4')}"`);
  console.log('Successfully pulled mora_motion_live.mp4!');
}

main().catch(console.error);
