package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * Covers the four corners outside CarPlay's rounded content rectangle.
 *
 * A SurfaceView cannot be clipped by a View outline on Android 8. This static overlay follows the
 * same content bounds and paints only the corner slivers, so it works with both video paths without
 * per-frame work. The fill color tracks the host's solid background instead of assuming black.
 */
internal class CarPlayCornerMaskView(context: Context) : View(context) {
    private val path = Path()
    private val bounds = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private var radiusPixels = 0f

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    fun setCornerRadiusDp(radiusDp: Int) {
        val next = radiusDp.coerceAtLeast(0) * resources.displayMetrics.density
        if (next == radiusPixels) return
        radiusPixels = next
        rebuild()
    }

    fun setSurroundColor(color: Int) {
        if (paint.color == color) return
        paint.color = color
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        rebuild()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radiusPixels > 0f) canvas.drawPath(path, paint)
    }

    private fun rebuild() {
        path.reset()
        if (width <= 0 || height <= 0 || radiusPixels <= 0f) {
            invalidate()
            return
        }
        val radius = radiusPixels.coerceAtMost(minOf(width, height) / 2f)
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(bounds, Path.Direction.CW)
        path.addRoundRect(bounds, radius, radius, Path.Direction.CW)
        invalidate()
    }
}

internal fun carPlayCornerSurroundColor(fullscreen: Boolean, vehicleThemeNight: Boolean): Int =
    if (fullscreen) Color.BLACK else WaitingScreenColors.of(vehicleThemeNight).background
