package com.jarvis.assistant.ui.matrix

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.macro.MacroExecutionResult
import com.jarvis.assistant.databinding.ActivityJarvisMatrixBinding
import com.jarvis.assistant.marketing.YouTubeCreatorToolkit
import com.jarvis.assistant.util.HudSoundManager
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class JarvisMatrixActivity : AppCompatActivity() {

    private lateinit var binding: ActivityJarvisMatrixBinding
    private val app by lazy { application as JarvisApp }
    private var currentGeneratedBitmap: android.graphics.Bitmap? = null
    private var appliedThemeKey: String = ""
    private var appliedCustomHex: String = ""

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            triggerIntruderPhotoCapture()
        } else {
            Toast.makeText(this, "Camera permission required for intruder detection.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyTheme(this)
        appliedThemeKey = app.preferences.appTheme
        appliedCustomHex = app.preferences.customThemeColor
        binding = ActivityJarvisMatrixBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sysBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density
            val safeTop = if (sysBars.top > 0) sysBars.top else (12 * density).toInt()
            binding.root.setPadding(sysBars.left, safeTop, sysBars.right, sysBars.bottom)
            insets
        }

        setupListeners()
        setupCategoryFilters()
        updateGuardStatusUi()
        checkAccessibilityStatus()
    }

    override fun onResume() {
        super.onResume()
        val prefs = app.preferences
        if (appliedThemeKey != prefs.appTheme || appliedCustomHex != prefs.customThemeColor) {
            appliedThemeKey = prefs.appTheme
            appliedCustomHex = prefs.customThemeColor
            recreate()
            return
        }
        updateGuardStatusUi()
        checkAccessibilityStatus()
    }

    private fun setupCategoryFilters() {
        val securitySections = listOf(binding.secVoiceGuardian, binding.secBiometricAppLock)
        val voiceSections = listOf(binding.secPocketTts, binding.secBarcodeScanner)
        val automationSections = listOf(binding.secAiMacros, binding.secLinuxTermux, binding.secCreatorStudio)
        val smartHomeSections = listOf(binding.secSmartHome, binding.secLiveWallpaper, binding.secStocks)
        val allSections = securitySections + voiceSections + automationSections + smartHomeSections

        val chips = listOf(
            binding.chipFilterAll,
            binding.chipFilterSecurity,
            binding.chipFilterVoice,
            binding.chipFilterAutomation,
            binding.chipFilterSmartHome
        )

        fun selectCategory(activeChip: android.widget.TextView, visibleSections: List<View>?) {
            HudSoundManager.playTapSound()
            chips.forEach { chip ->
                chip.setBackgroundResource(com.jarvis.assistant.R.drawable.bg_jarvis_row_item)
                chip.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            }
            activeChip.setBackgroundResource(com.jarvis.assistant.R.drawable.bg_jarvis_pill_button)
            activeChip.setTextColor(android.graphics.Color.WHITE)

            allSections.forEach { section ->
                section.visibility = if (visibleSections == null || section in visibleSections) View.VISIBLE else View.GONE
            }
        }

        binding.chipFilterAll.setOnClickListener { selectCategory(binding.chipFilterAll, null) }
        binding.chipFilterSecurity.setOnClickListener { selectCategory(binding.chipFilterSecurity, securitySections) }
        binding.chipFilterVoice.setOnClickListener { selectCategory(binding.chipFilterVoice, voiceSections) }
        binding.chipFilterAutomation.setOnClickListener { selectCategory(binding.chipFilterAutomation, automationSections) }
        binding.chipFilterSmartHome.setOnClickListener { selectCategory(binding.chipFilterSmartHome, smartHomeSections) }
    }

    private fun checkAccessibilityStatus() {
        if (!JarvisAccessibilityService.isEnabled()) {
            binding.tvMacroStatus.text = "âš ï¸ Accessibility Service OFF. Tap macro button to enable."
            binding.tvMacroStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_light))
        } else {
            binding.tvMacroStatus.text = "âœ… JARVIS Accessibility Service is ACTIVE."
            binding.tvMacroStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
        }
    }

    private fun ensureAccessibilityOrPrompt(): Boolean {
        if (!JarvisAccessibilityService.isEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("JARVIS Accessibility Required")
                .setMessage("To allow JARVIS to automatically open apps, type prompts, and save images, please enable 'JARVIS Assistant' in Accessibility Settings.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return false
        }
        return true
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            HudSoundManager.playTapSound()
            finish()
        }

        // --- 1. AI Direct Generation & Macros ---
        binding.btnDirectGenerateImg.setOnClickListener {
            HudSoundManager.playTapSound()
            val prompt = binding.etChatGptPrompt.text.toString().trim()
            if (prompt.isBlank()) {
                Toast.makeText(this, "Please enter an image prompt", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.pbAiImageLoading.visibility = View.VISIBLE
            binding.tvAiImageStatus.visibility = View.VISIBLE
            binding.tvAiImageStatus.text = "Generating direct AI image (Flux Model)..."
            binding.ivGeneratedAiImage.visibility = View.GONE

            lifecycleScope.launch {
                val bitmap = app.directAiImageGenerator.generateImage(prompt)
                withContext(Dispatchers.Main) {
                    binding.pbAiImageLoading.visibility = View.GONE
                    if (bitmap != null) {
                        currentGeneratedBitmap = bitmap
                        binding.ivGeneratedAiImage.setImageBitmap(bitmap)
                        binding.ivGeneratedAiImage.visibility = View.VISIBLE
                        binding.layoutAiImageActions.visibility = View.VISIBLE
                        binding.tvAiImageStatus.text = "âœ… Image Generated Successfully (Flux Model)"
                        Toast.makeText(this@JarvisMatrixActivity, "AI Image Generated! Tap Save to keep it in App/Gallery.", Toast.LENGTH_SHORT).show()
                    } else {
                        binding.layoutAiImageActions.visibility = View.GONE
                        binding.tvAiImageStatus.text = "âŒ Generation failed. Check internet connection."
                        Toast.makeText(this@JarvisMatrixActivity, "Image generation failed.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        binding.btnSaveAiImage.setOnClickListener {
            HudSoundManager.playTapSound()
            val bmp = currentGeneratedBitmap
            if (bmp != null) {
                saveImageToAppAndGallery(bmp)
            } else {
                Toast.makeText(this, "No image to save", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnShareAiImage.setOnClickListener {
            HudSoundManager.playTapSound()
            val bmp = currentGeneratedBitmap
            if (bmp != null) {
                shareImage(bmp)
            }
        }

        binding.btnViewFullAiImage.setOnClickListener {
            HudSoundManager.playTapSound()
            val bmp = currentGeneratedBitmap
            if (bmp != null) {
                viewFullScreen(bmp)
            }
        }

        binding.btnGenerateChatGptImg.setOnClickListener {
            HudSoundManager.playTapSound()
            val prompt = binding.etChatGptPrompt.text.toString().trim()
            if (prompt.isBlank()) {
                Toast.makeText(this, "Please enter an image prompt", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!ensureAccessibilityOrPrompt()) return@setOnClickListener

            Toast.makeText(this, "JARVIS Macro: Initiating ChatGPT Image Workflow...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val res = app.macroEngine.executeMacroByName("chatgpt image", mapOf("image prompt" to prompt))
                when (res) {
                    is MacroExecutionResult.Success -> Toast.makeText(this@JarvisMatrixActivity, "ChatGPT Macro Complete!", Toast.LENGTH_LONG).show()
                    is MacroExecutionResult.Failure -> Toast.makeText(this@JarvisMatrixActivity, "Macro Notice: ${res.error}", Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.btnGeminiImage.setOnClickListener {
            HudSoundManager.playTapSound()
            val prompt = binding.etGeminiPrompt.text.toString().trim()
            if (prompt.isBlank()) {
                Toast.makeText(this, "Please enter an image prompt", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!ensureAccessibilityOrPrompt()) return@setOnClickListener

            Toast.makeText(this, "JARVIS Macro: Initiating Gemini Image Workflow...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val res = app.macroEngine.executeMacroByName("gemini image", mapOf("image prompt" to prompt))
                when (res) {
                    is MacroExecutionResult.Success -> Toast.makeText(this@JarvisMatrixActivity, "Gemini Image Macro Complete!", Toast.LENGTH_LONG).show()
                    is MacroExecutionResult.Failure -> Toast.makeText(this@JarvisMatrixActivity, "Macro Notice: ${res.error}", Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.btnGeminiVideo.setOnClickListener {
            HudSoundManager.playTapSound()
            val prompt = binding.etGeminiPrompt.text.toString().trim()
            if (prompt.isBlank()) {
                Toast.makeText(this, "Please enter a video prompt", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!ensureAccessibilityOrPrompt()) return@setOnClickListener

            Toast.makeText(this, "JARVIS Macro: Initiating Gemini Video Workflow...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val res = app.macroEngine.executeMacroByName("gemini video", mapOf("video prompt" to prompt))
                when (res) {
                    is MacroExecutionResult.Success -> Toast.makeText(this@JarvisMatrixActivity, "Gemini Video Macro Complete!", Toast.LENGTH_LONG).show()
                    is MacroExecutionResult.Failure -> Toast.makeText(this@JarvisMatrixActivity, "Macro Notice: ${res.error}", Toast.LENGTH_LONG).show()
                }
            }
        }

        // --- 2. Voice Guardian & Watchman ---
        binding.btnToggleGuard.setOnClickListener {
            HudSoundManager.playTapSound()
            app.voiceGuardian.isArmed = !app.voiceGuardian.isArmed
            updateGuardStatusUi()
        }

        binding.btnTestWatchmanPhoto.setOnClickListener {
            HudSoundManager.playTapSound()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                triggerIntruderPhotoCapture()
            }
        }

        // --- 3. Linux Terminal & Native Shell Execution ---
        binding.btnRunNativeShell.setOnClickListener {
            val cmd = binding.etTermuxCommand.text.toString().trim().ifBlank { "uname -a" }
            executeNativeShell(cmd)
        }

        binding.btnChipUname.setOnClickListener { executeNativeShell("uname -a") }
        binding.btnChipDf.setOnClickListener { executeNativeShell("df -h") }
        binding.btnChipUptime.setOnClickListener { executeNativeShell("uptime") }
        binding.btnChipDate.setOnClickListener { executeNativeShell("date") }

        binding.btnRunTermux.setOnClickListener {
            HudSoundManager.playTapSound()
            val cmd = binding.etTermuxCommand.text.toString().trim()
            if (cmd.isBlank()) {
                Toast.makeText(this, "Enter a command first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!app.termuxBridge.isTermuxInstalled()) {
                AlertDialog.Builder(this)
                    .setTitle("Termux App Not Installed")
                    .setMessage("You can execute commands natively without Termux! Would you like to run it directly on device?")
                    .setPositiveButton("Run Natively") { _, _ -> executeNativeShell(cmd) }
                    .setNegativeButton("Get Termux") { _, _ ->
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/")))
                    }
                    .show()
                return@setOnClickListener
            }
            val success = app.termuxBridge.runCommand(cmd)
            binding.tvTermuxResult.text = if (success) "Dispatched to Termux: $cmd" else "Termux execution failed."
        }

        binding.btnRunUbuntu.setOnClickListener {
            HudSoundManager.playTapSound()
            val cmd = binding.etTermuxCommand.text.toString().trim()
            if (cmd.isBlank()) return@setOnClickListener
            val success = app.termuxBridge.runInUbuntu(cmd)
            binding.tvTermuxResult.text = if (success) "Dispatched to PRoot Ubuntu: $cmd" else "Failed to dispatch to Ubuntu."
        }

        // --- 4. Stock Market & Live Quotes ---
        binding.btnFetchStock.setOnClickListener {
            HudSoundManager.playTapSound()
            val symbol = binding.etStockSymbol.text.toString().trim()
            if (symbol.isBlank()) return@setOnClickListener
            fetchStockQuote(symbol)
        }

        binding.btnChipReliance.setOnClickListener { fetchStockQuote("RELIANCE.NS") }
        binding.btnChipTcs.setOnClickListener { fetchStockQuote("TCS.NS") }
        binding.btnChipBtc.setOnClickListener { fetchStockQuote("BTC-USD") }
        binding.btnChipNifty.setOnClickListener { fetchStockQuote("^NSEI") }

        binding.btnTrendingStocks.setOnClickListener {
            HudSoundManager.playTapSound()
            binding.tvStockResult.text = "Fetching trending stocks..."
            lifecycleScope.launch {
                val trending = app.financeClient.getTrending()
                withContext(Dispatchers.Main) {
                    if (trending.isNotEmpty()) {
                        binding.tvStockResult.text = "Trending: " + trending.take(8).joinToString(", ")
                    } else {
                        binding.tvStockResult.text = "Trending stocks currently unavailable. Try symbol lookup."
                    }
                }
            }
        }

        // --- 5. Creator Studio & SEO ---
        binding.btnGenerateScript.setOnClickListener {
            HudSoundManager.playTapSound()
            val topic = binding.etScriptTopic.text.toString().trim()
            if (topic.isBlank()) {
                Toast.makeText(this, "Enter a topic first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.tvScriptResult.text = "JARVIS Neural Engine formulating high-retention script for '$topic'..."
            binding.btnGenerateScript.isEnabled = false
            lifecycleScope.launch {
                val script = YouTubeCreatorToolkit.generateDynamicScript(this@JarvisMatrixActivity, topic, "Reel", inHinglish = true)
                withContext(Dispatchers.Main) {
                    binding.btnGenerateScript.isEnabled = true
                    binding.tvScriptResult.text = script
                }
            }
        }

        binding.btnCopyScript.setOnClickListener {
            HudSoundManager.playTapSound()
            val script = binding.tvScriptResult.text.toString()
            if (script.isNotBlank() && !script.contains("will appear here")) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("JARVIS Script", script))
                Toast.makeText(this, "Script copied to clipboard!", Toast.LENGTH_SHORT).show()
            }
        }

        // --- 6. On-Device PocketTTS Neural Voice Engine ---
        binding.btnPhraseHindi.setOnClickListener {
            binding.etPocketTtsInput.setText("à¤¨à¤®à¤¸à¥à¤¤à¥‡ à¤¸à¤°, à¤®à¥ˆà¤‚ à¤œà¤¾à¤°à¥à¤µà¤¿à¤¸ à¤¹à¥‚à¤à¥¤ à¤‘à¤² à¤¸à¤¿à¤¸à¥à¤Ÿà¤®à¥à¤¸ à¤¨à¥‰à¤®à¤¿à¤¨à¤²à¥¤")
        }

        binding.btnPhraseEnglish.setOnClickListener {
            binding.etPocketTtsInput.setText("All systems operational. Tactical protocols engaged and ready for commands.")
        }

        binding.btnPocketTtsSpeak.setOnClickListener {
            HudSoundManager.playTapSound()
            val text = binding.etPocketTtsInput.text.toString().trim()
            if (text.isBlank()) {
                Toast.makeText(this, "Please enter text to synthesize", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.tvPocketTtsStatus.text = "PocketTTS: Synthesizing with native ONNX + Kyutai Mimi vocoder..."
            com.jarvis.assistant.voice.PocketTtsEngine.getInstance(this).speak(text) {
                runOnUiThread {
                    binding.tvPocketTtsStatus.text = "PocketTTS: Speech playback completed (24kHz 16-bit PCM)."
                }
            }
        }

        binding.btnPocketTtsStop.setOnClickListener {
            HudSoundManager.playTapSound()
            com.jarvis.assistant.voice.PocketTtsEngine.getInstance(this).stop()
            binding.tvPocketTtsStatus.text = "PocketTTS: Playback stopped."
        }

        // --- 7. Stark Arc Reactor Live Wallpaper ---
        binding.btnApplyLiveWallpaper.setOnClickListener {
            HudSoundManager.playTapSound()
            try {
                val intent = Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                    putExtra(
                        android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        android.content.ComponentName(this@JarvisMatrixActivity, com.jarvis.assistant.wallpaper.JarvisWallpaperService::class.java)
                    )
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    startActivity(Intent(android.app.WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
                } catch (e2: Exception) {
                    Toast.makeText(this, "JARVIS Live Wallpaper is ready in Wallpaper settings", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // --- 8. Protocol 7 Biometric AppLock ---
        binding.btnLaunchAppLockTest.setOnClickListener {
            HudSoundManager.playTapSound()
            val intent = Intent(this, com.jarvis.assistant.ui.facelock.JarvisAppLockActivity::class.java).apply {
                putExtra(com.jarvis.assistant.ui.facelock.JarvisAppLockActivity.EXTRA_APP_LABEL, "JARVIS Matrix Hub")
                putExtra(com.jarvis.assistant.ui.facelock.JarvisAppLockActivity.EXTRA_PACKAGE_NAME, packageName)
            }
            startActivity(intent)
        }

        // --- 9. On-Device Barcode & QR Scanner ---
        binding.btnScanScreenBarcode.setOnClickListener {
            HudSoundManager.playTapSound()
            binding.tvBarcodeScanResult.text = "Scanning screen with Barcode & QR engine..."
            lifecycleScope.launch {
                val bmp = JarvisAccessibilityService.captureScreenBitmap()
                if (bmp != null) {
                    val scanner = com.jarvis.assistant.vision.BarcodeScannerEngine(this@JarvisMatrixActivity)
                    val codes = scanner.scanBitmap(bmp)
                    withContext(Dispatchers.Main) {
                        if (codes.isNotEmpty()) {
                            val scanned = codes.joinToString("\n") { "${it.format}: ${it.rawValue}" }
                            binding.tvBarcodeScanResult.text = "Detected Barcodes / QR:\n$scanned"
                            com.jarvis.assistant.voice.PocketTtsEngine.getInstance(this@JarvisMatrixActivity)
                                .speak("Barcode detected: ${codes.first().rawValue}")
                            Toast.makeText(this@JarvisMatrixActivity, "Found ${codes.size} code(s)!", Toast.LENGTH_SHORT).show()
                        } else {
                            binding.tvBarcodeScanResult.text = "Scan complete: No QR / Barcodes visible on screen."
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        binding.tvBarcodeScanResult.text = "Screen capture requires JARVIS Accessibility Service. Tap to enable."
                        ensureAccessibilityOrPrompt()
                    }
                }
            }
        }

        // --- 10. Stark Smart Home & IoT Engine ---
        binding.btnSmartHomeOn.setOnClickListener {
            HudSoundManager.playTapSound()
            lifecycleScope.launch {
                val res = app.smartHomeManager.controlDevice("Living Room & Ambient Lights", "on")
                withContext(Dispatchers.Main) {
                    binding.tvSmartHomeStatus.text = res
                    com.jarvis.assistant.voice.PocketTtsEngine.getInstance(this@JarvisMatrixActivity).speak(res)
                    Toast.makeText(this@JarvisMatrixActivity, res, Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnSmartHomeOff.setOnClickListener {
            HudSoundManager.playTapSound()
            lifecycleScope.launch {
                val res = app.smartHomeManager.controlDevice("All Smart Lights", "off")
                withContext(Dispatchers.Main) {
                    binding.tvSmartHomeStatus.text = res
                    com.jarvis.assistant.voice.PocketTtsEngine.getInstance(this@JarvisMatrixActivity).speak(res)
                    Toast.makeText(this@JarvisMatrixActivity, res, Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnSmartHomeBlink.setOnClickListener {
            HudSoundManager.playTapSound()
            lifecycleScope.launch {
                val res = app.smartHomeManager.controlDevice("Stark Core Ambient Lights", "blink", intervalSec = 2)
                withContext(Dispatchers.Main) {
                    binding.tvSmartHomeStatus.text = res
                    com.jarvis.assistant.voice.PocketTtsEngine.getInstance(this@JarvisMatrixActivity).speak(res)
                    Toast.makeText(this@JarvisMatrixActivity, res, Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnSmartHomeChase.setOnClickListener {
            HudSoundManager.playTapSound()
            lifecycleScope.launch {
                val res = app.smartHomeManager.controlDevice("Arc Reactor Array", "chase")
                withContext(Dispatchers.Main) {
                    binding.tvSmartHomeStatus.text = res
                    com.jarvis.assistant.voice.PocketTtsEngine.getInstance(this@JarvisMatrixActivity).speak(res)
                    Toast.makeText(this@JarvisMatrixActivity, res, Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnConfigureSmartHome.setOnClickListener {
            HudSoundManager.playTapSound()
            val intent = android.content.Intent(this, com.jarvis.assistant.ui.settings.ApiConnectionActivity::class.java)
            startActivity(intent)
        }

        binding.btnTestSmartHome.setOnClickListener {
            HudSoundManager.playTapSound()
            binding.tvSmartHomeStatus.text = "Testing Home Assistant / Webhook link..."
            lifecycleScope.launch {
                val (_, statusMsg) = app.smartHomeManager.testConnection()
                withContext(Dispatchers.Main) {
                    binding.tvSmartHomeStatus.text = statusMsg
                    com.jarvis.assistant.voice.PocketTtsEngine.getInstance(this@JarvisMatrixActivity).speak(statusMsg)
                    Toast.makeText(this@JarvisMatrixActivity, statusMsg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun executeNativeShell(command: String) {
        HudSoundManager.playTapSound()
        binding.etTermuxCommand.setText(command)
        binding.tvTermuxResult.text = "Executing '$command' on native Linux..."
        lifecycleScope.launch(Dispatchers.IO) {
            val output = app.termuxBridge.executeLocalCommand(command)
            withContext(Dispatchers.Main) {
                binding.tvTermuxResult.text = output
            }
        }
    }

    private fun fetchStockQuote(rawSymbol: String) {
        HudSoundManager.playTapSound()
        val symbol = when (rawSymbol.trim().uppercase()) {
            "RELIANCE" -> "RELIANCE.NS"
            "TCS" -> "TCS.NS"
            "INFOSYS", "INFY" -> "INFY.NS"
            "HDFC", "HDFCBANK" -> "HDFCBANK.NS"
            "TATAMOTORS", "TATA MOTORS" -> "TATAMOTORS.NS"
            "NIFTY", "NIFTY50" -> "^NSEI"
            "SENSEX" -> "^BSESN"
            "BITCOIN", "BTC" -> "BTC-USD"
            "ETHEREUM", "ETH" -> "ETH-USD"
            else -> rawSymbol.trim()
        }
        binding.tvStockResult.text = "Fetching live quote for $symbol..."
        lifecycleScope.launch {
            val quote = app.financeClient.getQuote(symbol)
            withContext(Dispatchers.Main) {
                if (quote != null) {
                    val sign = if (quote.change >= 0) "+" else ""
                    binding.tvStockResult.text = "${quote.name} (${quote.symbol})\nPrice: ${quote.currency} ${quote.price}\nChange: $sign${String.format("%.2f", quote.changePercent)}%\nState: ${quote.marketState}"
                } else {
                    binding.tvStockResult.text = "Could not fetch data for $symbol. Try adding .NS for Indian stocks (e.g. RELIANCE.NS, TCS.NS) or BTC-USD for crypto."
                }
            }
        }
    }

    private fun triggerIntruderPhotoCapture() {
        Toast.makeText(this, "Testing Watchman camera capture...", Toast.LENGTH_SHORT).show()
        app.watchmanSecurity.captureIntruderPhoto { file ->
            runOnUiThread {
                if (file != null) {
                    Toast.makeText(this, "Watchman: Photo saved to ${file.name}", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Intruder capture completed.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateGuardStatusUi() {
        val isArmed = app.voiceGuardian.isArmed
        binding.tvGuardStatus.text = if (isArmed) "Status: Armed (Full Security Active)" else "Status: Disarmed (Normal Mode)"
        val voices = app.voiceGuardian.listVoices()
        binding.tvEnrolledVoices.text = if (voices.isEmpty()) "No voice profiles enrolled" else "Enrolled Voices: " + voices.joinToString(", ") { "${it.name} (${it.role})" }
    }

    private fun saveImageToAppAndGallery(bitmap: android.graphics.Bitmap) {
        try {
            val filename = "JARVIS_AI_${System.currentTimeMillis()}.jpg"
            // 1. Save to Device MediaStore (Pictures/JARVIS)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/JARVIS")
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                    }
                }
            } else {
                val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "JARVIS")
                if (!dir.exists()) dir.mkdirs()
                val file = java.io.File(dir, filename)
                java.io.FileOutputStream(file).use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                }
                android.media.MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
            }

            // 2. Save directly inside App Internal Storage
            val internalDir = java.io.File(filesDir, "saved_ai_images")
            if (!internalDir.exists()) internalDir.mkdirs()
            val internalFile = java.io.File(internalDir, filename)
            java.io.FileOutputStream(internalFile).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
            }

            binding.tvAiImageStatus.text = "âœ… Saved to Pictures/JARVIS & App Storage!"
            Toast.makeText(this, "ðŸ’¾ Image Saved to Device Gallery and App Storage!", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareImage(bitmap: android.graphics.Bitmap) {
        try {
            val cacheFile = java.io.File(cacheDir, "shared_ai_image.jpg")
            java.io.FileOutputStream(cacheFile).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                cacheFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share JARVIS AI Image"))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not share image: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun viewFullScreen(bitmap: android.graphics.Bitmap) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val imageView = android.widget.ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.BLACK)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.setContentView(imageView)
        dialog.show()
    }
}
