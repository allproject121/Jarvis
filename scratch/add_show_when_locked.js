const fs = require('fs');

let mainCode = fs.readFileSync('app/src/main/java/com/jarvis/assistant/ui/home/MainActivity.kt', 'utf8');
const target = 'super.onCreate(savedInstanceState)';
const replacement = `super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }`;
mainCode = mainCode.replace(target, replacement);
fs.writeFileSync('app/src/main/java/com/jarvis/assistant/ui/home/MainActivity.kt', mainCode, 'utf8');

let moraCode = fs.readFileSync('app/src/main/java/com/jarvis/assistant/ui/character/MoraCharacterActivity.kt', 'utf8');
const targetMora = 'super.onCreate(savedInstanceState)';
moraCode = moraCode.replace(targetMora, replacement);
fs.writeFileSync('app/src/main/java/com/jarvis/assistant/ui/character/MoraCharacterActivity.kt', moraCode, 'utf8');

console.log('Successfully updated MainActivity.kt and MoraCharacterActivity.kt');
