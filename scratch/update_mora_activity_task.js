const fs = require('fs');
let content = fs.readFileSync('app/src/main/java/com/jarvis/assistant/ui/character/MoraCharacterActivity.kt', 'utf8');

// Normalize line endings temporarily or match with regex
const searchPattern = /lifecycleScope\.launch\s*\{\s*JarvisConversationService\.audioFrequency\.collectLatest\s*\{[\s\S]*?\}\s*\}\s*\}\s*\}/;

const match = content.match(searchPattern);
if (!match) {
  console.log('Search pattern not found');
  process.exit(1);
}

const replacementCode = `lifecycleScope.launch {
            JarvisConversationService.audioFrequency.collectLatest { freq ->
                val amp = freq.amplitude
                val now = System.currentTimeMillis()
                if (now - lastAudioPushTime >= 33L && isPageLoaded) {
                    lastAudioPushTime = now
                    binding.moraWebView.evaluateJavascript(
                        "if(window.MoraCompanion && window.MoraCompanion.setExternalAudio){ window.MoraCompanion.setExternalAudio($amp); }",
                        null
                    )
                }
            }
        }

        lifecycleScope.launch {
            com.jarvis.assistant.automation.agent.AgentTaskEngine.taskState.collectLatest { snapshot ->
                if (snapshot != null) {
                    when (snapshot.currentStatus) {
                        "COMPLETED" -> triggerMoraReaction("success")
                        "FAILED", "BLOCKED" -> triggerMoraReaction("failed")
                        "RUNNING", "EXECUTING", "PLANNING", "IN_PROGRESS" -> triggerMoraReaction("executing")
                    }
                }
            }
        }
    }`;

content = content.replace(searchPattern, replacementCode);
fs.writeFileSync('app/src/main/java/com/jarvis/assistant/ui/character/MoraCharacterActivity.kt', content, 'utf8');
console.log('Successfully updated MoraCharacterActivity.kt with taskState observation!');
