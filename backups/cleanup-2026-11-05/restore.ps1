# Restores the full java/res-layout/res-xml/manifest/test state from before the 2026-11-05 cleanup.
# Run: powershell -ExecutionPolicy Bypass -File restore.ps1
$backup = $PSScriptRoot
$project = Resolve-Path (Join-Path $PSScriptRoot "..\..")
foreach ($rel in @("app\src\main\java","app\src\main\res\layout","app\src\main\res\xml","app\src\test")) {
    $dst = Join-Path $project $rel
    if (Test-Path $dst) { Remove-Item $dst -Recurse -Force }
    Copy-Item (Join-Path $backup $rel) $dst -Recurse -Force
    Write-Host "Restored $rel"
}
Copy-Item (Join-Path $backup "app\src\main\AndroidManifest.xml") (Join-Path $project "app\src\main\AndroidManifest.xml") -Force
Write-Host "Done. Rebuild the app."
