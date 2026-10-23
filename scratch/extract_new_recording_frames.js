const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const WebSocket = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/ws'));

const videoPath = path.resolve(__dirname, 'Screenrecording_20261023_195759.mp4');
const outDir = path.resolve(__dirname, '../artifacts/new_user_recording_frames');
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

server.listen(8770, async () => {
  console.log('Video server listening on port 8770');
  const chromePath = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
  const edgePath = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
  const browserBin = fs.existsSync(chromePath) ? chromePath : edgePath;
  const userDataDir = 'C:\\Users\\Roshan\\AppData\\Local\\Temp\\browser_video_extract_new';

  const proc = spawn(browserBin, [
    '--remote-debugging-port=9250',
    '--headless=new',
    '--disable-gpu-sandbox',
    '--no-sandbox',
    `--user-data-dir=${userDataDir}`,
    'http://127.0.0.1:8770/'
  ]);

  await new Promise(r => setTimeout(r, 2000));

  try {
    const listRes = await fetch('http://127.0.0.1:9250/json');
    const list = await listRes.json();
    const page = list.find(p => p.type === 'page');

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

    // Wait until video metadata loaded
    for (let i = 0; i < 30; i++) {
      const ready = await evalExpr('window.isReady');
      if (ready) break;
      await new Promise(r => setTimeout(r, 300));
    }

    const duration = await evalExpr('window.videoDuration') || 20.0;
    console.log('Video duration:', duration, 'seconds');

    // Extract frames at 1s intervals
    const count = Math.min(Math.floor(duration), 30);
    for (let t = 1; t <= count; t += 2) {
      console.log(`Extracting frame at ${t}s...`);
      const dataUrl = await evalExpr(`window.seekAndCapture(${t})`);
      if (dataUrl && dataUrl.startsWith('data:image/jpeg;base64,')) {
        const base64Data = dataUrl.replace(/^data:image\/jpeg;base64,/, "");
        const filePath = path.join(outDir, `frame_${String(t).padStart(2, '0')}s.jpg`);
        fs.writeFileSync(filePath, Buffer.from(base64Data, 'base64'));
        console.log(`Saved ${filePath}`);
      }
    }
    console.log('All frames extracted successfully!');
    ws.close();
  } catch (e) {
    console.error('Extraction error:', e);
  } finally {
    proc.kill();
    server.close();
    process.exit(0);
  }
});
