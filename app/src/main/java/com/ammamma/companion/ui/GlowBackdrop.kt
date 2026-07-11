package com.ammamma.companion.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Ambient floating glow backdrop — the owner's "Gemini-app ambient gradient glow"
 * bar. Draws UNDER a screen's real content: three soft radial-gradient blobs
 * drifting on slow, independent Lissajous paths. Deliberately cheap for a 2 GB /
 * Snapdragon 450 phone: no bitmaps, no blur (API 27 has no RenderEffect), one
 * Shader built per blob and only translated per frame (never recreated in
 * onDraw), ONE ValueAnimator drives all three, invalidate is throttled to
 * ~30fps, and the animator is hard-paused whenever this view is off-screen.
 *
 * Host activities MUST call [start] from onStart() and [stop] from onStop() —
 * battery matters on this phone. [setMood] tints the three blobs per screen;
 * [setPulsing] makes them slowly breathe alpha (FindPhone only: urgency without
 * panic).
 */
class GlowBackdrop @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private class Blob(
        var r: Int, var g: Int, var b: Int,
        val baseAlpha: Int,
        val periodMs: Long,
        val phase: Double,
        val radiusFrac: Float
    ) {
        var shader: RadialGradient? = null
    }

    // Default (Home) mood: warm amber + soft violet + teal — calm, luxurious.
    private val blobs = arrayOf(
        Blob(255, 176, 84, 28, 24_000L, 0.0, 0.62f),
        Blob(150, 120, 255, 22, 31_000L, 2.1, 0.58f),
        Blob(90, 200, 180, 20, 19_000L, 4.2, 0.55f)
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private var pulsing = false
    private val startTime = SystemClock.uptimeMillis()
    private var lastInvalidate = 0L

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val now = SystemClock.uptimeMillis()
            if (now - lastInvalidate >= FRAME_MS) {
                lastInvalidate = now
                invalidate()
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShaders()
    }

    private fun rebuildShaders() {
        if (width == 0) return
        blobs.forEach { blob ->
            val radius = width * blob.radiusFrac
            val color = Color.argb(255, blob.r, blob.g, blob.b)
            blob.shader = RadialGradient(0f, 0f, radius, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
    }

    /** Retint the three blobs for this screen's mood. Pass exactly 3 opaque RGB
     * ints — we own the low alpha ourselves so every mood stays equally subtle. */
    fun setMood(vararg colors: Int) {
        colors.forEachIndexed { i, c ->
            if (i < blobs.size) {
                blobs[i].r = Color.red(c); blobs[i].g = Color.green(c); blobs[i].b = Color.blue(c)
            }
        }
        rebuildShaders()
        invalidate()
    }

    fun setPulsing(on: Boolean) { pulsing = on }

    fun start() { if (!animator.isStarted) animator.start() }
    fun stop() { animator.cancel() }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) stop()
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val t = SystemClock.uptimeMillis() - startTime
        val cx = width / 2f
        val cy = height / 2f
        val ampX = width * 0.15f
        val ampY = height * 0.15f
        val breathe = if (pulsing) 0.55 + 0.45 * Math.sin(2 * Math.PI * t / 3400.0) else 1.0

        blobs.forEach { blob ->
            val shader = blob.shader ?: return@forEach
            val angleX = 2 * Math.PI * t / blob.periodMs + blob.phase
            val angleY = 2 * Math.PI * t / (blob.periodMs * 1.37) + blob.phase * 1.6
            val dx = (ampX * Math.sin(angleX)).toFloat()
            val dy = (ampY * Math.cos(angleY)).toFloat()

            paint.shader = shader
            paint.alpha = (blob.baseAlpha * breathe).toInt().coerceIn(0, 255)
            matrix.reset()
            matrix.setTranslate(cx + dx, cy + dy)
            shader.setLocalMatrix(matrix)
            canvas.drawCircle(cx + dx, cy + dy, width * blob.radiusFrac, paint)
        }
    }

    companion object {
        private const val FRAME_MS = 33L  // ~30fps invalidate cap
    }
}
