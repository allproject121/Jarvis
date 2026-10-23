const path = require('path');
const WebSocket = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/ws'));
const http = require('http');
const { execSync } = require('child_process');

async function getWsUrl() {
  return new Promise((resolve, reject) => {
    http.get('http://127.0.0.1:9222/json', (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        const list = JSON.parse(data);
        const page = list.find(p => p.type === 'page' && p.url.includes('orb.html'));
        if (page) resolve(page.webSocketDebuggerUrl);
        else reject(new Error('orb.html page not found'));
      });
    }).on('error', reject);
  });
}

function sendCommand(ws, method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = Math.floor(Math.random() * 100000);
    const handler = (msg) => {
      const data = JSON.parse(msg);
      if (data.id === id) {
        ws.removeListener('message', handler);
        if (data.error) reject(data.error);
        else resolve(data.result);
      }
    };
    ws.on('message', handler);
    ws.send(JSON.stringify({ id, method, params }));
  });
}

async function evalJs(ws, expression) {
  const res = await sendCommand(ws, 'Runtime.evaluate', {
    expression,
    returnByValue: true
  });
  return res.result ? res.result.value : res;
}

function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

async function main() {
  const wsUrl = await getWsUrl();
  console.log('Connecting to:', wsUrl);
  const ws = new WebSocket(wsUrl);
  await new Promise(res => ws.on('open', res));

  // Let's inspect the entire scene hierarchy in customModelGroup or scene
  const hierarchy = await evalJs(ws, `
    (function() {
      if (!window.JarvisOrb) return "no JarvisOrb";
      const res = [];
      // Search in scene
      let targetObj = null;
      let targetWrapper = null;
      let targetGroup = null;

      // Find all groups/meshes in scene
      scene.traverse(function(obj) {
        if (obj.isMesh && obj.name) {
          res.push({ name: obj.name, type: obj.type, rotY: obj.rotation.y });
        }
      });
      return {
        meshesSample: res.slice(0, 10),
        totalMeshes: res.length,
        customModelLoaded: typeof customModelLoaded !== "undefined" ? customModelLoaded : null,
        customModelInnerRotY: customModelInner ? customModelInner.rotation.y : null,
        customModelInnerScale: customModelInner ? customModelInner.scale.x : null,
        customModelObjRotY: (customModelInner && customModelInner.children[0]) ? customModelInner.children[0].rotation.y : null,
        customModelGroupRotY: customModelGroup ? customModelGroup.rotation.y : null,
        customModelRotYVar: typeof customModelRotY !== "undefined" ? customModelRotY : null
      };
    })()
  `);

  console.log('Scene details:', JSON.stringify(hierarchy, null, 2));

  // Now let's test rotating customModelInner.children[0].rotation.y to 0, Math.PI, etc.
  console.log('Testing rotation 0...');
  await evalJs(ws, `
    if (customModelInner && customModelInner.children[0]) {
      customModelInner.children[0].rotation.y = 0;
    }
  `);
  await sleep(1000);
  execSync('C:\\Android\\Sdk\\platform-tools\\adb.exe shell screencap -p /sdcard/rot_0.png');
  execSync('C:\\Android\\Sdk\\platform-tools\\adb.exe pull /sdcard/rot_0.png C:\\Users\\Roshan\\.gemini\\antigravity\\brain\\027c6672-b4f2-4696-badc-0e02f6ddd130\\rot_0.png');

  console.log('Testing rotation Math.PI...');
  await evalJs(ws, `
    if (customModelInner && customModelInner.children[0]) {
      customModelInner.children[0].rotation.y = Math.PI;
    }
  `);
  await sleep(1000);
  execSync('C:\\Android\\Sdk\\platform-tools\\adb.exe shell screencap -p /sdcard/rot_pi.png');
  execSync('C:\\Android\\Sdk\\platform-tools\\adb.exe pull /sdcard/rot_pi.png C:\\Users\\Roshan\\.gemini\\antigravity\\brain\\027c6672-b4f2-4696-badc-0e02f6ddd130\\rot_pi.png');

  ws.close();
}

main().catch(console.error);
