const path = require('path');
const { execSync } = require('child_process');
const WebSocket = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/ws'));
const http = require('http');

try {
  const adb = 'C:\\Android\\Sdk\\platform-tools\\adb.exe';
  const out = execSync(`"${adb}" -s 10BG1D01DA007QG shell "cat /proc/net/unix | grep webview_devtools"`).toString();
  const match = out.match(/@(webview_devtools_remote_\d+)/);
  if (match) {
    execSync(`"${adb}" -s 10BG1D01DA007QG forward tcp:9222 localabstract:${match[1]}`);
  }
} catch (e) {}

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
            const m = window.MoraCompanion.getModel();

            const MIXAMO_MAP = {
              hips: 'J_Bip_C_Hips',
              spine: 'J_Bip_C_Spine',
              spine1: 'J_Bip_C_Chest',
              spine2: 'J_Bip_C_UpperChest',
              neck: 'J_Bip_C_Neck',
              head: 'J_Bip_C_Head',
              leftshoulder: 'J_Bip_L_Shoulder',
              leftarm: 'J_Bip_L_UpperArm',
              leftforearm: 'J_Bip_L_LowerArm',
              lefthand: 'J_Bip_L_Hand',
              rightshoulder: 'J_Bip_R_Shoulder',
              rightarm: 'J_Bip_R_UpperArm',
              rightforearm: 'J_Bip_R_LowerArm',
              righthand: 'J_Bip_R_Hand'
            };

            const gltfLoader = new THREE.GLTFLoader();
            gltfLoader.load('models/mora_animations.glb', (animGltf) => {
              ctrl.mixer.stopAllAction();
              ctrl.actions = {};

              for (const clip of animGltf.animations) {
                const rawName = (clip.name || '').toLowerCase();
                const remappedTracks = [];

                for (const track of clip.tracks) {
                  if (!track.name.endsWith('.quaternion') && !track.name.endsWith('.rotation')) continue;
                  const parts = track.name.split('.');
                  const rawBone = parts[0];
                  const prop = parts[1];
                  const targetBoneName = MIXAMO_MAP[rawBone.toLowerCase()] || rawBone;

                  if (m.getObjectByName(targetBoneName)) {
                    const clonedTrack = track.clone();
                    clonedTrack.name = targetBoneName + '.' + prop;
                    remappedTracks.push(clonedTrack);
                  }
                }

                const retargetedClip = new THREE.AnimationClip(clip.name, clip.duration, remappedTracks);
                const action = ctrl.mixer.clipAction(retargetedClip);
                action.setLoop(THREE.LoopRepeat);
                ctrl.actions[rawName] = action;
                if (rawName.includes('idle')) ctrl.actions['idle'] = action;
                if (rawName.includes('talking_0') || rawName === 'talking0') ctrl.actions['talking_0'] = action;
                if (rawName.includes('talking_1') || rawName === 'talking1') ctrl.actions['talking_1'] = action;
                if (rawName.includes('talking_2') || rawName === 'talking2') ctrl.actions['talking_2'] = action;
                if (rawName.includes('talking') && !ctrl.actions['talking_0']) ctrl.actions['talking_0'] = action;
                if (rawName.includes('laugh')) ctrl.actions['laughing'] = action;
                if (rawName.includes('rumba') || rawName.includes('dance')) ctrl.actions['rumba'] = action;
              }

              ctrl.activeAction = null;
              ctrl.fadeToAction('idle', 0.1);
            });

            return { status: 'retargeting Mixamo to VRoid bones' };
          })()`,
          returnByValue: true
        }
      }));
    });
    ws.on('message', msg => {
      console.log('Retarget result:', JSON.parse(msg).result.result.value);
      ws.close();
    });
  });
});
