const fs = require('fs');
const filePath = 'app/src/main/java/com/jarvis/assistant/ui/character/MoraCharacterActivity.kt';
let content = fs.readFileSync(filePath, 'utf8');

const isCRLF = content.includes('\r\n');
let normalized = content.replace(/\r\n/g, '\n');

const target = `            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
                loadsImagesAutomatically = true
                mediaPlaybackRequiresUserGesture = false
            }

            webChromeClient = WebChromeClient()`;

const replacement = `            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
            clearCache(true)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                loadsImagesAutomatically = true
                mediaPlaybackRequiresUserGesture = false
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(cm: android.webkit.ConsoleMessage?): Boolean {
                    android.util.Log.d("MoraJS", "\${cm?.message()} -- line \${cm?.lineNumber()} of \${cm?.sourceId()}")
                    return true
                }
            }`;

if (!normalized.includes(target)) {
  console.error('Target not found in MoraCharacterActivity.kt');
  process.exit(1);
}

let updated = normalized.replace(target, replacement);
if (isCRLF) updated = updated.replace(/\n/g, '\r\n');
fs.writeFileSync(filePath, updated, 'utf8');
console.log('Successfully updated MoraCharacterActivity.kt');
