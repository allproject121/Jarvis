const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const WebSocket = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/ws'));

const videoPath = path.resolve('scratch/mora_canonical_test.mp4');
const outDir = path.resolve('scratch/canonical_frames');
if (!fs.existsSync(outDir)) fs.mkdirSync(outDir, { recursive: true });

const server = http.createServer((req, res) => {
  if (req.url === '/video.mp4') {
    const stat = fs.statSync(videoPath);
    const range = req.headers.range;
    if (range) {
      const parts = range.replace(/bytes=/, "").split("-");
      const start = parseInt(parts[0], 10);
      const end = parts[1] ? parseInt(parts[1], 10) : stat.size - 1;
      const chunksize = (end - start) + 1;
      const file = fs.createReadStream(videoPath, { start, end });
      res.writeHead(206, {
        'Content-Range': `bytes ${start}-${end}/${stat.size}`,
        'Accept-Ranges': 'bytes',
        'Content-Length': chunksize,
        'Content-Type': 'video/mp4',
      });
      file.pipe(res);
    } else {
      res.writeHead(200, { 'Content-Length': stat.size, 'Content-Type': 'video/mp4' });
      fs.createReadStream(videoPath).pipe(res);
    }
  } else if (req.url === '/') {
    res.writeHead(200, { 'Content-Type': 'text/html' });
    res.end(`<!DOCTYPE html>
    <html><body>
    <video id="v" src="/video.mp4" muted crossorigin="anonymous" playsinline style="display:none;"></video>
    <canvas id="c"></canvas>
    <script>
      const v = document.getElementById('v');
      const c = document.getElementById('c');
      const ctx = c.getContext('2d');
      window.isReady = false;
      v.addEventListener('loadedmetadata', () => {
        c.width = v.videoWidth;
        c.height = v.videoHeight;
        window.videoDuration = v.duration;
        window.isReady = true;
      });
      window.seekAndCapture = function(time) {
        return new Promise((resolve) => {
          v.currentTime = time;
          v.onseeked = () => {
            ctx.drawImage(v, 0, 0);
            resolve(c.toDataURL('image/jpeg', 0.85));
          };
        });
      };
    </script>
    </body></html>`);
  }
});

server.listen(8766, async () => {
  console.log('Video server listening on port 8766');
  const chromePath = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
  const edgePath = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
  const browserBin = fs.existsSync(chromePath) ? chromePath : edgePath;
  const userDataDir = 'C:\\Users\\Roshan\\AppData\\Local\\Temp\\browser_video_extract_canonical';

  const proc = spawn(browserBin, [
    '--remote-debugging-port=9224',
    '--headless=new',
    '--disable-gpu-sandbox',
    '--no-sandbox',
    `--user-data-dir=${userDataDir}`,
    'http://127.0.0.1:8766/'
  ]);

  await new Promise(r => setTimeout(r, 2000));

  try {
    const listRes = await fetch('http://127.0.0.1:9224/json');
    const list = await listRes.json();
    const page = list.find(p => p.type === 'page');
    console.log('Connected to browser page:', page.title);

    const ws = new WebSocket(page.webSocketDebuggerUrl);
    await new Promise(r => ws.on('open', r));

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
        ws.send(JSON.stringify({ id, method: 'Runtime.evaluate', params: { expression: expr, returnByValue: true, awaitPromise: true } }));
      });
    }

    for (let i = 0; i < 20; i++) {
      const ready = await evalExpr('window.isReady');
      if (ready) break;
      await new Promise(r => setTimeout(r, 300));
    }

    const duration = await evalExpr('window.videoDuration') || 35.0;
    console.log('Video duration:', duration, 'seconds');

    const testPhases = [
      { time: 2.0, name: '01_idle_standing.jpg', desc: 'Phase 1: Stable Idle standing breathing pose' },
      { time: 6.0, name: '02_wave_gesture.jpg', desc: 'Phase 2: Wave hand gesture' },
      { time: 10.5, name: '03_joy_emote.jpg', desc: 'Phase 3: Joy cheer reaction' },
      { time: 15.0, name: '04_learn_emote.jpg', desc: 'Phase 4: Learn respectful nod & gesture' },
      { time: 20.0, name: '05_dance_motion.jpg', desc: 'Phase 5: Full-body dance movement' },
      { time: 25.5, name: '06_speaking_gesture.jpg', desc: 'Phase 6: Conversational speech & hand motion' },
      { time: 29.0, name: '07_poke_reaction.jpg', desc: 'Phase 7: Touch poke interactive reaction' },
      { time: 33.0, name: '08_idle_recovery.jpg', desc: 'Phase 8: Smooth recovery to stable Idle' }
    ];

    for (const phase of testPhases) {
      if (phase.time > duration) continue;
      console.log(`Extracting [${phase.desc}] at ${phase.time}s...`);
      const dataUrl = await evalExpr(`window.seekAndCapture(${phase.time})`);
      if (dataUrl && dataUrl.startsWith('data:image/jpeg;base64,')) {
        const base64 = dataUrl.replace('data:image/jpeg;base64,', '');
        const outPath = path.join(outDir, phase.name);
        fs.writeFileSync(outPath, Buffer.from(base64, 'base64'));
        console.log(`  -> Saved ${outPath} (${(Buffer.byteLength(base64, 'base64') / 1024).toFixed(1)} KB)`);
      }
    }

    console.log('\nAll key verification frames extracted successfully!');
  } catch (e) {
    console.error('Frame extraction error:', e);
  } finally {
    proc.kill();
    server.close();
    process.exit(0);
  }
});
