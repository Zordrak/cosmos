package com.cosmos.screensaver.screensaver

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.cosmos.screensaver.settings.CaptionMode

/**
 * Cross-fades between pre-placed [Frame]s and draws the caption chosen in settings. Captions
 * alternate corners to limit burn-in.
 */
internal class SlideshowView(context: Context) : View(context) {
    private data class Slide(val frame: Frame, val attribution: Attribution, val captionOnLeft: Boolean)

    var captionMode: CaptionMode = CaptionMode.SUBTLE
        set(value) {
            field = value
            captionCache.clear()
            invalidate()
        }

    private val synthetic = SyntheticArtworkRenderer()
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val destination = Rect()
    private val scrim = RectF()

    private var current: Slide? = null
    private var previous: Slide? = null
    private var fade = 1f
    private var animator: ValueAnimator? = null
    private var message: String? = null
    private var captionOnLeft = true

    fun showMessage(text: String) {
        message = text
        if (current == null) invalidate()
    }

    fun show(frame: Frame, attribution: Attribution) {
        animator?.cancel()
        previous?.frame?.bitmap?.takeIf { it !== current?.frame?.bitmap }?.recycle()
        previous = current
        captionOnLeft = !captionOnLeft
        current = Slide(frame, attribution, captionOnLeft)
        fade = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FADE_MILLIS
            addUpdateListener {
                fade = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val slide = current
        if (slide == null) {
            synthetic.draw(canvas, width, height, message, showTestMarkers = false)
            return
        }
        canvas.drawColor(Color.BLACK)
        if (width <= 0 || height <= 0) return
        previous?.takeIf { fade < 1f }?.let { drawSlide(canvas, it, 1f - fade) }
        drawSlide(canvas, slide, fade)
    }

    private fun drawSlide(canvas: Canvas, slide: Slide, alpha: Float) {
        val bitmap = slide.frame.bitmap
        if (bitmap.isRecycled) return
        val box = slide.frame.destination
        destination.set(box.left, box.top, box.right, box.bottom)
        imagePaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        canvas.drawBitmap(bitmap, null, destination, imagePaint)
        if (captionMode != CaptionMode.OFF) drawCaption(canvas, slide, alpha)
    }

    private class CaptionLayout(
        val width: Int,
        val height: Int,
        val lines: List<StaticLayout>,
        val textWidth: Float,
        val textHeight: Float,
        val gap: Float
    )

    private val captionCache = HashMap<Slide, CaptionLayout>()

    private fun textPaint(sizeUnits: Float, unit: Float, typeface: String, baseAlpha: Int) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = baseAlpha
            textSize = sizeUnits * unit
            this.typeface = Typeface.create(typeface, Typeface.NORMAL)
            setShadowLayer(3f * unit, 0f, unit, Color.argb(200, 0, 0, 0))
        }

    private fun captionLayout(slide: Slide): CaptionLayout {
        captionCache[slide]?.takeIf { it.width == width && it.height == height }?.let { return it }
        captionCache.keys.retainAll(setOfNotNull(current, previous))
        val unit = height / 1080f
        val detailed = captionMode == CaptionMode.DETAILED
        val maxTextWidth = (width * if (detailed) 0.5f else 0.4f).toInt().coerceAtLeast(1)
        // Each line keeps its own paint so the caption can fade with its image.
        val lines = if (detailed) {
            listOf(
                layout(slide.attribution.title, textPaint(22f, unit, "sans-serif-medium", DETAILED_ALPHA), maxTextWidth, 2),
                layout(slide.attribution.credit, textPaint(17f, unit, "sans-serif", DETAILED_ALPHA), maxTextWidth, 3),
                layout(slide.attribution.source, textPaint(15f, unit, "sans-serif", SUBTLE_ALPHA), maxTextWidth, 2)
            )
        } else {
            listOf(
                layout(slide.attribution.title, textPaint(15f, unit, "sans-serif-medium", SUBTLE_ALPHA), maxTextWidth, 1),
                layout(slide.attribution.credit, textPaint(12f, unit, "sans-serif", SUBTLE_ALPHA), maxTextWidth, 2)
            )
        }
        val gap = (if (detailed) 6f else 3f) * unit
        val textWidth = lines.maxOf(::lineWidth)
        val textHeight = lines.sumOf { it.height } + gap * (lines.size - 1)
        return CaptionLayout(width, height, lines, textWidth, textHeight, gap).also { captionCache[slide] = it }
    }

    private fun drawCaption(canvas: Canvas, slide: Slide, alpha: Float) {
        val unit = height / 1080f
        val detailed = captionMode == CaptionMode.DETAILED
        val margin = (if (detailed) 40f else 28f) * unit
        val padding = if (detailed) 14f * unit else 0f
        val caption = captionLayout(slide)
        val boxWidth = caption.textWidth + 2 * padding
        val boxHeight = caption.textHeight + 2 * padding
        val left = if (slide.captionOnLeft) margin else width - margin - boxWidth
        val top = height - margin - boxHeight

        if (detailed) {
            scrim.set(left, top, left + boxWidth, top + boxHeight)
            scrimPaint.alpha = (alpha * DETAILED_SCRIM_ALPHA).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(scrim, 8f * unit, 8f * unit, scrimPaint)
        }

        var y = top + padding
        for (textLayout in caption.lines) {
            val originalAlpha = textLayout.paint.alpha
            textLayout.paint.alpha = (alpha * originalAlpha).toInt().coerceIn(0, 255)
            canvas.save()
            val x = if (slide.captionOnLeft) left + padding else left + padding + caption.textWidth - lineWidth(textLayout)
            canvas.translate(x, y)
            textLayout.draw(canvas)
            canvas.restore()
            textLayout.paint.alpha = originalAlpha
            y += textLayout.height + caption.gap
        }
    }

    private fun layout(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    private fun lineWidth(layout: StaticLayout): Float =
        (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) } ?: 0f

    companion object {
        private const val FADE_MILLIS = 1_500L
        private const val SUBTLE_ALPHA = 165
        private const val DETAILED_ALPHA = 225
        private const val DETAILED_SCRIM_ALPHA = 90
    }
}