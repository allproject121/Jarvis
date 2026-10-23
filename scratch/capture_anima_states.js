const { execFile } = require('child_process');
const path = require('path');
const fs = require('fs');

const chromePath = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const brainDir = 'C:\\Users\\Roshan\\.gemini\\antigravity\\brain\\027c6672-b4f2-4696-badc-0e02f6ddd130';

function capture(url, outName) {
  return new Promise((resolve, reject) => {
    const outPng = path.join(brainDir, outName);
    const args = [
      '--headless',
      '--use-gl=angle',
      '--enable-webgl',
      '--screenshot=' + outPng,
      '--window-size=1280,900',
      '--virtual-time-budget=20000',
      url
    ];
    console.log('Capturing:', url, '->', outName);
    execFile(chromePath, args, (err) => {
      if (err) console.error('Error for', outName, err);
      else console.log('Successfully wrote', outName, fs.existsSync(outPng));
      resolve();
    });
  });
}

async function main() {
  await capture('http://localhost:8082/lab?motion=idle_243', 'anima_hinata_idle.png');
  await capture('http://localhost:8082/lab?motion=wave_290', 'anima_hinata_wave.png');
  await capture('http://localhost:8082/lab?motion=talk_314&audio=0.9&emotion=happy', 'anima_hinata_talk.png');
  console.log('All captures done.');
}

main();
