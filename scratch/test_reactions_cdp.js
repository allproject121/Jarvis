const http = require('http');
const path = require('path');
const WebSocket = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/ws'));
const { execSync } = require('child_process');

function getPageWsUrl() {
  return new Promise((resolve, reject) => {
    http.get('http://127.0.0.1:9222/json', (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        try {
          const list = JSON.parse(data);
          const page = list.find(p => p.type === 'page' && p.url.includes('orb.html'));
          if (page && page.webSocketDebuggerUrl) resolve(page.webSocketDebuggerUrl);
          else reject(new Error('orb.html not found'));
        } catch (e) { reject(e); }
      });
    }).on('error', reject);
  });
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
  const wsUrl = await getPageWsUrl();
  console.log('Connecting to:', wsUrl);
  const ws = new WebSocket(wsUrl);
  await new Promise(r => ws.on('open', r));
  console.log('Connected to CDP.');

  // 1. Test Wave
  console.log('Testing: triggerReaction("wave")...');
  await evalInPage(ws, 'window.JarvisOrb.triggerReaction("wave")');
  await new Promise(r => setTimeout(r, 600));
  execSync('& "C:\\Android\\Sdk\\platform-tools\\adb.exe" -s 10BG1D01DA007QG shell screencap -p /sdcard/screen_mora_wave.png', { shell: 'powershell.exe' });
  execSync('& "C:\\Android\\Sdk\\platform-tools\\adb.exe" -s 10BG1D01DA007QG pull /sdcard/screen_mora_wave.png C:\\Users\\Roshan\\.gemini\\antigravity\\brain\\027c6672-b4f2-4696-badc-0e02f6ddd130\\screen_mora_wave.png', { shell: 'powershell.exe' });
  console.log('Captured screen_mora_wave.png');

  // 2. Test Speaking + Audio Reactive
  console.log('Testing: setAnimState("speaking") with audio...');
  await evalInPage(ws, 'window.JarvisOrb.setAnimState("speaking"); window.JarvisOrb.setExternalAudio(0.85, 0.7, 0.9);');
  await new Promise(r => setTimeout(r, 800));
  execSync('& "C:\\Android\\Sdk\\platform-tools\\adb.exe" -s 10BG1D01DA007QG shell screencap -p /sdcard/screen_mora_speaking.png', { shell: 'powershell.exe' });
  execSync('& "C:\\Android\\Sdk\\platform-tools\\adb.exe" -s 10BG1D01DA007QG pull /sdcard/screen_mora_speaking.png C:\\Users\\Roshan\\.gemini\\antigravity\\brain\\027c6672-b4f2-4696-badc-0e02f6ddd130\\screen_mora_speaking.png', { shell: 'powershell.exe' });
  console.log('Captured screen_mora_speaking.png');

  // 3. Test Interruption / Barge-in
  console.log('Testing: window.JarvisOrb.interrupt()...');
  await evalInPage(ws, 'window.JarvisOrb.interrupt()');
  await new Promise(r => setTimeout(r, 300));
  const diag = await evalInPage(ws, 'JSON.stringify({ activeAction: customModelEngine ? customModelEngine.activeActionName : null, morphs: customModelEngine ? customModelEngine.hasMorphTargets : false })');
  console.log('After interruption state:', diag.result.value);

  // 4. Test Teach Reaction
  console.log('Testing: triggerReaction("teach")...');
  await evalInPage(ws, 'window.JarvisOrb.triggerReaction("teach")');
  await new Promise(r => setTimeout(r, 600));
  execSync('& "C:\\Android\\Sdk\\platform-tools\\adb.exe" -s 10BG1D01DA007QG shell screencap -p /sdcard/screen_mora_teach.png', { shell: 'powershell.exe' });
  execSync('& "C:\\Android\\Sdk\\platform-tools\\adb.exe" -s 10BG1D01DA007QG pull /sdcard/screen_mora_teach.png C:\\Users\\Roshan\\.gemini\\antigravity\\brain\\027c6672-b4f2-4696-badc-0e02f6ddd130\\screen_mora_teach.png', { shell: 'powershell.exe' });
  console.log('Captured screen_mora_teach.png');

  // Reset to idle
  await evalInPage(ws, 'window.JarvisOrb.setAnimState("idle")');
  ws.close();
  console.log('All tests completed successfully!');
}

run().catch(console.error);
