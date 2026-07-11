package com.ammamma.companion.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Shared "luxury" press physics for every actionable element on the four
 * redesigned screens: a quick scale-down on touch, a spring-back overshoot on
 * release, and a haptic tick — plus (optionally) a rounded ripple for surfaces
 * whose background doesn't already carry one (the btn_* drawables already ripple
 * themselves, so callers pass addRipple=false for those).
 *
 * Uses OnTouchListener returning false, so it NEVER swallows the touch — every
 * existing OnClickListener / OnLongClickListener on these views keeps working
 * exactly as before.
 */
object Press {

    fun attach(view: View, cornerRadiusDp: Float = 24f, addRipple: Boolean = true) {
        if (addRipple && view.foreground == null) {
            val radiusPx = cornerRadiusDp * view.resources.displayMetrics.density
            val mask = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radiusPx
                setColor(Color.WHITE)
            }
            view.foreground = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, mask)
        }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    v.animate().scaleX(0.96f).scaleY(0.96f)
                        .setDuration(70).setInterpolator(DecelerateInterpolator()).start()
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    v.animate().scaleX(1f).scaleY(1f)
                        .setDuration(240).setInterpolator(OvershootInterpolator(3f)).start()
                }
            }
            false   // never consume — real click/long-click listeners still fire
        }
    }
}
