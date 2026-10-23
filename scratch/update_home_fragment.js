const fs = require('fs');
let code = fs.readFileSync('app/src/main/java/com/jarvis/assistant/ui/home/HomeFragment.kt', 'utf8');

// 1. Add class variables
if (!code.includes('private var isZenModeActive = false')) {
  code = code.replace(
    'private var isPageLoaded = false',
    'private var isZenModeActive = false\n    private var currentLoadedOrbUrl: String? = null\n    private var isPageLoaded = false'
  );
  console.log('Added isZenModeActive and currentLoadedOrbUrl');
}

// 2. Add click listeners in onViewCreated
if (!code.includes('binding.btnToggleZenMode.setOnClickListener')) {
  const targetTop = 'binding.btnDismissLiveError.setOnClickListener';
  const newListeners = `binding.btnToggleZenMode.setOnClickListener {
            HudSoundManager.playTapSound()
            toggleZenMode()
        }

        binding.btnExitZenMode.setOnClickListener {
            HudSoundManager.playTapSound()
            toggleZenMode()
        }

        binding.btnDismissLiveError.setOnClickListener`;
  code = code.replace(targetTop, newListeners);
  console.log('Added zen mode button click listeners');
}

// 3. Update setupOrbWebView loadUrl
if (!code.includes('currentLoadedOrbUrl = initialUrl')) {
  const targetLoad = 'loadUrl(com.jarvis.assistant.util.JarvisWebViewClient.ORB_URL)';
  const newLoad = `val initialUrl = if (preferences.orbEntityType == "mora_cyber") {
                com.jarvis.assistant.util.JarvisWebViewClient.MORA_URL
            } else {
                com.jarvis.assistant.util.JarvisWebViewClient.ORB_URL
            }
            currentLoadedOrbUrl = initialUrl
            loadUrl(initialUrl)`;
  code = code.replace(targetLoad, newLoad);
  console.log('Updated setupOrbWebView loadUrl with dynamic mora check');
}

// 4. Update applyCustomizationPreferences URL reload, halo visibility and height
const targetApplyPref = `        // Dynamic 50% Screen Stage Height
        try {
            val screenHeight = resources.displayMetrics.heightPixels
            val targetStageHeight = if (preferences.isOrbCinematic50) {
                (screenHeight * 0.48f).toInt()
            } else {
                (280 * resources.displayMetrics.density).toInt()
            }
            if (b.orbContainer.layoutParams.height != targetStageHeight) {
                b.orbContainer.layoutParams.height = targetStageHeight
                b.orbContainer.requestLayout()
            }
        } catch (_: Exception) {}`;

const newApplyPref = `        val isMora = preferences.orbEntityType == "mora_cyber"
        val desiredUrl = if (isMora) {
            com.jarvis.assistant.util.JarvisWebViewClient.MORA_URL
        } else {
            com.jarvis.assistant.util.JarvisWebViewClient.ORB_URL
        }

        if (currentLoadedOrbUrl != desiredUrl) {
            currentLoadedOrbUrl = desiredUrl
            isPageLoaded = false
            b.orbWebView.loadUrl(desiredUrl)
        }

        // Hide purple orb halo ring when Mora is active to avoid circle across torso
        b.orbHaloRing.visibility = if (isMora) View.GONE else View.VISIBLE

        // Dynamic Stage Height
        try {
            val screenHeight = resources.displayMetrics.heightPixels
            val density = resources.displayMetrics.density
            val targetStageHeight = if (isZenModeActive) {
                (520 * density).toInt().coerceAtMost((screenHeight * 0.70f).toInt())
            } else if (preferences.isOrbCinematic50) {
                (screenHeight * 0.48f).toInt()
            } else if (isMora) {
                (360 * density).toInt()
            } else {
                (280 * density).toInt()
            }
            if (b.orbContainer.layoutParams.height != targetStageHeight) {
                b.orbContainer.layoutParams.height = targetStageHeight
                b.orbContainer.requestLayout()
                b.orbWebView.postDelayed({
                    b.orbWebView.evaluateJavascript("if(window.JarvisOrb && window.JarvisOrb.resize){ window.JarvisOrb.resize(); }", null)
                }, 200)
            }
        } catch (_: Exception) {}`;

