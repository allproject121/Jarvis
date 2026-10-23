const fs = require('fs');
let s = fs.readFileSync('app/src/main/java/com/jarvis/assistant/ui/settings/CustomizationStudioActivity.kt', 'utf8');

// 1. tvIcon.text
s = s.replace(
  'when (item.category) {\r\n                "ai_girl"',
  'when (item.category) {\r\n                "hinata_anime" -> tvIcon.text = "🌸"\r\n                "ai_girl"'
);
if (!s.includes('"hinata_anime" -> tvIcon.text = "🌸"')) {
  s = s.replace(
    'when (item.category) {\n                "ai_girl"',
    'when (item.category) {\n                "hinata_anime" -> tvIcon.text = "🌸"\n                "ai_girl"'
  );
}

// 2. autoRotation
s = s.replace(
  'selectedEntityType == "custom_3d" || selectedEntityType == "ai_girl"',
  'selectedEntityType == "custom_3d" || selectedEntityType == "ai_girl" || selectedEntityType == "hinata_anime"'
);

// 3. customLoadScript
s = s.replace(
  'selectedEntityType == "custom_3d" || selectedEntityType == "ai_girl"',
  'selectedEntityType == "custom_3d" || selectedEntityType == "ai_girl" || selectedEntityType == "hinata_anime"'
);

// 4. initTabIdx
s = s.replace(
  '"ai_girl", "custom_3d" -> 1',
  '"hinata_anime", "ai_girl", "custom_3d" -> 1'
);

// 5. tvPreviewPresetName
s = s.replace(
  '"ai_girl" -> "ENTITY: JETT (VALORANT 3D)"',
  '"hinata_anime" -> "ENTITY: HINATA (ANIME COMPANION)"\r\n            "ai_girl" -> "ENTITY: JETT (3D FALLBACK)"'
);
if (!s.includes('"hinata_anime" -> "ENTITY: HINATA (ANIME COMPANION)"')) {
  s = s.replace(
    '"ai_girl" -> "ENTITY: JETT (VALORANT 3D)"',
    '"hinata_anime" -> "ENTITY: HINATA (ANIME COMPANION)"\n            "ai_girl" -> "ENTITY: JETT (3D FALLBACK)"'
  );
}

fs.writeFileSync('app/src/main/java/com/jarvis/assistant/ui/settings/CustomizationStudioActivity.kt', s, 'utf8');
console.log('CustomizationStudioActivity.kt updated successfully');
