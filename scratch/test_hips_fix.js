const http = require('http');
const path = require('path');
const WebSocket = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/ws'));
const { execSync } = require('child_process');

const adb = 'C:\\Android\\Sdk\\platform-tools\\adb.exe';

try {
  const out = execSync(`"${adb}" -s 10BG1D01DA007QG shell cat /proc/net/unix`).toString();
  const match = out.match(/@(webview_devtools_remote_\d+)/);
  if (match) {
    execSync(`"${adb}" -s 10BG1D01DA007QG forward tcp:9222 localabstract:${match[1]}`);
    console.log('Forwarded', match[1]);
  }
} catch (e) { console.error('Forward err:', e.message); }

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
            // In all clips, fix J_Bip_C_Hips.position track values to be in meters (~0.8794)
            for (const [name, action] of Object.entries(ctrl.actions)) {
              const clip = action.getClip();
              const hipsTrack = clip.tracks.find(t => t.name === 'J_Bip_C_Hips.position');
              if (hipsTrack) {
                for (let i = 0; i < hipsTrack.values.length; i += 3) {
                  hipsTrack.values[i] = 0;
                  if (hipsTrack.values[i + 1] > 5) {
                    hipsTrack.values[i + 1] *= 0.01;
                  }
                  hipsTrack.values[i + 2] = -0.0043;
                }
              }
            }
            ctrl.mixer.stopAllAction();
            ctrl.fadeToAction('idle', 0.1);
            return { fixedClips: Object.keys(ctrl.actions) };
          })()`,
          returnByValue: true
        }
      }));
    });
    ws.on('message', msg => {
      console.log('Fixed clips:', JSON.parse(msg).result.result.value);
      ws.close();
      setTimeout(() => {
        execSync(`"${adb}" -s 10BG1D01DA007QG shell screencap -p /sdcard/screen_mora_hips_fixed.png`);
        execSync(`"${adb}" -s 10BG1D01DA007QG pull /sdcard/screen_mora_hips_fixed.png C:\\Users\\Roshan\\.gemini\\antigravity\\brain\\027c6672-b4f2-4696-badc-0e02f6ddd130\\screen_mora_hips_fixed.png`);
        console.log('Pulled screen_mora_hips_fixed.png');
      }, 500);
    });
  });
});
