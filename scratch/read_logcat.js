const { execSync } = require('child_process');

try {
  const output = execSync('C:\\Android\\Sdk\\platform-tools\\adb.exe -s 10BG1D01DA007QG logcat -d', { maxBuffer: 10 * 1024 * 1024 }).toString('utf8');
  const lines = output.split('\n');
  const recentLines = lines.slice(-400).filter(l => l.includes('28450') || l.includes('Jarvis') || l.includes('chromium') || l.includes('UniversalHumanoid'));
  console.log(recentLines.slice(-100).join('\n'));
} catch (e) {
  console.error(e.message);
}
