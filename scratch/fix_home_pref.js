const fs = require('fs');
let code = fs.readFileSync('app/src/main/java/com/jarvis/assistant/ui/home/HomeFragment.kt', 'utf8');

const target = `        // Dynamic 50% Screen Stage Height
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

const replacement = `        val isMora = preferences.orbEntityType == "mora_cyber"
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

        // Hide purple orb halo ring when Mora is active to avoid circle across character
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

// Normalize CRLF to LF for replacement, then write
const normalizedCode = code.replace(/\r\n/g, '\n');
const normalizedTarget = target.replace(/\r\n/g, '\n');
const normalizedReplacement = replacement.replace(/\r\n/g, '\n');

if (normalizedCode.includes(normalizedTarget)) {
  const updated = normalizedCode.replace(normalizedTarget, normalizedReplacement);
  fs.writeFileSync('app/src/main/java/com/jarvis/assistant/ui/home/HomeFragment.kt', updated, 'utf8');
  console.log('Successfully updated applyCustomizationPreferences in HomeFragment.kt');
} else {
  console.error('Target not found in HomeFragment.kt');
}
