# Undo the 2026-11-05 evening work: Dark Bento design + contrast, Home-screen settings merge,
# real Backup & restore, old floating avatar, Media studio fit, Driving mode + Live wallpaper removal,
# AI settings pages fixes and debug hooks.
# Run: powershell -ExecutionPolicy Bypass -File restore.ps1
# NOTE: files are restored to their state before this work; later edits by other sessions to the
# same files would be lost too.
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

# 2. Deleted files come back.
$deleted = Join-Path $backup "deleted"
Get-ChildItem $deleted -Recurse -File | ForEach-Object {
    $rel = $_.FullName.Substring($deleted.Length + 1)
    $dest = Join-Path $project $rel
    New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
    Copy-Item $_.FullName $dest -Force
    Write-Host "Brought back $rel"
}

# 3. Files that this work added.
$added = @(
    "app\src\debug\java\com\jarvis\assistant\debug\DebugOpenActivity.kt",
    "app\src\debug\java\com\jarvis\assistant\debug\GeminiTestReceiver.kt",
    "app\src\debug\java\com\jarvis\assistant\debug\ToolTestReceiver.kt",
    "app\src\main\java\com\jarvis\assistant\androllm\agent\confirm\ConfirmationOverlay.kt",
    "app\src\main\java\com\jarvis\assistant\androllm\diagnostics\GeminiSelfTest.kt",
    "app\src\main\java\com\jarvis\assistant\androllm\permissions\handler\JarvisAccessHandlers.kt",
    "app\src\main\res\color\ripple_on_accent.xml",
    "app\src\main\java\com\jarvis\assistant\ai\GeminiTextModels.kt",
    "app\src\main\jniLibs\arm64-v8a\libonnxrt120.so"
)
foreach ($rel in $added) {
    $p = Join-Path $project $rel
    if (Test-Path $p) { Remove-Item $p -Force; Write-Host "Removed $rel" }
}
Write-Host "Done. Rebuild the app."
