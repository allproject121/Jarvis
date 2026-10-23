package com.jarvis.assistant.util

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

object HudAnimationHelper {

    /**
     * Attaches tactile spring micro-animation when the user touches any card or button.
     * Scales slightly down on press (0.96f) and springs back on release with overshoot.
     */
    fun attachSpringTouch(view: View?) {
        if (view == null) return
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    v.animate()
                        .scaleX(0.96f)
                        .scaleY(0.96f)
                        .setDuration(90L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(160L)
                        .setInterpolator(OvershootInterpolator(1.6f))
                        .start()
                }
            }
            false // Retains normal click listener handling
        }
    }

    /**
     * Plays a staggered boot-up entrance animation sequence for home screen elements.
     */
    fun playCascadeEntrance(views: List<Pair<out View?, Float>>, baseDelay: Long = 40L) {
        views.forEachIndexed { index, (view, transY) ->
            if (view != null) {
                view.alpha = 0f
                view.translationY = transY
                view.animate().cancel()
                view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(index * baseDelay)
                    .setDuration(360L)
                    .setInterpolator(DecelerateInterpolator(1.4f))
                    .start()
            }
        }
    }

    /**
     * Starts a continuous, gentle breathing pulse animation for the Arc Reactor halo ring.
     */
    fun startBreathingHalo(haloView: View?): ValueAnimator? {
        if (haloView == null) return null
        return ObjectAnimator.ofPropertyValuesHolder(
            haloView,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.98f, 1.07f, 0.98f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.98f, 1.07f, 0.98f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.40f, 0.85f, 0.40f)
        ).apply {
            duration = 3000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }
}
