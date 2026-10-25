const fs = require('fs');
const content = fs.readFileSync('app/src/main/java/com/jarvis/assistant/ui/godseye/GodsEyeMcpClient.kt', 'utf8');
let inString = false;
let inLineComment = false;
let inBlockComment = false;
let braceCount = 0;
let lines = content.split('\n');

for (let lineIdx = 0; lineIdx < lines.length; lineIdx++) {
  const line = lines[lineIdx];
  const prevCount = braceCount;
  inLineComment = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    const prev = i > 0 ? line[i-1] : '';
    if (inLineComment) continue;
    if (inBlockComment) {
      if (prev === '*' && ch === '/') inBlockComment = false;
      continue;
    }
    if (inString) {
      if (ch === '"' && prev !== '\\') inString = false;
      continue;
    }
    if (ch === '/' && line[i+1] === '/') { inLineComment = true; break; }
    if (ch === '/' && line[i+1] === '*') { inBlockComment = true; i++; continue; }
    if (ch === '"') { inString = true; continue; }
    if (ch === '{') braceCount++;
    if (ch === '}') braceCount--;
  }
  if (prevCount === 1 && braceCount > 1) {
    console.log(`L${lineIdx+1} entered >1: ${line.trim().substring(0, 80)}`);
  }
  if (prevCount > 1 && braceCount === 1) {
    console.log(`L${lineIdx+1} returned to 1: ${line.trim().substring(0, 80)}`);
  }
  if (braceCount === 0) {
    console.log(`L${lineIdx+1} returned to 0: ${line.trim().substring(0, 80)}`);
  }
}
