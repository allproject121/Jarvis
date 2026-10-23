const path = require('path');
const WebSocket = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/ws'));
const http = require('http');

http.get('http://127.0.0.1:9222/json', (res) => {
  let data = ''; res.on('data', c => data += c);
  res.on('end', () => {
    const list = JSON.parse(data);
    const page = list.find(p => p.type === 'page' && p.url.includes('mora_character.html'));
    const ws = new WebSocket(page.webSocketDebuggerUrl);
    ws.on('open', () => {
      ws.send(JSON.stringify({
        id: 1, method: 'Runtime.evaluate',
        params: {
          expression: `(() => {
            const m = window.MoraCompanion.getModel();
            function printNode(node, depth = 0) {
              const res = [{
                depth,
                name: node.name,
                type: node.type,
                isBone: !!node.isBone,
                isSkinnedMesh: !!node.isSkinnedMesh,
                pos: [node.position.x, node.position.y, node.position.z],
                rot: [node.rotation.x, node.rotation.y, node.rotation.z],
                scale: [node.scale.x, node.scale.y, node.scale.z]
              }];
              for (const child of node.children) {
                res.push(...printNode(child, depth + 1));
              }
              return res;
            }
            return printNode(m).slice(0, 40);
          })()`,
          returnByValue: true
        }
      }));
    });
    ws.on('message', msg => {
      console.log('Result:', JSON.stringify(JSON.parse(msg).result.result.value, null, 2));
      ws.close();
    });
  });
});
