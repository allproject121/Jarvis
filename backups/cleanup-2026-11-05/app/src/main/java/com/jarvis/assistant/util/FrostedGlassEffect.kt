package com.jarvis.assistant.util

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import com.jarvis.assistant.JarvisApp

object FrostedGlassEffect {

    fun isBlurEnabled(): Boolean {
        return try {
            JarvisApp.instance.preferences.isBlurEffectEnabled
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Applies hardware GPU gaussian blur on Android 12+ (API 31+) if user enabled blur effect.
     * If disabled or API < 31, clears blur safely.
     */
    fun applyBlur(view: View?, radiusX: Float = 35f, radiusY: Float = 35f) {
        if (view == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                if (isBlurEnabled()) {
                    view.setRenderEffect(RenderEffect.createBlurEffect(radiusX, radiusY, Shader.TileMode.CLAMP))
                } else {
                    view.setRenderEffect(null)
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Applies system window blur behind for dialogs/bottom sheets (Android 12+ / API 31+)
     */
    fun applyWindowBlur(window: Window?, blurRadius: Int = 45, dimAmount: Float = 0.22f) {
        if (window == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isBlurEnabled()) {
            try {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.setDimAmount(dimAmount)
                window.attributes = window.attributes.apply {
                    blurBehindRadius = blurRadius
                }
            } catch (_: Exception) {
                window.setDimAmount(0.40f)
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                } catch (_: Exception) {}
            }
            window.setDimAmount(0.45f)
        }
    }
}
