const fs = require('fs');

const filePath = 'app/src/main/java/com/jarvis/assistant/ui/settings/CustomizationStudioActivity.kt';
let code = fs.readFileSync(filePath, 'utf8');

// 1. presets.forEach
code = code.replace(
  /presets\.forEach\s*\{\s*\(view,\s*presetKey\)\s*->\s*view\.setOnClickListener\s*\{[\s\S]*?applyPreviewOrbParams\(\)\s*\}\s*\}/,
  `presets.forEach { (view, presetKey) ->
            view.setOnClickListener {
                HudSoundManager.playTapSound()
                selectedPreset = presetKey
                selectedTheme = when (presetKey) {
                    "crimson" -> "red"
                    "white" -> "pearl_white"
                    "light" -> "pearl_white"
                    "violet" -> "amethyst"
                    else -> presetKey
                }
                customColorHex = if (presetKey == "crimson") "#FF4D5E" else if (presetKey == "white") "#FFFFFF" else ""
                updatePresetUI(presetKey)
                updateThemeUI(selectedTheme)
                applyPreviewOrbParams()
            }
        }`
);

// 2. btnApplyCustomColor
code = code.replace(
  /binding\.btnApplyCustomColor\.setOnClickListener\s*\{[\s\S]*?Toast\.makeText\(this,\s*"Enter valid hex format[^"]*",\s*Toast\.LENGTH_SHORT\)\.show\(\)\s*\}\s*\}/,
  `binding.btnApplyCustomColor.setOnClickListener {
            HudSoundManager.playTapSound()
            val hex = binding.etCustomColorHex.text?.toString()?.trim() ?: ""
            if (hex.matches(Regex("^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$"))) {
                customColorHex = hex
                val parsed = Color.parseColor(hex)
                val hsv = FloatArray(3)
                Color.colorToHSV(parsed, hsv)
                val h = hsv[0]
                val s = hsv[1]
                val v = hsv[2]
                selectedTheme = when {
                    s < 0.15f && v > 0.65f -> "pearl_white"
                    s < 0.15f && v <= 0.65f -> "stealth"
                    (h in 345f..360f || h in 0f..15f) || hex.equals("#FF4D5E", ignoreCase = true) || hex.equals("#FF0000", ignoreCase = true) -> "red"
                    hex.equals("#F59E0B", ignoreCase = true) -> "gold"
                    hex.equals("#10B981", ignoreCase = true) || hex.equals("#84CC16", ignoreCase = true) -> "emerald"
                    hex.equals("#EC4899", ignoreCase = true) -> "pink"
                    hex.equals("#FF7700", ignoreCase = true) -> "orange"
                    hex.equals("#6366F1", ignoreCase = true) -> "indigo"
                    hex.equals("#C084FC", ignoreCase = true) -> "amethyst"
                    h in 16f..45f -> "orange"
                    h in 46f..65f -> "gold"
                    h in 66f..165f -> "emerald"
                    h in 166f..205f -> "cyan"
                    h in 206f..255f -> "indigo"
                    h in 256f..295f -> "amethyst"
                    h in 296f..344f -> "pink"
                    else -> "red"
                }
                selectedPreset = if (selectedTheme == "red") "crimson" else if (selectedTheme == "pearl_white") "white" else "custom"
                updatePresetUI(selectedPreset)
                updateThemeUI(selectedTheme)
                applyPreviewOrbParams()
                binding.customizationStudioRoot.setBackgroundColor(ThemeManager.getThemeBgColor(selectedTheme, customColorHex))
                Toast.makeText(this, "Theme color $hex applied!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Enter valid hex format (e.g. #00FFEE or #FF4D5E)", Toast.LENGTH_SHORT).show()
            }
        }`
);

// 3. swatches map
code = code.replace(
  /val swatches = mapOf\(\s*binding\.swatchGold to Pair\("#F59E0B", "gold"\),/,
  `val swatches = mapOf(
            binding.swatchRed to Pair("#FF4D5E", "red"),
            binding.swatchGold to Pair("#F59E0B", "gold"),`
);

// 4. swatches click
code = code.replace(
  /selectedTheme = pair\.second\s*selectedPreset = "custom"/,
  `selectedTheme = pair.second
                selectedPreset = if (pair.second == "red") "crimson" else "custom"`
);

// 5. setupThemeTabControls
code = code.replace(
  /themeCards\.forEach\s*\{\s*\(card,\s*themeKey\)\s*->\s*card\.setOnClickListener\s*\{[\s\S]*?applyPreviewOrbParams\(\)\s*\}\s*\}/,
  `themeCards.forEach { (card, themeKey) ->
            card.setOnClickListener {
                HudSoundManager.playTapSound()
                selectedTheme = themeKey
                selectedPreset = when (themeKey) {
                    "red" -> "crimson"
                    "light", "pearl_white" -> "white"
                    "dark" -> "cyan"
                    else -> themeKey
                }
                customColorHex = if (themeKey == "red") "#FF4D5E" else if (themeKey == "light") "#FFFFFF" else ""
                updateThemeUI(themeKey)
                updatePresetUI(selectedPreset)
                applyPreviewOrbParams()
            }
        }`
);

// 6. updatePresetUI
code = code.replace(
  /private fun updatePresetUI\(activeKey: String\)\s*\{[\s\S]*?binding\.tvPreviewPresetName\.text = "CORE: \$\{activeKey\.uppercase\(\)\}"\s*\}/,
  `private fun updatePresetUI(activeKey: String) {
        val lowerKey = activeKey.lowercase()
        val presetViews = listOf(
            binding.presetCyan to listOf("cyan"),
            binding.presetCrimson to listOf("crimson", "red", "ruby"),
            binding.presetViolet to listOf("violet", "amethyst"),
            binding.presetEmerald to listOf("emerald"),
            binding.presetWhite to listOf("white", "pearl_white", "light"),
            binding.presetSunset to listOf("sunset"),
            binding.presetStealth to listOf("stealth"),
            binding.presetLight to listOf("light", "pearl_white")
        )

        presetViews.forEach { (view, keys) ->
            if (keys.contains(lowerKey)) {
                view.setBackgroundResource(R.drawable.bg_jarvis_chip)
            } else {
                view.setBackgroundResource(R.drawable.bg_jarvis_row_item)
            }
        }

        val displayLabel = if (lowerKey == "crimson" || lowerKey == "red") "CRIMSON RED" else if (lowerKey == "white") "ARC WHITE" else activeKey.uppercase()
        binding.tvPreviewPresetName.text = "CORE: $displayLabel"
    }`
);

// 7. updateThemeUI
code = code.replace(
  /private fun updateThemeUI\(activeKey: String\)\s*\{[\s\S]*?cards\.forEach\s*\{\s*\(card,\s*key\)\s*->[\s\S]*?\}\s*\}/,
  `private fun updateThemeUI(activeKey: String) {
        val lowerKey = activeKey.lowercase()
        val cards = listOf(
            binding.cardThemeNavy to listOf("dark", "cyan"),
            binding.cardThemeStealth to listOf("stealth"),
            binding.cardThemeRed to listOf("red", "crimson", "ruby"),
            binding.cardThemeAmethyst to listOf("amethyst", "violet", "purple"),
            binding.cardThemeLight to listOf("light", "pearl_white", "white")
        )

        cards.forEach { (card, keys) ->
            if (keys.contains(lowerKey)) {
                card.setBackgroundResource(R.drawable.bg_jarvis_chip)
            } else {
                card.setBackgroundResource(R.drawable.bg_jarvis_row_item)
            }
        }
    }`
);

fs.writeFileSync(filePath, code, 'utf8');
console.log('CustomizationStudioActivity.kt updated successfully!');
