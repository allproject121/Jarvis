const fs = require('fs');

// 1. Fix JarvisWebViewClient.kt
{
  const p = 'app/src/main/java/com/jarvis/assistant/util/JarvisWebViewClient.kt';
  let c = fs.readFileSync(p, 'utf8');
  if (!c.includes('import com.jarvis.assistant.JarvisApp')) {
    c = c.replace('import android.content.Context', 'import android.content.Context\nimport com.jarvis.assistant.JarvisApp');
    fs.writeFileSync(p, c, 'utf8');
    console.log('Added JarvisApp import to JarvisWebViewClient.kt');
  }
}

// 2. Fix ModelVaultManager.kt line 209
{
  const p = 'app/src/main/java/com/jarvis/assistant/util/ModelVaultManager.kt';
  let c = fs.readFileSync(p, 'utf8');
  const target = 'if (f.exists() && file.length() > 0) return f';
  const repl = 'if (f.exists() && f.length() > 0) return f';
  if (c.includes(target)) {
    c = c.replace(target, repl);
    fs.writeFileSync(p, c, 'utf8');
    console.log('Fixed file.length -> f.length in ModelVaultManager.kt');
  }
}
