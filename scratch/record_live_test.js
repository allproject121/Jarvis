const { execSync, spawn } = require('child_process');
const fs = require('fs');
const path = require('path');

const ADB = 'C:\\Android\\Sdk\\platform-tools\\adb.exe -s 10BG1D01DA007QG';

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function adbShell(cmd) {
  execSync(`${ADB} shell ${cmd}`);
}

async function runLiveTest() {
  console.log('=== STARTING COMPREHENSIVE LIVE DEVICE TEST (CANONICAL MORA) ===');

  // Ensure activity is in foreground
  adbShell('am start -n com.jarvis.assistant/.ui.character.MoraCharacterActivity');
  await sleep(2000);

  // Remove existing recording file on device
  try { adbShell('rm -f /sdcard/mora_canonical_test.mp4'); } catch (_) {}

  console.log('Starting 35-second screen recording on physical phone...');
  const screenrecord = spawn('C:\\Android\\Sdk\\platform-tools\\adb.exe', ['-s', '10BG1D01DA007QG', 'shell', 'screenrecord', '--time-limit=35', '--bit-rate=6000000', '/sdcard/mora_canonical_test.mp4']);

  screenrecord.on('error', (err) => console.error('screenrecord error:', err));
  await sleep(1500);

  // 1. OBSERVE IDLE (0s - 4s)
  console.log('[Phase 1] Observing Idle standing pose (4s)...');
  await sleep(4000);

  // 2. TRIGGER WAVE (4s - 8s)
  console.log('[Phase 2] Tapping Wave (191, 2554)...');
  adbShell('input tap 191 2554');
  await sleep(4200);

  // 3. TRIGGER JOY (8s - 13s)
  console.log('[Phase 3] Tapping Joy (491, 2554)...');
  adbShell('input tap 491 2554');
  await sleep(5000);

  // 4. TRIGGER LEARN (13s - 17s)
  console.log('[Phase 4] Tapping Learn (792, 2554)...');
  adbShell('input tap 792 2554');
  await sleep(4000);

  // 5. TRIGGER DANCE (17s - 23s)
  console.log('[Phase 5] Tapping Dance (1086, 2554)...');
  adbShell('input tap 1086 2554');
  await sleep(6500);

  // 6. SCROLL AND TRIGGER SPEAK (23s - 28s)
  console.log('[Phase 6] Scrolling right to Speak button and tapping...');
  adbShell('input swipe 1100 2554 200 2554 250');
  await sleep(600);
  adbShell('input tap 1050 2554'); // Speak button after scroll
  await sleep(4500);

  // 7. POKE TOUCH REACTION (28s - 31s)
  console.log('[Phase 7] Poking Mora on screen (630, 1100)...');
  adbShell('input tap 630 1100');
  await sleep(3500);

  // 8. IDLE RETURN (31s - 35s)
  console.log('[Phase 8] Observing return to stable Idle pose...');
  await sleep(4000);

  console.log('Waiting for screenrecord to finalize on device...');
  await sleep(2000);

  // Pull video
  const localVideo = path.resolve('scratch/mora_canonical_test.mp4');
  console.log('Pulling /sdcard/mora_canonical_test.mp4 to', localVideo);
  execSync(`${ADB} pull /sdcard/mora_canonical_test.mp4 "${localVideo}"`);

  if (!fs.existsSync(localVideo) || fs.statSync(localVideo).size < 1000) {
    throw new Error('Screen record failed or pulled file is empty');
  }

  console.log('Video pulled successfully! File size:', (fs.statSync(localVideo).size / 1024 / 1024).toFixed(2), 'MB');

  // Extract frames every 2 seconds
  const framesDir = path.resolve('scratch/canonical_frames');
  if (!fs.existsSync(framesDir)) fs.mkdirSync(framesDir, { recursive: true });

  console.log('Extracting video frames with ffmpeg...');
  execSync(`ffmpeg -y -i "${localVideo}" -vf "fps=0.5" "${path.join(framesDir, 'frame_%02d.jpg')}"`);

  const frames = fs.readdirSync(framesDir).filter(f => f.endsWith('.jpg'));
  console.log(`Extracted ${frames.length} frames across the entire 35-second test!`);
  frames.forEach(f => console.log('  ', f));
}

runLiveTest().catch(console.error);
