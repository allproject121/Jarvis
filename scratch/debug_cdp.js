const { spawn } = require('child_process');
const http = require('http');

const chromePath = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const chrome = spawn(chromePath, [
  '--headless=new',
  '--remote-debugging-port=9222',
  '--use-gl=angle',
  '--enable-webgl',
  '--window-size=1280,900',
  'http://localhost:8082/lab'
]);

chrome.stdout.on('data', d => console.log('chrome out:', d.toString()));
chrome.stderr.on('data', d => console.log('chrome err:', d.toString()));

setTimeout(() => {
  http.get('http://127.0.0.1:9222/json', res => {
    let d = '';
    res.on('data', c => d += c);
    res.on('end', () => {
      console.log('JSON endpoints:', d);
      chrome.kill();
      process.exit(0);
    });
  }).on('error', e => {
    console.error('HTTP err:', e.message);
    chrome.kill();
    process.exit(1);
  });
}, 3000);
