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
            const ctrl = window.MoraCompanion.getController();
            const action = ctrl.activeAction;
            if (!action) return 'no action';
            const clip = action.getClip();
            return {
              clipName: clip.name,
              tracksCount: clip.tracks.length,
              sampleTracks: clip.tracks.slice(0, 15).map(t => t.name)
            };
          })()`,
          returnByValue: true
        }
      }));
    });
    ws.on('message', msg => {
      console.log('Active clip tracks:', JSON.stringify(JSON.parse(msg).result.result.value, null, 2));
      ws.close();
    });
  });
});
