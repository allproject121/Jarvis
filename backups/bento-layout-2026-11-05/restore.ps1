# Undo the 2026-11-05 bento LAYOUT pass (Home + Settings structure). Colours/theme are separate
# (backups/redesign-cleanup-2026-11-05).
# Run: powershell -ExecutionPolicy Bypass -File restore.ps1
$backup = $PSScriptRoot
$project = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Get-ChildItem (Join-Path $backup "app") -Recurse -File | ForEach-Object {
    $rel = $_.FullName.Substring($backup.Length + 1)
    Copy-Item $_.FullName (Join-Path $project $rel) -Force
    Write-Host "Restored $rel"
}
foreach ($name in @("bg_bento_dot", "bg_bento_gap", "bg_bento_set_dot_success", "bg_bento_set_circle")) {
    $p = Join-Path $project "app\src\main\res\drawable\$name.xml"
    if (Test-Path $p) { Remove-Item $p -Force; Write-Host "Removed $name" }
}
Write-Host "Done. Rebuild the app."
