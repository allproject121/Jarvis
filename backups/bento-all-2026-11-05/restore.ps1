# Undo the 2026-11-05 late-night "bento everywhere" pass: Home screen controls merged into
# Arc reactor (Customization) studio, bento restyle of the other XML screens and the Compose pages.
# Run: powershell -ExecutionPolicy Bypass -File restore.ps1
$backup = $PSScriptRoot
$project = Resolve-Path (Join-Path $PSScriptRoot "..\..")

# 1. Edited files -> saved copies.
Get-ChildItem (Join-Path $backup "app") -Recurse -File | ForEach-Object {
    $rel = $_.FullName.Substring($backup.Length + 1)
    $dest = Join-Path $project $rel
    New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
    Copy-Item $_.FullName $dest -Force
    Write-Host "Restored $rel"
}

# 2. Deleted files (home-cards bottom sheet) come back.
$deleted = Join-Path $backup "deleted"
if (Test-Path $deleted) {
    Get-ChildItem $deleted -Recurse -File | ForEach-Object {
        $rel = $_.FullName.Substring($deleted.Length + 1)
        $dest = Join-Path $project $rel
        New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
        Copy-Item $_.FullName $dest -Force
        Write-Host "Brought back $rel"
    }
}

# 3. Files added by this pass.
Remove-Item (Join-Path $project "app\src\main\java\com\jarvis\assistant\ui\compose\designsystem\Bento.kt") -Force -ErrorAction SilentlyContinue
Get-ChildItem (Join-Path $project "app\src\main\res\drawable") -Filter "bg_bento_studio_*.xml" | Remove-Item -Force
Get-ChildItem (Join-Path $project "app\src\main\res\drawable") -Filter "bg_bento_x_*.xml" | Remove-Item -Force
Write-Host "Done. Rebuild the app."
