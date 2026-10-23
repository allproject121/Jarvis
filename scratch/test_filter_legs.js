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
            const gltfLoader = new THREE.GLTFLoader();
            gltfLoader.load('models/hinata_animations.glb', (animGltf) => {
              ctrl.mixer.stopAllAction();
              ctrl.actions = {};
              for (const clip of animGltf.animations) {
                const rawName = (clip.name || '').toLowerCase();
                const cleanTracks = [];
                for (const track of clip.tracks) {
                  const bName = track.name.split('.')[0].toLowerCase();
                  // Skip leg tracks that have inverted VRM bone flips
                  if (bName.includes('upperleg') || bName.includes('lowerleg') || bName.includes('foot') || bName.includes('toe')) {
                    continue;
                  }
                  // Keep quaternion rotation tracks for bones that exist in Mora
                  if ((track.name.endsWith('.quaternion') || track.name.endsWith('.rotation')) && ctrl.root.getObjectByName(track.name.split('.')[0])) {
                    cleanTracks.push(track);
                  }
                }
                const newClip = new THREE.AnimationClip(clip.name, clip.duration, cleanTracks);
                const action = ctrl.mixer.clipAction(newClip);
                action.setLoop(THREE.LoopRepeat);
                ctrl.actions[rawName] = action;
                if (rawName.includes('idle')) ctrl.actions['idle'] = action;
                if (rawName.includes('talking_0') || rawName === 'talking0') ctrl.actions['talking_0'] = action;
                if (rawName.includes('talking_1') || rawName === 'talking1') ctrl.actions['talking_1'] = action;
                if (rawName.includes('talking_2') || rawName === 'talking2') ctrl.actions['talking_2'] = action;
                if (rawName.includes('talking') && !ctrl.actions['talking_0']) ctrl.actions['talking_0'] = action;
                if (rawName.includes('laugh')) ctrl.actions['laughing'] = action;
                if (rawName.includes('angry')) ctrl.actions['angry'] = action;
                if (rawName.includes('cry')) ctrl.actions['crying'] = action;
                if (rawName.includes('rumba') || rawName.includes('dance')) ctrl.actions['rumba'] = action;
                if (rawName.includes('terrif') || rawName.includes('fear')) ctrl.actions['terrified'] = action;
              }
              ctrl.activeAction = null;
              ctrl.fadeToAction('idle', 0.1);
            });
            return { status: 'reloading without legs' };
          })()`,
          returnByValue: true
        }
      }));
    });
    ws.on('message', msg => {
      console.log('Reload result:', JSON.parse(msg).result.result.value);
      ws.close();
    });
  });
});
