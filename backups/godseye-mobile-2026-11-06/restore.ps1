# Restores God's Eye phone layout + Jarvis connection work of 2026-11-06.
# Run: powershell -ExecutionPolicy Bypass -File restore.ps1
$backup = $PSScriptRoot
$project = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Get-ChildItem -Path (Join-Path $backup "app") -Recurse -File | ForEach-Object {
    $rel = $_.FullName.Substring($backup.Length + 1)
    Copy-Item $_.FullName (Join-Path $project $rel) -Force
    Write-Host "Restored $rel"
}
# Files added by this change
foreach ($added in @("app\src\main\assets\godseye\jarvis-mobile.css", "app\src\main\assets\godseye\jarvis-mobile.js")) {
    $p = Join-Path $project $added
    if (Test-Path $p) { Remove-Item $p -Force; Write-Host "Removed $added" }
}
Write-Host "Done. Rebuild the app."
