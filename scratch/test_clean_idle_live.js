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
    console.log('Forwarded', match[1]);
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

            // Override update to prevent any compounding quaternion multiplication
            ctrl.update = function(dt, time, state, audioLevel, activeReaction, reactionTimer) {
              if (this.hasClips) {
                let desired = "idle";
                if (activeReaction === "rumba" || activeReaction === "dance") desired = "rumba";
                else if (activeReaction === "joy" || activeReaction === "laugh") desired = "laughing";
                else if (activeReaction === "angry") desired = "angry";
                else if (activeReaction === "cry") desired = "crying";
                else if (activeReaction === "terrified") desired = "terrified";
                else if (activeReaction === "wave") desired = "talking_1";
                else if (activeReaction === "teach" || activeReaction === "nod") desired = "talking_2";
                else if (state === "speaking") {
                  const cycle = Math.floor((time / 2.6) % 3);
                  desired = cycle === 1 ? "talking_1" : (cycle === 2 ? "talking_2" : "talking_0");
                } else {
                  desired = "idle";
                }
                this.fadeToAction(desired, 0.35);
                this.mixer.update(dt);
              }

              // Audio lip-sync & blinking
              const isSpeaking = (state === "speaking");
              const rawVol = audioLevel > 0.015 ? audioLevel : 0;
              const mouthAVal = isSpeaking ? Math.min(1.0, (rawVol > 0.015 ? rawVol * 3.2 : 0.45) + Math.abs(Math.sin(time * 11.5)) * 0.32) : 0;
              const mouthOVal = isSpeaking ? Math.min(0.85, Math.abs(Math.cos(time * 7.5)) * (rawVol > 0.015 ? rawVol * 2.5 : 0.30)) : 0;
              const mouthEVal = isSpeaking ? Math.min(0.75, Math.abs(Math.sin(time * 9.2)) * (rawVol > 0.015 ? rawVol * 2.0 : 0.22)) : 0;

              const blinkPhase = time % 3.6;
              const isBlinking = blinkPhase < 0.12 ? 1.0 : 0.0;
              const gentleSmile = (activeReaction === "joy" || activeReaction === "teach" || activeReaction === "blush") ? 0.75 : 0.15;

              for (const item of this.morphMap.mouthA) item.mesh.morphTargetInfluences[item.idx] = THREE.MathUtils.lerp(item.mesh.morphTargetInfluences[item.idx], mouthAVal, dt * 26);
              for (const item of this.morphMap.mouthO) item.mesh.morphTargetInfluences[item.idx] = THREE.MathUtils.lerp(item.mesh.morphTargetInfluences[item.idx], mouthOVal, dt * 22);
              for (const item of this.morphMap.mouthE) item.mesh.morphTargetInfluences[item.idx] = THREE.MathUtils.lerp(item.mesh.morphTargetInfluences[item.idx], mouthEVal, dt * 22);
              for (const item of this.morphMap.blink)  item.mesh.morphTargetInfluences[item.idx] = THREE.MathUtils.lerp(item.mesh.morphTargetInfluences[item.idx], isBlinking, dt * 32);
              for (const item of this.morphMap.joy)    item.mesh.morphTargetInfluences[item.idx] = THREE.MathUtils.lerp(item.mesh.morphTargetInfluences[item.idx], gentleSmile, dt * 10);
            };

            // Reset all bone transforms to rest pose so accumulated tilts vanish
            m.traverse(n => {
              if (n.isBone) {
                n.quaternion.set(0, 0, 0, 1);
              }
            });

            // Set pivot
            if (!window._moraPivot) {
              window._moraPivot = new THREE.Group();
              m.parent.remove(m);
              window._moraPivot.add(m);
              m.parent.add(window._moraPivot);
            }
            window._moraPivot.rotation.y = Math.PI;
            window.MoraCompanion.setState('idle');
            ctrl.fadeToAction('idle', 0.1);

            return { status: 'cleaned and running pure idle' };
          })()`,
          returnByValue: true
        }
      }));
    });
    ws.on('message', msg => {
      console.log('Result:', JSON.parse(msg).result.result.value);
      ws.close();
    });
  });
});