if (code.includes(targetApplyPref)) {
  code = code.replace(targetApplyPref, newApplyPref);
  console.log('Replaced applyCustomizationPreferences stage height & halo logic');
}

// 5. Add toggleZenMode and applyZenModeState methods if not present
if (!code.includes('fun toggleZenMode()')) {
  const zenMethods = `
    fun toggleZenMode() {
        isZenModeActive = !isZenModeActive
        applyZenModeState(animated = true)
    }

    private fun applyZenModeState(animated: Boolean = true) {
        val b = _binding ?: return
        val density = resources.displayMetrics.density
        val isMora = preferences.orbEntityType == "mora_cyber"

        b.btnToggleZenMode.setImageResource(
            if (isZenModeActive) R.drawable.ic_zen_mode_active else R.drawable.ic_zen_mode
        )
        b.btnExitZenMode.visibility = if (isZenModeActive) View.VISIBLE else View.GONE

        val hudViews = listOf(
            b.btnSwitchToJarvisMode,
            b.layoutStatusIndicator,
            b.cardWeather,
            b.cardTelemetry,
            b.layoutActionGrid,
            b.layoutUpdatesSectionHeader,
            b.layoutUpdatesContainer
        )

        hudViews.forEach { view ->
            if (animated) {
                if (isZenModeActive) {
                    view.animate().alpha(0f).setDuration(220).withEndAction {
                        view.visibility = View.GONE
                    }.start()
                } else {
                    view.visibility = View.VISIBLE
                    view.alpha = 0f
                    view.animate().alpha(1f).setDuration(250).start()
                }
            } else {
                view.visibility = if (isZenModeActive) View.GONE else View.VISIBLE
                view.alpha = if (isZenModeActive) 0f else 1f
            }
        }

        if (!isZenModeActive) {
            b.cardWeather.visibility = if (preferences.showWeatherWidget) View.VISIBLE else View.GONE
            b.cardTelemetry.visibility = if (preferences.showTelemetryWidget) View.VISIBLE else View.GONE
            b.layoutUpdatesSectionHeader.visibility = if (preferences.showUpdatesWidget) View.VISIBLE else View.GONE
            b.layoutUpdatesContainer.visibility = if (preferences.showUpdatesWidget) View.VISIBLE else View.GONE
        }

        val screenHeight = resources.displayMetrics.heightPixels
        val targetStageHeight = if (isZenModeActive) {
            (520 * density).toInt().coerceAtMost((screenHeight * 0.70f).toInt())
        } else if (preferences.isOrbCinematic50) {
            (screenHeight * 0.48f).toInt()
        } else if (isMora) {
            (360 * density).toInt()
        } else {
            (280 * density).toInt()
        }

        val currentParams = b.orbContainer.layoutParams
        if (currentParams.height != targetStageHeight) {
            if (animated) {
                val anim = android.animation.ValueAnimator.ofInt(currentParams.height, targetStageHeight)
                anim.duration = 260
                anim.addUpdateListener { va ->
                    currentParams.height = va.animatedValue as Int
                    b.orbContainer.layoutParams = currentParams
                }
                anim.start()
            } else {
                currentParams.height = targetStageHeight
                b.orbContainer.layoutParams = currentParams
            }
        }

        b.orbWebView.postDelayed({
            b.orbWebView.evaluateJavascript("if(window.JarvisOrb && window.JarvisOrb.resize){ window.JarvisOrb.resize(); }", null)
        }, 300)
    }
`;
  // Insert before the last closing brace
  const lastBraceIdx = code.lastIndexOf('}');
  code = code.substring(0, lastBraceIdx) + zenMethods + '\n}\n';
  console.log('Added toggleZenMode and applyZenModeState methods');
}

fs.writeFileSync('app/src/main/java/com/jarvis/assistant/ui/home/HomeFragment.kt', code, 'utf8');
console.log('Successfully updated HomeFragment.kt');
