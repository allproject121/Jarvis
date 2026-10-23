const path = require('path');
const WebSocket = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/ws'));
const { execSync } = require('child_process');

const brainDir = 'C:\\Users\\Roshan\\.gemini\\antigravity\\brain\\027c6672-b4f2-4696-badc-0e02f6ddd130';

function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

function capture(outName) {
  const remote = '/sdcard/' + outName;
  const local = path.join(brainDir, outName);
  execSync(`C:\\Android\\Sdk\\platform-tools\\adb.exe shell screencap -p ${remote}`);
  execSync(`C:\\Android\\Sdk\\platform-tools\\adb.exe pull ${remote} ${local}`);
  console.log('Saved frame:', outName);
}

async function main() {
  const ws = new WebSocket('ws://127.0.0.1:9222/devtools/page/0645D74DB260EDD2AD2B954D345930BD');
  await new Promise(r => ws.on('open', r));

  const run = (expr) => new Promise((resolve) => {
    const id = 400;
    ws.on('message', function handler(m) {
      const data = JSON.parse(m);
      if (data.id === id) {
        ws.removeListener('message', handler);
        resolve(data.result);
      }
    });
    ws.send(JSON.stringify({ id, method: 'Runtime.evaluate', params: { expression: expr, returnByValue: true } }));
  });

  // Since we want to rotate via the canvas pointer events or via customModelRotY:
  // Let's dispatch pointer events on the canvas to set exact rotation!
  console.log('Dispatching drag to find face...');
  await run(`
    (function() {
      const canvas = document.querySelector('canvas');
      if (!canvas) return 'no canvas';
      const rect = canvas.getBoundingClientRect();
      const midX = rect.left + rect.width / 2;
      const midY = rect.top + rect.height / 2;

      function dispatch(type, x, y) {
        canvas.dispatchEvent(new PointerEvent(type, {
          clientX: x,
          clientY: y,
          bubbles: true,
          cancelable: true,
          pointerId: 1
        }));
      }

      // Drag by +450 pixels
      dispatch('pointerdown', midX, midY);
      dispatch('pointermove', midX + 450, midY);
      dispatch('pointerup', midX + 450, midY);
      return 'dragged';
    })()
  `);

  await sleep(600);
  capture('device_hinata_front_verified.png');

  ws.close();
}

main().catch(console.error);
