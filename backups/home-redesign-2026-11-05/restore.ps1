# Restores the Home screen + bottom dock from before the 2026-11-05 "Nova bento" home redesign.
# Run: powershell -ExecutionPolicy Bypass -File restore.ps1
$backup = $PSScriptRoot
$project = Resolve-Path (Join-Path $PSScriptRoot "..\..")

$files = @(
    "app\src\main\res\layout\fragment_home.xml",
    "app\src\main\res\layout\activity_main.xml",
    "app\src\main\java\com\jarvis\assistant\ui\home\HomeFragment.kt",
    "app\src\main\java\com\jarvis\assistant\ui\home\MainActivity.kt"
)
foreach ($rel in $files) {
    Copy-Item (Join-Path $backup $rel) (Join-Path $project $rel) -Force
    Write-Host "Restored $rel"
}

# Drawables that were added by the redesign (the older bg_nova_glow_* files are NOT touched).
$added = @(
    "bg_nova_card", "bg_nova_icon_button", "bg_nova_icon_chip", "bg_nova_badge",
    "bg_nova_ghost_pill", "bg_nova_dot", "bg_nova_hero_spotlight", "bg_nova_hero_fade",
    "bg_nova_spacer", "bg_nova_hairline", "bg_nova_dock", "bg_nova_dock_active",
    "bg_nova_dock_spotlight", "bg_nova_dock_handle", "bg_nova_mic_on", "bg_nova_mic_off",
    "bg_nova_mic_muted"
)
foreach ($name in $added) {
    $path = Join-Path $project "app\src\main\res\drawable\$name.xml"
    if (Test-Path $path) {
        # Only delete if no other layout/code still references it.
        $refs = Get-ChildItem (Join-Path $project "app\src\main") -Recurse -Include *.xml,*.kt |
            Select-String -Pattern "(@drawable/|R\.drawable\.)$name\b" -List
        if (-not $refs) { Remove-Item $path -Force; Write-Host "Removed drawable $name" }
        else { Write-Host "Kept drawable $name (still referenced)" }
    }
}
Write-Host "Done. Rebuild the app."
