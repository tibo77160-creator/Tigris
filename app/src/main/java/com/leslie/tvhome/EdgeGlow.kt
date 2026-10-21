package com.leslie.tvhome

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View

/**
 * Soft bands of the focused app's colour along the left, top and right edges of the screen.
 * Ambilight in "follow video" mode samples exactly these edges, so the wall behind the TV takes
 * the app's colour: Netflix red, Prime blue… The middle of the screen is left untouched.
 */
class EdgeGlow(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var color = Color.TRANSPARENT
    private var anim: ValueAnimator? = null

    init {
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Cross-fades the bands to [target] (fully saturated, as Ambilight shows it best). */
    fun show(target: Int) {
        val to = vivid(target)
        if (to == color) return
        anim?.cancel()
        anim = ValueAnimator.ofObject(ArgbEvaluator(), color, to).apply {
            duration = 450
            addUpdateListener { color = it.animatedValue as Int; invalidate() }
            start()
        }
    }

    private fun vivid(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[1] = (hsv[1] * 1.15f).coerceAtMost(1f)
        hsv[2] = hsv[2].coerceAtLeast(0.75f)
        return Color.HSVToColor(hsv)
    }

    override fun onDraw(c: Canvas) {
        if (Color.alpha(color) == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val side = w * 0.035f
        val top = h * 0.05f
        val clear = color and 0x00FFFFFF
        paint.shader = LinearGradient(0f, 0f, side, 0f, color, clear, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, side, h, paint)
        paint.shader = LinearGradient(w, 0f, w - side, 0f, color, clear, Shader.TileMode.CLAMP)
        c.drawRect(w - side, 0f, w, h, paint)
        paint.shader = LinearGradient(0f, 0f, 0f, top, color, clear, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, top, paint)
    }
}
