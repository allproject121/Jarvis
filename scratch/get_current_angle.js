const path = require('path');
const WebSocket = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/ws'));

async function main() {
  const ws = new WebSocket('ws://127.0.0.1:9222/devtools/page/0645D74DB260EDD2AD2B954D345930BD');
  await new Promise(r => ws.on('open', r));

  const run = (expr) => new Promise((resolve) => {
    const id = 200;
    ws.on('message', function handler(m) {
      const data = JSON.parse(m);
      if (data.id === id) {
        ws.removeListener('message', handler);
        resolve(data.result);
      }
    });
    ws.send(JSON.stringify({ id, method: 'Runtime.evaluate', params: { expression: expr, returnByValue: true } }));
  });

  // Find customModelGroup from scene
  const res = await run(`
    (function() {
      // Find scene from renderer or canvas
      let foundGroup = null;
      let foundWrapper = null;
      let foundModel = null;
      // In Three.js, all meshes have parent
      // Let's find meshes with Face.baked
      let faceMesh = null;
      // We can find all Object3D instances by searching through window or closures if available
      // Or find canvas WebGL context
      return {
        draggedCurrentState: "testing"
      };
    })()
  `);
  console.log(res);
  ws.close();
}

main().catch(console.error);
