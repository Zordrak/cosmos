package com.cosmos.screensaver.screensaver

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.cosmos.screensaver.settings.CaptionMode

/** Caption geometry in 1080p units, shared by the slideshow and the settings preview. */
internal object CaptionGeometry {
    fun margin(detailed: Boolean) = if (detailed) 40f else 28f
    fun padding(detailed: Boolean) = if (detailed) 14f else 0f
    fun maxTextWidth(widthPx: Int, detailed: Boolean) = (widthPx * if (detailed) 0.5f else 0.4f).toInt().coerceAtLeast(1)

    /** The layouts depend only on these inputs; side and alpha are applied at draw time. */
    data class Key(
        val title: String,
        val credit: String,
        val source: String,
        val mode: CaptionMode,
        val sizeScale: Float,
        val widthPx: Int,
        val heightPx: Int
    )

    fun key(attribution: Attribution, mode: CaptionMode, sizeScale: Float, widthPx: Int, heightPx: Int) =
        Key(attribution.title, attribution.credit, attribution.source, mode, sizeScale, widthPx, heightPx)
}

/**
 * Draws the picture credit onto a canvas of [widthPx] x [heightPx] pixels. The caller owns timing
 * (Subtle's hold and fade, Detailed's persistence, cross-fades) and passes the resulting [alpha].
 * Laid-out text is cached for the few captions in play at once, never an unbounded history.
 */
internal class CaptionRenderer {
    private class Laid(
        val lines: List<StaticLayout>,
        val textWidth: Float,
        val textHeight: Float,
        val gap: Float,
        /** Each line's text scale, so its shadow can be redrawn faded. */
        val shadowUnits: List<Float>
    )

    private val cache = object : LinkedHashMap<CaptionGeometry.Key, Laid>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CaptionGeometry.Key, Laid>?) = size > CACHE_SIZE
    }
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val scrim = RectF()

    fun clear() = cache.clear()

    fun draw(
        canvas: Canvas,
        attribution: Attribution,
        mode: CaptionMode,
        sizeScale: Float,
        widthPx: Int,
        heightPx: Int,
        onLeft: Boolean,
        alpha: Float
    ) {
        if (mode == CaptionMode.OFF || alpha <= 0f || widthPx <= 0 || heightPx <= 0) return
        val unit = heightPx / 1080f
        val detailed = mode == CaptionMode.DETAILED
        val margin = CaptionGeometry.margin(detailed) * unit
        val padding = CaptionGeometry.padding(detailed) * unit
        val caption = laidOut(attribution, mode, sizeScale, widthPx, heightPx)
        val boxWidth = caption.textWidth + 2 * padding
        val boxHeight = caption.textHeight + 2 * padding
        val left = if (onLeft) margin else widthPx - margin - boxWidth
        val top = heightPx - margin - boxHeight

        if (detailed) {
            scrim.set(left, top, left + boxWidth, top + boxHeight)
            scrimPaint.alpha = (alpha * DETAILED_SCRIM_ALPHA).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(scrim, 8f * unit, 8f * unit, scrimPaint)
        }

        var y = top + padding
        caption.lines.forEachIndexed { index, textLayout ->
            val originalAlpha = textLayout.paint.alpha
            textLayout.paint.alpha = (alpha * originalAlpha).toInt().coerceIn(0, 255)
            // The shadow must fade with the text, or it lingers as a dark ghost of the credit.
            val shadowUnit = caption.shadowUnits[index]
            textLayout.paint.setShadowLayer(3f * shadowUnit, 0f, shadowUnit, Color.argb((alpha * SHADOW_ALPHA).toInt().coerceIn(0, 255), 0, 0, 0))
            canvas.save()
            val x = if (onLeft) left + padding else left + padding + caption.textWidth - lineWidth(textLayout)
            canvas.translate(x, y)
            textLayout.draw(canvas)
            canvas.restore()
            textLayout.paint.alpha = originalAlpha
            y += textLayout.height + caption.gap
        }
    }

    private fun laidOut(attribution: Attribution, mode: CaptionMode, sizeScale: Float, widthPx: Int, heightPx: Int): Laid {
        val key = CaptionGeometry.key(attribution, mode, sizeScale, widthPx, heightPx)
        cache[key]?.let { return it }
        val unit = heightPx / 1080f
        val detailed = mode == CaptionMode.DETAILED
        val maxTextWidth = CaptionGeometry.maxTextWidth(widthPx, detailed)
        val margin = CaptionGeometry.margin(detailed) * unit
        val padding = CaptionGeometry.padding(detailed) * unit
        val availableHeight = (heightPx - 2 * (margin + padding)).coerceAtLeast(1f)
        var scale = 1f
        var lines: List<StaticLayout>
        var gap: Float
        var textHeight: Float
        do {
            val textUnit = unit * scale * sizeScale
            lines = if (detailed) {
                listOf(
                    staticLayout(attribution.title, textPaint(DETAILED_TITLE, textUnit, "sans-serif-medium", DETAILED_ALPHA), maxTextWidth, 2),
                    staticLayout(attribution.credit, textPaint(DETAILED_CREDIT, textUnit, "sans-serif", DETAILED_ALPHA), maxTextWidth),
                    staticLayout(attribution.source, textPaint(DETAILED_SOURCE, textUnit, "sans-serif", SUBTLE_ALPHA), maxTextWidth)
                )
            } else {
                listOf(
                    staticLayout(attribution.title, textPaint(SUBTLE_TITLE, textUnit, "sans-serif-medium", SUBTLE_ALPHA), maxTextWidth, 1),
                    staticLayout(attribution.credit, textPaint(SUBTLE_CREDIT, textUnit, "sans-serif", SUBTLE_ALPHA), maxTextWidth, 1)
                )
            }
            gap = (if (detailed) 6f else 2f) * textUnit
            textHeight = lines.sumOf { it.height } + gap * (lines.size - 1)
            if (textHeight > availableHeight) scale *= 0.75f
        } while (textHeight > availableHeight && scale > 0.01f)
        return Laid(lines, lines.maxOf(::lineWidth), textHeight, gap, List(lines.size) { unit * scale * sizeScale }).also { cache[key] = it }
    }

    private fun textPaint(sizeUnits: Float, unit: Float, typeface: String, baseAlpha: Int) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = baseAlpha
            textSize = sizeUnits * unit
            this.typeface = Typeface.create(typeface, Typeface.NORMAL)
            setShadowLayer(3f * unit, 0f, unit, Color.argb(SHADOW_ALPHA, 0, 0, 0))
        }

    private fun lineWidth(layout: StaticLayout): Float =
        (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) } ?: 0f

    companion object {
        private const val CACHE_SIZE = 3
        private const val SHADOW_ALPHA = 220
        private const val SUBTLE_ALPHA = 170
        private const val DETAILED_ALPHA = 225
        private const val DETAILED_SCRIM_ALPHA = 90

        // Caption font sizes in 1080p units, before the user's Credit size scale.
        private const val SUBTLE_TITLE = 18f
        private const val SUBTLE_CREDIT = 15.5f
        private const val DETAILED_TITLE = 24f
        private const val DETAILED_CREDIT = 19f
        private const val DETAILED_SOURCE = 16.5f
    }
}

internal fun staticLayout(text: String, paint: TextPaint, width: Int, maxLines: Int? = null): StaticLayout =
    StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(false)
        .apply {
            if (maxLines != null) {
                setMaxLines(maxLines)
                setEllipsize(TextUtils.TruncateAt.END)
            }
        }
        .build()
