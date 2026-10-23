package com.jarvis.assistant.ui.settings

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.databinding.BottomSheetThemeSelectionBinding
import com.jarvis.assistant.ui.home.HomeFragment
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.util.FrostedGlassEffect
import com.jarvis.assistant.util.ThemeManager

class ThemeSelectionBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetThemeSelectionBinding? = null
    private val binding get() = _binding!!
    private val preferences by lazy { JarvisApp.instance.preferences }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetThemeSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            if (FrostedGlassEffect.isBlurEnabled()) {
                FrostedGlassEffect.applyWindowBlur(window)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val currentTheme = preferences.appTheme
        updateCheckmarks(currentTheme)

        val customHex = preferences.customThemeColor
        binding.etCustomHex.setText(customHex)
        try {
            binding.viewCustomPreview.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor(customHex))
        } catch (_: Exception) {}

        binding.cardThemeLight.setOnClickListener { selectTheme("pearl_white", "Pearl White Light") }
        binding.cardThemeDark.setOnClickListener { selectTheme("dark", "Obsidian Dark") }
        binding.cardThemeRed.setOnClickListener { selectTheme("red", "Crimson Ruby") }
        binding.cardThemeSunset.setOnClickListener { selectTheme("sunset", "Peach Sunset") }
        binding.cardThemeStealth.setOnClickListener { selectTheme("stealth", "Stealth OLED Black") }

        binding.btnApplyCustom.setOnClickListener {
            val hex = binding.etCustomHex.text.toString().trim()
            applyCustomColor(hex)
        }

        // Quick Swatches
        binding.swatch1.setOnClickListener { applyCustomColor("#F59E0B") }
        binding.swatch2.setOnClickListener { applyCustomColor("#10B981") }
        binding.swatch3.setOnClickListener { applyCustomColor("#EC4899") }
        binding.swatch4.setOnClickListener { applyCustomColor("#FF7700") }
        binding.swatch5.setOnClickListener { applyCustomColor("#6366F1") }
        binding.swatch6.setOnClickListener { applyCustomColor("#22D3EE") }
        binding.swatchPurple.setOnClickListener { applyCustomColor("#C084FC") }
        binding.swatchWhite.setOnClickListener { applyCustomColor("#FFFFFF") }
        binding.swatchBlack.setOnClickListener { applyCustomColor("#000000") }
    }

    private fun applyCustomColor(hexInput: String) {
        val hex = if (hexInput.startsWith("#")) hexInput else "#$hexInput"
        val parsed = try {
            Color.parseColor(hex)
        } catch (_: Exception) {
            Toast.makeText(requireContext(), "Invalid HEX color format (e.g. #FFFFFF or #22D3EE)", Toast.LENGTH_SHORT).show()
            return
        }

        preferences.customThemeColor = hex

        val hsv = FloatArray(3)
        Color.colorToHSV(parsed, hsv)
        val s = hsv[1]
        val v = hsv[2]

        val targetTheme = when {
            s < 0.15f && v > 0.65f -> "pearl_white"
            s < 0.15f && v <= 0.65f -> "stealth"
            hex.equals("#F59E0B", ignoreCase = true) -> "gold"
            hex.equals("#10B981", ignoreCase = true) || hex.equals("#84CC16", ignoreCase = true) -> "emerald"
            hex.equals("#EC4899", ignoreCase = true) -> "pink"
            hex.equals("#FF7700", ignoreCase = true) -> "orange"
            hex.equals("#6366F1", ignoreCase = true) -> "indigo"
            hex.equals("#FF4D5E", ignoreCase = true) -> "red"
            hex.equals("#C084FC", ignoreCase = true) || hex.equals("#A855F7", ignoreCase = true) -> "amethyst"
            hex.equals("#22D3EE", ignoreCase = true) -> "dark"
            else -> "custom"
        }

        preferences.appTheme = targetTheme
        updateCheckmarks(targetTheme)

        Toast.makeText(requireContext(), "Theme set to $hex", Toast.LENGTH_SHORT).show()

        // Sync with Neural Orb
        val mainActivity = activity as? MainActivity
        val homeFrag = mainActivity?.supportFragmentManager?.fragments
            ?.filterIsInstance<HomeFragment>()
            ?.firstOrNull()
        homeFrag?.setOrbTheme(targetTheme, hex)

        dismiss()
        activity?.recreate()
    }

    private fun selectTheme(themeKey: String, themeName: String) {
        preferences.appTheme = themeKey
        val hex = ThemeManager.getThemeAccentHex()
        preferences.customThemeColor = hex
        updateCheckmarks(themeKey)
        Toast.makeText(requireContext(), "Theme set to $themeName", Toast.LENGTH_SHORT).show()

        val mainActivity = activity as? MainActivity
        val homeFrag = mainActivity?.supportFragmentManager?.fragments
            ?.filterIsInstance<HomeFragment>()
            ?.firstOrNull()
        homeFrag?.setOrbTheme(themeKey, null)

        dismiss()
        activity?.recreate()
    }

    private fun updateCheckmarks(activeTheme: String) {
        binding.ivCheckLight.visibility = if (activeTheme == "pearl_white" || activeTheme == "light") View.VISIBLE else View.GONE
        binding.ivCheckDark.visibility = if (activeTheme == "dark") View.VISIBLE else View.GONE
        binding.ivCheckRed.visibility = if (activeTheme == "red") View.VISIBLE else View.GONE
        binding.ivCheckSunset.visibility = if (activeTheme == "sunset") View.VISIBLE else View.GONE
        binding.ivCheckStealth.visibility = if (activeTheme == "stealth" || activeTheme == "black") View.VISIBLE else View.GONE
        binding.ivCheckCustom.visibility = if (activeTheme == "custom") View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ThemeSelectionBottomSheet"
        fun newInstance() = ThemeSelectionBottomSheet()
    }
}
