const { exec, spawn } = require('child_process');
const fs = require('fs');

const ADB = '"C:\\Android\\Sdk\\platform-tools\\adb.exe" -s 10BG1D01DA007QG';

function runCmd(cmd) {
  return new Promise((resolve, reject) => {
    exec(cmd, (err, stdout, stderr) => {
      if (err) resolve({ err, stdout, stderr });
      else resolve({ stdout, stderr });
    });
  });
}

function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

async function evalJs(js) {
  const escaped = js.replace(/"/g, '\\"');
  // Send via adb shell am broadcast or eval via chrome devtools / touch
  // We can poke at different Y coordinates:
  // orbContainer center is X=630, Y=1150
  // Mora body:
  // Headpat: Y=750 (hitY >= 1.2)
  // Chest / Body: Y=1000 (hitY >= 0.6) -> triggers pervert!
  // Legs: Y=1350 (hitY < 0.6) -> triggers legs!
}

async function main() {
  console.log("=== STARTING LIVE DEVICE RECORDING VERIFICATION ===");
  
  // 1. Ensure MainActivity is running
  await runCmd(`${ADB} shell am start -n com.jarvis.assistant/.ui.home.MainActivity`);
  await sleep(2000);

  // 2. Start screenrecord in background
  console.log("Starting 16s screen recording on device...");
  runCmd(`${ADB} shell screenrecord --time-limit 16 /sdcard/mora_test_verify.mp4`);
  await sleep(1500);

  // t = 2s: Idle observed
  console.log("t = 2s: Observing Natural Idle...");
  await sleep(2000);

  // t = 4s: Tap Chest (X=630, Y=1000) -> Triggers Pervert / Defensive posture
  console.log("t = 4s: Tapping Chest to trigger Pervert/Defensive reaction...");
  await runCmd(`${ADB} shell input tap 630 1000`);
  await sleep(3500);

  // t = 7.5s: Tap Head (X=630, Y=780) -> Triggers Headpat / Joy
  console.log("t = 7.5s: Tapping Head to trigger Joy reaction...");
  await runCmd(`${ADB} shell input tap 630 780`);
  await sleep(3500);

  // t = 11s: Tap Legs (X=630, Y=1380) -> Triggers Curious/Legs reaction
  console.log("t = 11s: Tapping Legs to trigger Legs reaction...");
  await runCmd(`${ADB} shell input tap 630 1380`);
  await sleep(3500);

  // t = 14.5s: Wait for recording to finalize
  console.log("Waiting for recording to finalize...");
  await sleep(3000);

  // 3. Pull recording
  console.log("Pulling video from device...");
  await runCmd(`${ADB} pull /sdcard/mora_test_verify.mp4 scratch/mora_test_verify.mp4`);
  
  if (fs.existsSync('scratch/mora_test_verify.mp4')) {
    const stats = fs.statSync('scratch/mora_test_verify.mp4');
    console.log(`Video pulled successfully: ${stats.size} bytes`);
  } else {
    console.error("Failed to pull video");
  }
}

main().catch(console.error);
