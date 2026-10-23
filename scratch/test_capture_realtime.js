const { spawn } = require('child_process');
const http = require('http');
const fs = require('fs');
const path = require('path');

const chromePath = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const brainDir = 'C:\\Users\\Roshan\\.gemini\\antigravity\\brain\\027c6672-b4f2-4696-badc-0e02f6ddd130';

function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

async function getWsUrl() {
  for (let i = 0; i < 30; i++) {
    await sleep(500);
    try {
      const res = await new Promise((resolve, reject) => {
        http.get('http://127.0.0.1:9222/json', resp => {
          let data = '';
          resp.on('data', chunk => data += chunk);
          resp.on('end', () => resolve(JSON.parse(data)));
        }).on('error', reject);
      });
      if (res && res[0] && res[0].webSocketDebuggerUrl) {
        return res[0].webSocketDebuggerUrl;
      }
    } catch (e) {}
  }
  throw new Error('Could not connect to Chrome debugging port');
}

async function main() {
  const chrome = spawn(chromePath, [
    '--headless',
    '--remote-debugging-port=9222',
    '--use-gl=angle',
    '--enable-webgl',
    '--window-size=1280,900',
    'http://localhost:8082/lab'
  ]);

  try {
    const wsUrl = await getWsUrl();
    const ws = new globalThis.WebSocket(wsUrl);

    let id = 1;
    const callbacks = new Map();

    ws.addEventListener('message', ev => {
      const msg = JSON.parse(ev.data);
      if (msg.id && callbacks.has(msg.id)) {
        const { resolve, reject } = callbacks.get(msg.id);
        callbacks.delete(msg.id);
        if (msg.error) reject(msg.error);
        else resolve(msg.result);
      }
    });

    await new Promise(r => ws.addEventListener('open', r));

    function send(method, params = {}) {
      return new Promise((resolve, reject) => {
        const curId = id++;
        callbacks.set(curId, { resolve, reject });
        ws.send(JSON.stringify({ id: curId, method, params }));
      });
    }

    await send('Page.enable');
    await send('Runtime.enable');

    // Wait until model is loaded: .lab__note disappears
    console.log('Polling for stage readiness...');
    let ready = false;
    for (let i = 0; i < 40; i++) {
      await sleep(1000);
      const evalRes = await send('Runtime.evaluate', {
        expression: `({
          note: document.querySelector('.lab__note')?.innerText || '',
          canvas: !!document.querySelector('canvas')
        })`,
        returnByValue: true
      });
      const val = evalRes.result?.value;
      console.log('State at', i + 1, 's:', val);
      if (val && val.canvas && !val.note) {
        ready = true;
        break;
      }
    }

    if (!ready) {
      console.error('Timed out waiting for character to load.');
    }

    // Let 1 full second of animation play
    await sleep(1500);

    // Capture 1: Idle
    console.log('Capturing Idle...');
    let snap = await send('Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(path.join(brainDir, 'anima_hinata_1_idle.png'), Buffer.from(snap.data, 'base64'));

    // Capture 2: Wave Gesture
    console.log('Triggering Wave...');
    await send('Runtime.evaluate', {
      expression: `
        const btns = Array.from(document.querySelectorAll('button'));
        const waveBtn = btns.find(b => b.innerText.includes('wave'));
        if (waveBtn) waveBtn.click();
      `
    });
    await sleep(1200);
    snap = await send('Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(path.join(brainDir, 'anima_hinata_2_wave.png'), Buffer.from(snap.data, 'base64'));

    // Capture 3: Talk & Speech Lip-sync
    console.log('Triggering Talk & Lip-sync...');
    await send('Runtime.evaluate', {
      expression: `
        if (window.__stage) {
          window.__stage.audioLevel = 0.92;
        }
        const btns = Array.from(document.querySelectorAll('button'));
        const talkBtn = btns.find(b => b.innerText.includes('talk'));
        if (talkBtn) talkBtn.click();
        const happyBtn = btns.find(b => b.innerText.includes('happy'));
        if (happyBtn) happyBtn.click();
      `
    });
    await sleep(1200);
    snap = await send('Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(path.join(brainDir, 'anima_hinata_3_talk.png'), Buffer.from(snap.data, 'base64'));

    console.log('All 3 verified captures completed successfully!');
    ws.close();
  } finally {
    chrome.kill();
  }
}

main().catch(err => {
  console.error(err);
  process.exit(1);
});
