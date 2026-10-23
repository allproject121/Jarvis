const fs = require('fs');
const file = 'app/src/main/java/com/jarvis/assistant/ui/settings/CustomizationStudioActivity.kt';
let content = fs.readFileSync(file, 'utf8');

const target = `            cardView.setOnClickListener {
                HudSoundManager.playTapSound()
                com.jarvis.assistant.util.ModelVaultManager.setActiveVaultItemId(this, item.id)
                selectedEntityType = item.category
                updateEntityTypeUI(selectedEntityType)
                populateVaultCardsUI()
                refreshCustomMediaStatusUI()
                applyPreviewOrbParams()
            }`;

const replacement = `            cardView.setOnClickListener {
                HudSoundManager.playTapSound()
                com.jarvis.assistant.util.ModelVaultManager.setActiveVaultItemId(this, item.id)
                selectedEntityType = item.category
                updateEntityTypeUI(selectedEntityType)
                populateVaultCardsUI()
                refreshCustomMediaStatusUI()
                applyPreviewOrbParams()
                if (item.category == "mora_cyber") {
                    startActivity(android.content.Intent(this@CustomizationStudioActivity, com.jarvis.assistant.ui.character.MoraCharacterActivity::class.java))
                }
            }`;

if (content.replace(/\r\n/g, '\n').includes(target.replace(/\r\n/g, '\n'))) {
  const isCrlf = content.includes('\r\n');
  const targetRe = isCrlf ? target.replace(/\n/g, '\r\n') : target.replace(/\r\n/g, '\n');
  const replRe = isCrlf ? replacement.replace(/\n/g, '\r\n') : replacement.replace(/\r\n/g, '\n');
  content = content.replace(targetRe, replRe);
  fs.writeFileSync(file, content, 'utf8');
  console.log('Successfully updated CustomizationStudioActivity.kt');
} else {
  console.error('Target not found in CustomizationStudioActivity.kt');
}
