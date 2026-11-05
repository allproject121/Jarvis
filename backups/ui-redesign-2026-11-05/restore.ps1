# Restores the UI (layouts, drawables, values, ThemeManager, home screen) from before the 2026-11-05 redesign.
# Run: powershell -ExecutionPolicy Bypass -File restore.ps1
$backup = $PSScriptRoot
$project = Resolve-Path (Join-Path $PSScriptRoot "..\..")
foreach ($rel in @("app\src\main\res\layout","app\src\main\res\drawable","app\src\main\res\values")) {
    $dst = Join-Path $project $rel
    if (Test-Path $dst) { Remove-Item $dst -Recurse -Force }
    Copy-Item (Join-Path $backup $rel) $dst -Recurse -Force
    Write-Host "Restored $rel"
}
Copy-Item (Join-Path $backup "app\src\main\java\com\jarvis\assistant\util\*") (Join-Path $project "app\src\main\java\com\jarvis\assistant\util") -Force
Copy-Item (Join-Path $backup "app\src\main\java\com\jarvis\assistant\ui\home\*") (Join-Path $project "app\src\main\java\com\jarvis\assistant\ui\home") -Force
Copy-Item (Join-Path $backup "AppPreferences.kt") (Join-Path $project "app\src\main\java\com\jarvis\assistant\data\preferences\AppPreferences.kt") -Force
Write-Host "Done. Rebuild the app."
