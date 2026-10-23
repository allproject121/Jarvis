const fs = require('fs');
let s = fs.readFileSync('app/src/main/java/com/jarvis/assistant/util/JarvisWebViewClient.kt', 'utf8');
if (!s.includes('MORA_URL')) {
  s = s.replace(
    'const val ORB_URL = "https://$ASSET_DOMAIN/assets/orb.html"',
    'const val ORB_URL = "https://$ASSET_DOMAIN/assets/orb.html"\n        const val MORA_URL = "https://$ASSET_DOMAIN/assets/mora_character.html"'
  );
  fs.writeFileSync('app/src/main/java/com/jarvis/assistant/util/JarvisWebViewClient.kt', s, 'utf8');
  console.log('Successfully updated JarvisWebViewClient.kt with MORA_URL');
} else {
  console.log('Already has MORA_URL');
}
