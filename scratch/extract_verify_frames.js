const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const WebSocket = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/ws'));

const videoPath = path.resolve('scratch/mora_test_verify.mp4');
const outDir = path.resolve('scratch/verify_frames');
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

server.listen(8768, async () => {
  console.log('Video server listening on port 8768');
  const chromePath = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
  const edgePath = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
  const browserBin = fs.existsSync(chromePath) ? chromePath : edgePath;
  const userDataDir = 'C:\\Users\\Roshan\\AppData\\Local\\Temp\\browser_video_extract_verify';

  const proc = spawn(browserBin, [
    '--remote-debugging-port=9226',
    '--headless=new',
    '--disable-gpu-sandbox',
    '--no-sandbox',
    `--user-data-dir=${userDataDir}`,
    'http://127.0.0.1:8768/'
  ]);

  await new Promise(r => setTimeout(r, 2000));

  try {
    const listRes = await fetch('http://127.0.0.1:9226/json');
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

    const duration = await evalExpr('window.videoDuration') || 16.0;
    console.log('Video duration:', duration, 'seconds');

    // Sample across the 16-second recording
    const times = [1.0, 2.5, 4.5, 6.5, 8.5, 11.0, 13.5, 15.5];
    for (let i = 0; i < times.length; i++) {
      const t = times[i];
      if (t > duration) continue;
      console.log(`Extracting frame at ${t}s...`);
      const dataUrl = await evalExpr(`window.seekAndCapture(${t})`);
      if (dataUrl && dataUrl.startsWith('data:image/jpeg;base64,')) {
        const base64 = dataUrl.replace('data:image/jpeg;base64,', '');
        const outPath = path.join(outDir, `frame_${i + 1}_${t.toFixed(1)}s.jpg`);
        fs.writeFileSync(outPath, Buffer.from(base64, 'base64'));
        console.log(`  -> Saved ${outPath} (${(Buffer.byteLength(base64, 'base64') / 1024).toFixed(1)} KB)`);
      }
    }

    console.log('\nAll frames extracted successfully!');
  } catch (e) {
    console.error('Frame extraction error:', e);
  } finally {
    proc.kill();
    server.close();
    process.exit(0);
  }
});
