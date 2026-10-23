const fs = require('fs');
const path = 'app/build/test-results/testDebugUnitTest';
const files = fs.readdirSync(path).filter(f => f.endsWith('.xml'));
let total = 0, failures = 0, errors = 0, skipped = 0;
files.forEach(f => {
  const content = fs.readFileSync(path + '/' + f, 'utf8');
  const mTests = content.match(/tests="(\d+)"/);
  const mFail = content.match(/failures="(\d+)"/);
  const mErr = content.match(/errors="(\d+)"/);
  const mSkip = content.match(/skipped="(\d+)"/);
  if (mTests) total += parseInt(mTests[1]);
  if (mFail) failures += parseInt(mFail[1]);
  if (mErr) errors += parseInt(mErr[1]);
  if (mSkip) skipped += parseInt(mSkip[1]);
});
console.log(JSON.stringify({ total, failures, errors, skipped, xmlCount: files.length }, null, 2));
