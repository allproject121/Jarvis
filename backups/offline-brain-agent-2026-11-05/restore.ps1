# Restores the files saved before the "offline brain agent upgrade" (2026-11-05, evening).
# Run from anywhere:  powershell -ExecutionPolicy Bypass -File restore.ps1
$backup = $PSScriptRoot
$project = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Get-ChildItem -Path (Join-Path $backup "app") -Recurse -File | ForEach-Object {
    $rel = $_.FullName.Substring($backup.Length + 1)
    $dest = Join-Path $project $rel
    Copy-Item -LiteralPath $_.FullName -Destination $dest -Force
    Write-Host "Restored $rel"
}
# New files added by the change (delete them so the project is exactly as before)
$added = @(
  "app\src\main\java\com\jarvis\assistant\ai\local\OfflineToolRouter.kt"
)
foreach ($a in $added) { $p = Join-Path $project $a; if (Test-Path $p) { Remove-Item $p -Force; Write-Host "Removed $a" } }
Write-Host "Done. Rebuild the app."
