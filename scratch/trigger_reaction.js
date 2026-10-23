const path = require('path');
const WebSocket = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/ws'));
const http = require('http');

const reaction = process.argv[2] || 'wave';

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
            const r = '${reaction}';
            if (r === 'speaking') {
              window.MoraCompanion.setState('speaking');
              window.MoraCompanion.setExternalAudio(0.7);
            } else if (r === 'listening') {
              window.MoraCompanion.setState('listening');
            } else if (r === 'thinking') {
              window.MoraCompanion.setState('thinking');
            } else if (r === 'idle') {
              window.MoraCompanion.setState('idle');
            } else {
              window.MoraCompanion.triggerReaction(r);
            }
            return { triggered: r, activeAction: window.MoraCompanion.getController().activeActionName };
          })()`,
          returnByValue: true
        }
      }));
    });
    ws.on('message', msg => {
      console.log('Reaction evaluated:', JSON.parse(msg).result.result.value);
      ws.close();
    });
  });
});
