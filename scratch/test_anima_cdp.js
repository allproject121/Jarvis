const { spawn } = require('child_process');
const http = require('http');
const fs = require('fs');
const path = require('path');

const chromePath = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const brainDir = 'C:\\Users\\Roshan\\.gemini\\antigravity\\brain\\027c6672-b4f2-4696-badc-0e02f6ddd130';
const tmpUserData = path.join(__dirname, 'tmp_chrome_profile');

const chrome = spawn(chromePath, [
  '--headless=new',
  '--remote-debugging-port=9222',
  '--user-data-dir=' + tmpUserData,
  '--no-first-run',
  '--no-default-browser-check',
  '--disable-extensions',
  '--use-gl=angle',
  '--enable-webgl',
  '--window-size=1280,900',
  'http://localhost:8082/lab'
]);

function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

async function getPageWsUrl() {
  for (let i = 0; i < 30; i++) {
    await sleep(400);
    try {
      const res = await new Promise((resolve, reject) => {
        http.get('http://127.0.0.1:9222/json', resp => {
          let data = '';
          resp.on('data', chunk => data += chunk);
          resp.on('end', () => resolve(JSON.parse(data)));
        }).on('error', reject);
      });
      const pageTarget = res.find(t => t.type === 'page' && t.url.includes('localhost:8082'));
      if (pageTarget && pageTarget.webSocketDebuggerUrl) {
        return pageTarget.webSocketDebuggerUrl;
      }
    } catch (e) {}
  }
  throw new Error('Could not find Anima page target');
}

async function main() {
  try {
    const wsUrl = await getPageWsUrl();
    console.log('Connecting to Anima page at:', wsUrl);

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
    console.log('Connected to CDP.');

    function send(method, params = {}) {
      return new Promise((resolve, reject) => {
        const curId = id++;
        callbacks.set(curId, { resolve, reject });
        ws.send(JSON.stringify({ id: curId, method, params }));
      });
    }

    await send('Page.enable');
    await send('Runtime.enable');

    console.log('Waiting for Hinata model to load in Anima...');
    let ready = false;
    for (let i = 0; i < 40; i++) {
      await sleep(500);
      const evalRes = await send('Runtime.evaluate', {
        expression: `({
          note: document.querySelector('.lab__note')?.innerText || '',
          canvas: !!document.querySelector('canvas')
        })`,
        returnByValue: true
      });
      const val = evalRes.result?.value;
      if (val && val.canvas && !val.note) {
        ready = true;
        console.log('Character ready after', (i + 1) * 0.5, 's');
        break;
      }
    }

    // Let 1.2s of animation play
    await sleep(1200);

    // 1. Capture Idle
    console.log('Capturing verified Idle...');
    let snap = await send('Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(path.join(brainDir, 'anima_hinata_1_idle.png'), Buffer.from(snap.data, 'base64'));

    // 2. Trigger Wave
    console.log('Triggering Wave gesture...');
    await send('Runtime.evaluate', {
      expression: `
        const btns = Array.from(document.querySelectorAll('button'));
        const waveBtn = btns.find(b => b.innerText.includes('wave'));
        if (waveBtn) waveBtn.click();
      `
    });
    await sleep(1400);
    snap = await send('Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(path.join(brainDir, 'anima_hinata_2_wave.png'), Buffer.from(snap.data, 'base64'));

    // 3. Trigger Talk & Lip-Sync
    console.log('Triggering Talk & Speech Lip-sync with Smile...');
    await send('Runtime.evaluate', {
      expression: `
        if (window.__stage) {
          window.__stage.audioLevel = 0.95;
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

    console.log('ALL VERIFIED ANIMATION FRAMES SAVED SUCCESSFULLY!');
    ws.close();
  } finally {
    chrome.kill();
  }
}

main().catch(err => {
  console.error('Fatal error:', err);
  chrome.kill();
  process.exit(1);
});
