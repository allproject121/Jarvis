const path = require('path');
const WebSocket = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/ws'));
const http = require('http');

async function getWsUrl() {
  return new Promise((resolve, reject) => {
    http.get('http://127.0.0.1:9222/json', (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        const list = JSON.parse(data);
        const page = list.find(p => p.type === 'page' && p.url.includes('mora_character.html'));
        if (page) resolve(page.webSocketDebuggerUrl);
        else reject(new Error('mora_character.html page not found: ' + JSON.stringify(list)));
      });
    }).on('error', reject);
  });
}

async function run() {
  const wsUrl = await getWsUrl();
  console.log('Connecting to:', wsUrl);
  const ws = new WebSocket(wsUrl);
  await new Promise(r => ws.on('open', r));

  function evalExpr(expr) {
    return new Promise((resolve) => {
      const id = Math.floor(Math.random() * 100000);
      const handler = (data) => {
        const msg = JSON.parse(data);
        if (msg.id === id) {
          ws.removeListener('message', handler);
          resolve(msg.result);
        }
      };
      ws.on('message', handler);
      ws.send(JSON.stringify({ id, method: 'Runtime.evaluate', params: { expression: expr, returnByValue: true } }));
    });
  }

  const res = await evalExpr(`
    (() => {
      const c = window.MoraCompanion.getController();
      const m = window.MoraCompanion.getModel();
      const a = c ? c.actions['idle'] : null;
      return {
        hasController: !!c,
        hasModel: !!m,
        modelRotY: m ? m.rotation.y : null,
        hasClips: c ? c.hasClips : false,
        actionsCount: c ? Object.keys(c.actions).length : 0,
        actions: c ? Object.keys(c.actions) : [],
        activeActionName: c ? c.activeActionName : null,
        activeActionRunning: c && c.activeAction ? c.activeAction.isRunning() : false,
        idleClipTracks: a ? a._clip.tracks.length : 0,
        idleFirstTracks: a ? a._clip.tracks.slice(0, 5).map(t => t.name) : [],
        bindingsCount: a && a._propertyBindings ? a._propertyBindings.length : 0,
        bindingNodes: a && a._propertyBindings ? a._propertyBindings.slice(0, 5).map(b => b.binding ? (b.binding.node ? b.binding.node.name : 'no node') : 'no binding') : []
      };
    })()
  `);
  console.log('Controller Inspection:', res.result.value);

  // Now test rotating model to front
  console.log('Setting model.rotation.y = Math.PI and camera lookAt...');
  await evalExpr(`
    (() => {
      const m = window.MoraCompanion.getModel();
      if (m) m.rotation.y = Math.PI;
      const c = window.MoraCompanion.getController();
      if (c && c.actions["idle"]) {
        c.fadeToAction("idle", 0.1);
      }
      return true;
    })()
  `);

  ws.close();
}

run().catch(console.error);
