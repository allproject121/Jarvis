$f = "app\src\main\res\layout\fragment_myra_home.xml"
$text = [System.IO.File]::ReadAllText($f)
$text = $text.Replace("@drawable/bg_myra_card", "@drawable/bg_jarvis_card")
$text = $text.Replace("@drawable/bg_myra_mode_pill", "@drawable/bg_jarvis_mode_pill")
$text = $text.Replace("@drawable/bg_myra_character_active", "@drawable/bg_jarvis_character_active")
$text = $text.Replace("@drawable/bg_myra_character_inactive", "@drawable/bg_jarvis_character_inactive")
$text = $text.Replace("MYRA AI 5.2", "JARVIS AI")
$text = $text.Replace("MYRA AI", "JARVIS AI")
$text = $text.Replace("MYRA", "JARVIS")
$text = $text.Replace("Myra", "Jarvis")
[System.IO.File]::WriteAllText($f, $text)
Write-Host "Updated $f successfully"
