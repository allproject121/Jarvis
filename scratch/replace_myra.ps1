$files = @(
    "app\src\main\java\com\jarvis\assistant\ui\settings\CustomizationStudioActivity.kt",
    "app\src\main\java\com\jarvis\assistant\ui\settings\SettingsFragment.kt",
    "app\src\main\res\layout\activity_customization_studio.xml",
    "app\src\main\res\layout\dialog_hud_assistant_name.xml",
    "app\src\main\res\layout\dialog_hud_persona_selection.xml",
    "app\src\main\res\layout\fragment_home.xml",
    "app\src\main\res\layout\fragment_settings.xml",
    "app\src\main\res\layout\fragment_myra_home.xml"
)

foreach ($file in $files) {
    if (Test-Path $file) {
        $content = [System.IO.File]::ReadAllText($file, [System.Text.Encoding]::UTF8)

        # Exact word replacements for assistant name and labels
        $content = $content -replace '\bMYRA\b', 'JARVIS'
        $content = $content -replace '\bMyra\b', 'Jarvis'
        $content = $content -replace '"myra"', '"jarvis"'
        $content = $content -replace "'myra'", "'jarvis'"

        [System.IO.File]::WriteAllText($file, $content, [System.Text.Encoding]::UTF8)
        Write-Host "Updated $file"
    }
}
