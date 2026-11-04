# Restores files saved before the feature fixes (2026-11-04). Run: powershell -ExecutionPolicy Bypass -File restore.ps1
$backup = $PSScriptRoot
$project = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Get-ChildItem -Path (Join-Path $backup "app") -Recurse -File | ForEach-Object {
    $rel = $_.FullName.Substring($backup.Length + 1)
    $dest = Join-Path $project $rel
    New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
    Copy-Item -LiteralPath $_.FullName -Destination $dest -Force
    Write-Host "Restored $rel"
}
# Files created by the merge (listed in added-files.txt) are removed
$added = Join-Path $backup "added-files.txt"
if (Test-Path $added) { Get-Content $added | Where-Object { $_.Trim() } | ForEach-Object { $p = Join-Path $project $_; if (Test-Path $p) { Remove-Item $p -Force; Write-Host "Removed $_" } } }
Write-Host "Done. Rebuild the app."
