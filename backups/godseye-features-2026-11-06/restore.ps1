# Restores God's Eye files to their state before the 2026-11-06 "all features working on phone" pass.
# Run: powershell -ExecutionPolicy Bypass -File restore.ps1
$backup = $PSScriptRoot
$project = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Get-ChildItem -Path (Join-Path $backup "app") -Recurse -File | ForEach-Object {
    $rel = $_.FullName.Substring($backup.Length + 1)
    Copy-Item $_.FullName (Join-Path $project $rel) -Force
    Write-Host "Restored $rel"
}
Write-Host "Done. Rebuild the app."
