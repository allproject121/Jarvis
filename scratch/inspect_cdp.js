const path = require('path');
const WebSocket = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/ws'));

async function main() {
  const ws = new WebSocket('ws://127.0.0.1:9222/devtools/page/0645D74DB260EDD2AD2B954D345930BD');
  await new Promise(r => ws.on('open', r));

  const run = (expr) => new Promise((resolve) => {
    const id = 100;
    ws.on('message', function handler(m) {
      const data = JSON.parse(m);
      if (data.id === id) {
        ws.removeListener('message', handler);
        resolve(data.result);
      }
    });
    ws.send(JSON.stringify({ id, method: 'Runtime.evaluate', params: { expression: expr, returnByValue: true } }));
  });

  const res = await run('document.title + " | " + location.href');
  console.log('Page info:', res);

  // Let's reload the page with ignoreCache: true!
  console.log('Sending Page.reload with ignoreCache: true...');
  const reloadId = 101;
  await new Promise(resolve => {
    ws.on('message', function handler(m) {
      const data = JSON.parse(m);
      if (data.id === reloadId) {
        ws.removeListener('message', handler);
        resolve();
      }
    });
    ws.send(JSON.stringify({ id: reloadId, method: 'Page.reload', params: { ignoreCache: true } }));
  });
  console.log('Reload triggered.');

  ws.close();
}

main().catch(console.error);
