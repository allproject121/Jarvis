# Restores the Settings + Chat screens from before the 2026-11-05 settings/chat redesign.
# Run: powershell -ExecutionPolicy Bypass -File restore.ps1
$backup = $PSScriptRoot
$project = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$files = @(
    "app\src\main\res\layout\fragment_settings.xml",
    "app\src\main\res\layout\fragment_chat.xml",
    "app\src\main\res\layout\item_jarvis_chat_message.xml",
    "app\src\main\res\layout\item_jarvis_conv_message.xml",
    "app\src\main\res\layout\item_chat_turn.xml",
    "app\src\main\res\layout\item_jarvis_settings_link_row.xml",
    "app\src\main\java\com\jarvis\assistant\ui\settings\SettingsFragment.kt",
    "app\src\main\java\com\jarvis\assistant\maya\ui\MayaSettingsRows.kt",
    "app\src\main\java\com\jarvis\assistant\ui\chat\ChatFragment.kt",
    "app\src\main\java\com\jarvis\assistant\ui\chat\ChatTabAdapters.kt"
)
foreach ($rel in $files) {
    $src = Join-Path $backup $rel
    if (Test-Path $src) {
        Copy-Item $src (Join-Path $project $rel) -Force
        Write-Host "Restored $rel"
    }
}
# Remove the drawables added by this redesign (prefix bg_set_ / ic_set_).
$drawables = Join-Path $project "app\src\main\res\drawable"
Get-ChildItem $drawables -Filter "bg_set_*.xml" -ErrorAction SilentlyContinue | ForEach-Object { Remove-Item $_.FullName -Force; Write-Host "Removed drawable $($_.Name)" }
Get-ChildItem $drawables -Filter "ic_set_*.xml" -ErrorAction SilentlyContinue | ForEach-Object { Remove-Item $_.FullName -Force; Write-Host "Removed drawable $($_.Name)" }
Write-Host "Done. Rebuild the app."
