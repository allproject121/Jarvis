const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const WebSocket = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/ws'));

const assetsDir = path.resolve(__dirname, '../app/src/main/assets');

const server = http.createServer((req, res) => {
  let reqPath = req.url.split('?')[0];
  if (reqPath === '/') reqPath = '/mora_character.html';
  const filePath = path.join(assetsDir, reqPath);
  if (!fs.existsSync(filePath)) {
    res.writeHead(404);
    res.end();
    return;
  }
  res.writeHead(200, { 'Content-Type': 'text/html', 'Access-Control-Allow-Origin': '*' });
  fs.createReadStream(filePath).pipe(res);
});

server.listen(8920, async () => {
  const browserBin = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
  const userDataDir = 'C:\\Users\\Roshan\\AppData\\Local\\Temp\\chrome_debug_err';

  const proc = spawn(browserBin, [
    '--remote-debugging-port=9240',
    '--headless=new',
    '--disable-gpu-sandbox',
    '--no-sandbox',
    `--user-data-dir=${userDataDir}`,
    'http://127.0.0.1:8920/mora_character.html'
  ]);

  await new Promise(r => setTimeout(r, 2000));
  const listRes = await fetch('http://127.0.0.1:9240/json');
  const list = await listRes.json();
  const page = list.find(p => p.type === 'page');

  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise(r => ws.on('open', r));

  ws.on('message', (data) => {
    const msg = JSON.parse(data);
    if (msg.method === 'Runtime.consoleAPICalled') {
      console.log('CONSOLE:', msg.params.args.map(a => a.value || JSON.stringify(a)).join(' '));
    }
    if (msg.method === 'Runtime.exceptionThrown') {
      console.error('EXCEPTION:', JSON.stringify(msg.params.exceptionDetails, null, 2));
    }
  });

  ws.send(JSON.stringify({ id: 1, method: 'Runtime.enable' }));
  ws.send(JSON.stringify({ id: 2, method: 'Log.enable' }));

  await new Promise(r => setTimeout(r, 6000));

  ws.close();
  proc.kill();
  server.close();
  process.exit(0);
});
