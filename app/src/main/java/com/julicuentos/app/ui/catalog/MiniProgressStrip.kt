package com.julicuentos.app.ui.catalog

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.julicuentos.app.R

class MiniProgressStrip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        trackPaint.color = ContextCompat.getColor(context, R.color.track)
        fillPaint.color = ContextCompat.getColor(context, R.color.accion)
        isEnabled = false
    }

    private var ratio = 0f

    fun setProgress(r: Float) {
        val clamped = r.coerceIn(0f, 1f)
        if (clamped != ratio) {
            ratio = clamped
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val y = h / 2f
        canvas.drawRoundRect(0f, 0f, w, h, y, y, trackPaint)
        val fillW = w * ratio
        if (fillW > 1f) {
            canvas.drawRoundRect(0f, 0f, fillW, h, y, y, fillPaint)
        }
    }
}
