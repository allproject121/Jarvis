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
    const id = 300;
    ws.on('message', function handler(m) {
      const data = JSON.parse(m);
      if (data.id === id) {
        ws.removeListener('message', handler);
        resolve(data.result);
      }
    });
    ws.send(JSON.stringify({ id, method: 'Runtime.evaluate', params: { expression: expr, returnByValue: true } }));
  });

  console.log('Frame 1: Capturing resting state before wave...');
  capture('device_hinata_motion_1_idle.png');

  console.log('Triggering wave gesture...');
  await run('window.JarvisOrb.triggerWave()');

  await sleep(350);
  console.log('Frame 2: Capturing wave rising frame...');
  capture('device_hinata_motion_2_wave_up.png');

  await sleep(400);
  console.log('Frame 3: Capturing wave peak motion frame...');
  capture('device_hinata_motion_3_wave_peak.png');

  ws.close();
  console.log('Done capturing wave sequence.');
}

main().catch(console.error);
