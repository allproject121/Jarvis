const fs = require('fs');
const file = process.argv[2] || 'scratch/uidump.xml';
const xml = fs.readFileSync(file, 'utf8');
const regex = /resource-id="com\.jarvis\.assistant:id\/([^"]+)"[^>]*bounds="([^"]+)"/g;
let match;
while ((match = regex.exec(xml)) !== null) {
  console.log(match[1].padEnd(30), match[2]);
}
