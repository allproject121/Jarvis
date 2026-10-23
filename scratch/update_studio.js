const fs = require('fs');
const p = 'app/src/main/java/com/jarvis/assistant/ui/settings/CustomizationStudioActivity.kt';
let c = fs.readFileSync(p, 'utf8');
const target = 'when (item.category) {\r\n                "hinata_anime"';
const replacement = 'when (item.category) {\r\n                "mora_cyber" -> tvIcon.text = "🤖"\r\n                "hinata_anime"';
if (c.includes(target)) {
  c = c.replace(target, replacement);
  fs.writeFileSync(p, c);
  console.log('Successfully updated CustomizationStudioActivity.kt');
} else {
  console.error('Target not found');
}
