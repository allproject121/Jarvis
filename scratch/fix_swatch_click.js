const fs = require('fs');
const file = 'app/src/main/java/com/jarvis/assistant/ui/settings/CustomizationStudioActivity.kt';
let code = fs.readFileSync(file, 'utf8');
code = code.replace(
  /updatePresetUI\("custom"\)\s*applyPreviewOrbParams\(\)/,
  `updatePresetUI(selectedPreset)\n                updateThemeUI(selectedTheme)\n                applyPreviewOrbParams()`
);
fs.writeFileSync(file, code, 'utf8');
console.log('Updated swatch click in CustomizationStudioActivity');
