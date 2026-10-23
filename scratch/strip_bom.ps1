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

foreach ($f in $files) {
    if (Test-Path $f) {
        $bytes = [System.IO.File]::ReadAllBytes($f)
        if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
            $newBytes = New-Object byte[] ($bytes.Length - 3)
            [System.Array]::Copy($bytes, 3, $newBytes, 0, $bytes.Length - 3)
            [System.IO.File]::WriteAllBytes($f, $newBytes)
            Write-Host "Stripped BOM from $f"
        }
    }
}
