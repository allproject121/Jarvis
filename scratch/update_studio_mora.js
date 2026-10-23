const fs = require('fs');
let code = fs.readFileSync('app/src/main/java/com/jarvis/assistant/ui/settings/CustomizationStudioActivity.kt', 'utf8');

if (!code.includes('"mora_cyber" -> "ENTITY: MORA')) {
  code = code.replace(
    'binding.tvPreviewPresetName.text = when(entity) {',
    'binding.tvPreviewPresetName.text = when(entity) {\n            "mora_cyber" -> "ENTITY: MORA (REDMAGIC CYBER COMPANION)"'
  );
  fs.writeFileSync('app/src/main/java/com/jarvis/assistant/ui/settings/CustomizationStudioActivity.kt', code, 'utf8');
  console.log('Successfully updated CustomizationStudioActivity.kt with mora_cyber entity name');
} else {
  console.log('Already updated');
}
