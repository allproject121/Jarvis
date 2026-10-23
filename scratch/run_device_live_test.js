const { execSync, spawn } = require('child_process');
const path = require('path');
const fs = require('fs');

const ADB = 'C:\\Android\\Sdk\\platform-tools\\adb.exe';
const DEVICE = '10BG1D01DA007QG';

function adb(cmd) {
  try {
    return execSync(`"${ADB}" -s ${DEVICE} ${cmd}`, { encoding: 'utf8', timeout: 20000 });
  } catch (e) {
    console.error(`ADB error for '${cmd}':`, e.message);
    return '';
  }
}

function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

async function runLiveTest() {
  console.log('Starting live test on phone', DEVICE);

  // Clean up any old recording
  adb('shell rm -f /sdcard/mora_test_live.mp4');

  // Start screenrecord in background
  console.log('Launching screenrecord on device (30 seconds)...');
  const recordProc = spawn(ADB, ['-s', DEVICE, 'shell', 'screenrecord', '--time-limit', '30', '/sdcard/mora_test_live.mp4'], {
    detached: true,
    stdio: 'ignore'
  });
  recordProc.unref();

  // Allow screenrecord to initialize
  await sleep(2000);

  // 1. Idle: 4 seconds
  console.log('[0s - 4s] Testing IDLE state...');
  await sleep(3000);

  // 2. Joy: Tap (150, 2554)
  console.log('[4s - 9s] Tapping JOY (150, 2554)...');
  adb('shell input tap 150 2554');
  await sleep(5000);

  // 3. Learn: Tap (419, 2554)
  console.log('[9s - 14s] Tapping LEARN (419, 2554)...');
  adb('shell input tap 419 2554');
  await sleep(5000);

  // 4. Dance: Tap (738, 2554)
  console.log('[14s - 20s] Tapping DANCE (738, 2554)...');
  adb('shell input tap 738 2554');
  await sleep(6000);

  // 5. Speak: Tap (1062, 2554)
  console.log('[20s - 25s] Tapping SPEAK (1062, 2554)...');
  adb('shell input tap 1062 2554');
  await sleep(5000);

  // 6. Interactive Tap / Wave: Tap Mora on stage (630, 1200)
  console.log('[25s - 29s] Tapping Mora character canvas (630, 1200)...');
  adb('shell input tap 630 1200');
  await sleep(4000);

  console.log('Test sequence completed! Waiting for screenrecord to finalize file...');
  await sleep(3000);

  const localMp4 = 'C:\\Users\\Roshan\\.gemini\\antigravity\\brain\\027c6672-b4f2-4696-badc-0e02f6ddd130\\mora_test_live.mp4';
  console.log('Pulling /sdcard/mora_test_live.mp4 to', localMp4);
  adb(`pull /sdcard/mora_test_live.mp4 "${localMp4}"`);

  if (fs.existsSync(localMp4)) {
    const stats = fs.statSync(localMp4);
    console.log(`Video pulled successfully! Size: ${(stats.size / 1024 / 1024).toFixed(2)} MB`);
  } else {
    console.error('Failed to pull video file');
  }
}

runLiveTest().catch(console.error);
