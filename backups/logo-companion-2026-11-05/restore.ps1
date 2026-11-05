# Undo the 2026-11-05 "JARVIS logo + app icon, Companion mode removed, Nova design fixes" change.
# Run: powershell -ExecutionPolicy Bypass -File restore.ps1
$backup = $PSScriptRoot
$project = Resolve-Path (Join-Path $PSScriptRoot "..\..")

# 1. Every file that was edited goes back to its saved copy.
Get-ChildItem (Join-Path $backup "app") -Recurse -File | ForEach-Object {
    $rel = $_.FullName.Substring($backup.Length + 1)
    if ($rel -eq "app\src\main\bg_nova_dock_active.xml") { return }   # handled below
    $dest = Join-Path $project $rel
    New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
    Copy-Item $_.FullName $dest -Force
    Write-Host "Restored $rel"
}

# 2. Files that were deleted (companion home, character sheet, 18+ gate, secret settings) come back.
$deleted = Join-Path $backup "deleted"
Get-ChildItem $deleted -Recurse -File | ForEach-Object {
    $rel = $_.FullName.Substring($deleted.Length + 1)
    $dest = Join-Path $project $rel
    New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
    Copy-Item $_.FullName $dest -Force
    Write-Host "Brought back $rel"
}

# 3. Drawables / colours.
$res = Join-Path $project "app\src\main\res"
Copy-Item (Join-Path $backup "app\src\main\bg_nova_dock_active.xml") (Join-Path $res "drawable\bg_nova_dock_active.xml") -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $res "color\nova_dock_active_fill.xml") -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $res "drawable-nodpi\ic_jarvis_brand_logo.png") -Force -ErrorAction SilentlyContinue
Copy-Item (Join-Path $backup "ic_jarvis_brand_logo.png") (Join-Path $res "drawable\ic_jarvis_brand_logo.png") -Force

Write-Host "Done. NOTE: the one-time migration already ran on phones that installed the new APK (companion turned off). Rebuild the app."
