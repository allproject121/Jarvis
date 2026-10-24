const fs = require('fs');
const file = 'app/src/main/java/com/jarvis/assistant/ui/character/MoraCharacterActivity.kt';
let code = fs.readFileSync(file, 'utf8');

// 1. Add ThemeManager.applyDynamicBackground
if (!code.includes('ThemeManager.applyDynamicBackground')) {
  code = code.replace(
    'setContentView(binding.root)',
    'setContentView(binding.root)\n        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)'
  );
}

// 2. Pass theme to loadUrl and onPageFinished
if (!code.includes('currentTheme')) {
  code = code.replace(
    /setMoraState\("idle"\)\s*\}\s*loadUrl\(MORA_URL\)/,
    `setMoraState("idle")
                val theme = JarvisApp.instance.preferences.appTheme
                val hex = JarvisApp.instance.preferences.customThemeColor
                evaluateJavascript("if(window.MoraCompanion && window.MoraCompanion.setTheme){ window.MoraCompanion.setTheme('" + theme + "', '" + hex + "'); }", null)
            }

            val currentTheme = JarvisApp.instance.preferences.appTheme
            val currentHex = JarvisApp.instance.preferences.customThemeColor
            loadUrl(MORA_URL + "?theme=" + currentTheme + "&hex=" + android.net.Uri.encode(currentHex))`
  );
}

fs.writeFileSync(file, code, 'utf8');
console.log('MoraCharacterActivity updated successfully');
