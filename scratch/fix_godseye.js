const fs = require('fs');
let file = 'app/src/main/java/com/jarvis/assistant/ui/godseye/GodsEyeMcpClient.kt';
let code = fs.readFileSync(file, 'utf8');

code = code.replace(/okHttpClient/g, 'httpClient');
code = code.replace(/catch \(_: Exception\)/g, 'catch (e: Exception)');
code = code.replace(/catch \(_: Throwable\)/g, 'catch (e: Throwable)');

fs.writeFileSync(file, code, 'utf8');
console.log('Fixed GodsEyeMcpClient.kt');
