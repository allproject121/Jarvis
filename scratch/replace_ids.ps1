$replacements = @{
    "btnNameChipMyra" = "btnNameChipJarvisCompanion"
    "cardPersonaMyra" = "cardPersonaJarvisCompanion"
    "btnDialogChipMyra" = "btnDialogChipJarvisCompanion"
    "cardDialogPersonaMyra" = "cardDialogPersonaJarvisCompanion"
    "badgeDialogPersonaMyra" = "badgeDialogPersonaJarvisCompanion"
    "rowMyraModeSwitch" = "rowJarvisModeSwitch"
    "switchMyraMode" = "switchJarvisMode"
    "tvMyraModeSubtitle" = "tvJarvisModeSubtitle"
    "btnSwitchToMyraMode" = "btnSwitchToJarvisMode"
}

$files = @(
    "app\src\main\java\com\jarvis\assistant\ui\settings\CustomizationStudioActivity.kt",
    "app\src\main\java\com\jarvis\assistant\ui\settings\SettingsFragment.kt",
    "app\src\main\java\com\jarvis\assistant\ui\home\HomeFragment.kt",
    "app\src\main\res\layout\activity_customization_studio.xml",
    "app\src\main\res\layout\dialog_hud_assistant_name.xml",
    "app\src\main\res\layout\dialog_hud_persona_selection.xml",
    "app\src\main\res\layout\fragment_home.xml",
    "app\src\main\res\layout\fragment_settings.xml"
)

foreach ($file in $files) {
    if (Test-Path $file) {
        $text = [System.IO.File]::ReadAllText($file)
        foreach ($oldId in $replacements.Keys) {
            $newId = $replacements[$oldId]
            $text = $text.Replace($oldId, $newId)
        }
        [System.IO.File]::WriteAllText($file, $text)
        Write-Host "Updated IDs in $file"
    }
}
