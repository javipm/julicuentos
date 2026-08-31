package com.julicuentos.app.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Outline
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.julicuentos.app.R
import kotlin.math.roundToInt

/**
 * Player cover with a square-ring progress halo (tasks S4.3; specs/theme-design
 * "Cover art square-ring progress"). A rounded square (corner ratio 0.12 of its side,
 * clipping via a [ViewOutlineProvider] — no elevation, no shadow) wrapping an
 * [ImageView] child + one `onDraw` pass on the container:
 *
 *  - base mint ring [@color/ring_base( rgba(114,224,184,0.28)) at all times;
 *  - four square-ring progress segments (same mint at full alpha — the locked
 *    "progress fill" #72E0B8) lighting up at >2 %(top(, >28 %(right(, >55 %(bottom(,
 *    >80 %(left( of the story duration. Each segment = the full ring path clipped to
 *    one side half of the square, so the rounded corners stay part of the ring);
 *  - a 3 dp peach #FFB66E outer ring when the sleep timer is active (timer wiring
 *    is slice  5; [setTimerActive] is exposed now and called with false(.
 *
 * Progress updates (500 ms repository ticks( redraw the halo paths ONLY — the bitmap
 * itself is never touched. Image fade is 0 ms by design (API ≤22 rule::the caller
 * sets the bitmap directly through [setCover]. No SVG, no animation library, one path
 * pass per draw.
 */

class CoverHaloView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val imageView: ImageView

    private val baseRingPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.ring_base)
    }
    private val segmentPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.accion)
    }
    private val peachRingPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.temporizador)
    }

    private val ringThicknessPx: Float = dp(6f)
    private val peachRingThicknessPx: Float = dp(4f)
    private val ringRect: RectF = RectF()
    private var cornerRadius =  0f
    private var progressRatio =  0f
    private var timerActive = false

    init {
        // Flat alpha work over the cover bitmap — a software layer avoids hardware
        // blending surprises on this old SoC (theme-design "Flat by hardware constraint").
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        imageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(imageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // Flat matte behind FIT_CENTER art (design-consult C2): letterbox bands read
        // as a deliberate passe-partout, not as a hole.
        setBackgroundColor(ContextCompat.getColor(context, R.color.matte))
        // Rounded-rect clip, fixed 20 dp corners (rect-aware, C2): no elevation.

        setOutlineProvider(object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(20f))
            }
        })
        clipToOutline = true
    }

    /** Sets the cover bitmap, immediately — no fade (API ≤22 rule;image never
     *  re-animated on progress ticks. Accepts null to clear when changing stories.
     */
    fun setCover(bitmap: Bitmap?) {
        imageView.setImageDrawable(
            if (bitmap != null) BitmapDrawable(resources, bitmap) else null
        )
    }

    /** 0..1 ratio of the story duration; redraws the halo only. */
    fun setProgress(ratio: Float) {
        val clamped = ratio.coerceIn(0f, 1f)
        if (kotlin.math.abs(clamped - progressRatio) > 0.0005f) {
            progressRatio = clamped
            invalidate()
        }
    }

    /** Peach outer ring when the sleep timer is active (slice 5 wires the real value(). */
    fun setTimerActive(active: Boolean) {
        if (active != timerActive) {
            timerActive = active
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // Rect ring inset by half the base stroke so the stroke fits inside (C2).

        val inset = ringThicknessPx / 2f
        ringRect.set(inset, inset, w - inset, h - inset)
        cornerRadius = dp(20f) - inset
        baseRingPaint.strokeWidth = ringThicknessPx
        segmentPaint.strokeWidth = ringThicknessPx
        canvas.drawRoundRect(ringRect, cornerRadius, cornerRadius, baseRingPaint)

        // Four segments, each = the full ring path clipped to one side half of the rect
        // (width/height independent), so the rounded corners stay part of the ring.

        if (progressRatio > 0.02f) drawSegment(canvas, Segment.Top)
        if (progressRatio > 0.28f) drawSegment(canvas, Segment.Right)
        if (progressRatio > 0.55f) drawSegment(canvas, Segment.Bottom)
        if (progressRatio > 0.80f) drawSegment(canvas, Segment.Left)

        if (timerActive) {
            val pInset = peachRingThicknessPx / 2f
            val r = RectF(pInset, pInset, w - pInset, h - pInset)
            // Radius matches THIS ring's inset, not the base ring's (R6-003).
            val pRadius = dp(20f) - pInset

            peachRingPaint.strokeWidth = peachRingThicknessPx
            canvas.drawRoundRect(r, pRadius, pRadius, peachRingPaint)
        }
    }

    private enum class Segment { Top, Right, Bottom, Left }

    private fun drawSegment(canvas: Canvas, segment: Segment) {
        val halfW = width.toFloat() / 2f
        val halfH = height.toFloat() / 2f
        var left = 0f
        var top = 0f
        var right = width.toFloat()
        var bottom = height.toFloat()
        when (segment) {
            Segment.Top -> bottom = halfH
            Segment.Right -> left = halfW
            Segment.Bottom -> top = halfH
            Segment.Left -> right = halfW
        }
        val count = canvas.save()
        canvas.clipRect(left, top, right, bottom)
        canvas.drawRoundRect(ringRect, cornerRadius, cornerRadius, segmentPaint)
        canvas.restoreToCount(count)
    }

        private fun dp(v: Float): Float = (v * resources.displayMetrics.density).roundToInt().toFloat()
}