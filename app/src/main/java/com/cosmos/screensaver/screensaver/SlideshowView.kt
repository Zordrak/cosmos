package com.cosmos.screensaver.screensaver

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View

/**
 * Shows images scaled to fit (never cropped or stretched) with a cross-fade, and always draws
 * the full attribution with the image. The caption alternates corners to limit burn-in.
 */
internal class SlideshowView(context: Context) : View(context) {
    private data class Slide(val bitmap: Bitmap, val attribution: Attribution, val captionOnLeft: Boolean)

    private val synthetic = SyntheticArtworkRenderer()
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(165, 0, 0, 0) }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val creditPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(232, 238, 248)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val sourcePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(176, 198, 228)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val destination = RectF()
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

    fun show(bitmap: Bitmap, attribution: Attribution) {
        animator?.cancel()
        previous = current
        captionOnLeft = !captionOnLeft
        current = Slide(bitmap, attribution, captionOnLeft)
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

    fun clear() {
        animator?.cancel()
        current = null
        previous = null
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
        if (fade >= 1f) previous = null
    }

    private fun drawSlide(canvas: Canvas, slide: Slide, alpha: Float) {
        val bitmap = slide.bitmap
        val viewport = ArtworkViewport.fit(width, height, bitmap.width.toFloat(), bitmap.height.toFloat())
        destination.set(
            viewport.left,
            viewport.top,
            viewport.left + bitmap.width * viewport.scale,
            viewport.top + bitmap.height * viewport.scale
        )
        imagePaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        canvas.drawBitmap(bitmap, null, destination, imagePaint)
        drawCaption(canvas, slide, alpha)
    }

    private class CaptionLayout(
        val width: Int,
        val height: Int,
        val lines: List<StaticLayout>,
        val boxWidth: Float,
        val boxHeight: Float
    )

    private val captionCache = HashMap<Slide, CaptionLayout>()

    private fun captionLayout(slide: Slide): CaptionLayout {
        captionCache[slide]?.takeIf { it.width == width && it.height == height }?.let { return it }
        captionCache.keys.retainAll(setOfNotNull(current, previous))
        val unit = height / 1080f
        val padding = 20f * unit
        val maxTextWidth = (width * 0.58f - 2 * padding).toInt().coerceAtLeast(1)
        titlePaint.textSize = 28f * unit
        creditPaint.textSize = 21f * unit
        sourcePaint.textSize = 18f * unit
        // Each line keeps its own paint so the caption can fade with its image.
        val lines = listOf(
            layout(slide.attribution.title, TextPaint(titlePaint), maxTextWidth),
            layout(slide.attribution.credit, TextPaint(creditPaint), maxTextWidth),
            layout(slide.attribution.source, TextPaint(sourcePaint), maxTextWidth)
        )
        val gap = 8f * unit
        val textWidth = lines.maxOf(::lineWidth)
        val textHeight = lines.sumOf { it.height } + gap * (lines.size - 1)
        return CaptionLayout(width, height, lines, textWidth + 2 * padding, textHeight + 2 * padding)
            .also { captionCache[slide] = it }
    }

    private fun drawCaption(canvas: Canvas, slide: Slide, alpha: Float) {
        val unit = height / 1080f
        val margin = 44f * unit
        val padding = 20f * unit
        val gap = 8f * unit
        val caption = captionLayout(slide)
        val left = if (slide.captionOnLeft) margin else width - margin - caption.boxWidth
        val top = height - margin - caption.boxHeight
        scrim.set(left, top, left + caption.boxWidth, top + caption.boxHeight)

        scrimPaint.alpha = (alpha * 165).toInt().coerceIn(0, 165)
        canvas.drawRoundRect(scrim, 10f * unit, 10f * unit, scrimPaint)

        val alphaInt = (alpha * 255).toInt().coerceIn(0, 255)
        var y = top + padding
        for (textLayout in caption.lines) {
            textLayout.paint.alpha = alphaInt
            canvas.save()
            canvas.translate(left + padding, y)
            textLayout.draw(canvas)
            canvas.restore()
            y += textLayout.height + gap
        }
    }

    private fun layout(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()

    private fun lineWidth(layout: StaticLayout): Float =
        (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) } ?: 0f

    companion object {
        private const val FADE_MILLIS = 1_500L
    }
}
