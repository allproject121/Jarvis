const { execFile } = require('child_process');
const path = require('path');
const fs = require('fs');

const chromePath = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const outPng = path.resolve(__dirname, 'anima_test.png');

const args = [
  '--headless=new',
  '--use-gl=angle',
  '--enable-webgl',
  '--screenshot=' + outPng,
  '--window-size=1280,900',
  'http://localhost:8082/lab'
];

execFile(chromePath, args, (err, stdout, stderr) => {
  console.log('Error:', err);
  console.log('stdout:', stdout);
  console.log('stderr:', stderr);
  console.log('Screenshot exists?', fs.existsSync(outPng));
});
