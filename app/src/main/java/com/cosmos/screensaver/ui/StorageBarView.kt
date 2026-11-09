package com.cosmos.screensaver.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.cosmos.screensaver.R

/** Colours of the bar's parts. The legend always names them and gives amounts, so colour is never the only meaning. */
internal object BarColours {
    fun of(kind: BarKind): Int = when (kind) {
        BarKind.OTHER_USED -> 0xFF5B6678.toInt()
        BarKind.FAVOURITES -> 0xFFFFD166.toInt()
        BarKind.SAVED -> 0xFF8CC8FF.toInt()
        BarKind.UNDO -> 0xFFB39DDB.toInt()
        BarKind.ROOM -> 0xFF3FB68B.toInt()
        BarKind.UNALLOCATED -> 0xFF1D2A43.toInt()
        BarKind.RESERVED -> 0xFF3A4A68.toInt()
    }
}

/**
 * Draws a [StorageBarLayout]. The widths come from [widths], which is pure. [mini] is the thin bar on
 * the Saved pictures page: no markers, just the proportions.
 */
@Suppress("ViewConstructor")
internal class StorageBarView(context: Context, private val mini: Boolean = false) : View(context) {
    private var layout = StorageBarLayout.UNKNOWN
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f * density }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val clip = Path()
    private val bounds = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun show(next: StorageBarLayout) {
        if (next == layout) return
        layout = next
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = ((if (mini) MINI_HEIGHT_DP else HEIGHT_DP) * density).toInt()
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val barTop = if (mini) 0f else MARKER_OVERHANG_DP * density
        val barBottom = height - (if (mini) 0f else MARKER_OVERHANG_DP * density)
        bounds.set(0f, barTop, width.toFloat(), barBottom)
        val radius = (barBottom - barTop) / 2f
        if (!layout.known) {
            fill.color = BarColours.of(BarKind.UNALLOCATED)
            canvas.drawRoundRect(bounds, radius, radius, fill)
            return
        }
        clip.reset()
        clip.addRoundRect(bounds, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        val pixels = widths(layout.segments.map { it.bytes }, width, (MIN_SEGMENT_DP * density).toInt())
        var x = 0f
        layout.segments.forEachIndexed { index, segment ->
            val w = pixels[index]
            if (w > 0) {
                fill.color = BarColours.of(segment.kind)
                canvas.drawRect(x, barTop, x + w, barBottom, fill)
            }
            x += w
        }
        canvas.restore()

        outline.color = if (layout.reserveShortfallBytes > 0L) SettingsColors.WARNING else BarColours.of(BarKind.RESERVED)
        canvas.drawRoundRect(bounds, radius, radius, outline)

        if (!mini) {
            drawMarker(canvas, layout.limitMarkerBytes, 0xFFFFFFFF.toInt(), barTop, barBottom)
            drawMarker(canvas, layout.capacityMarkerBytes, SettingsColors.WARNING, barTop, barBottom)
        }
    }

    private fun drawMarker(canvas: Canvas, offset: Long, colour: Int, barTop: Float, barBottom: Float) {
        if (offset < 0L || layout.totalBytes <= 0L) return
        val x = (offset.toDouble() / layout.totalBytes * width).toFloat().coerceIn(marker.strokeWidth, width - marker.strokeWidth)
        marker.color = colour
        canvas.drawLine(x, barTop - MARKER_OVERHANG_DP * density, x, barBottom + MARKER_OVERHANG_DP * density, marker)
    }

    companion object {
        private const val HEIGHT_DP = 44
        private const val MINI_HEIGHT_DP = 12
        private const val MARKER_OVERHANG_DP = 6f
        private const val MIN_SEGMENT_DP = 4

        /**
         * Pixel widths for [bytes] across [totalPixels]: proportional, but any non-empty part gets at
         * least [minPixels] so a small favourites slice is still visible. The widest part gives up the
         * difference, so the widths always add up to [totalPixels] and none is negative.
         */
        fun widths(bytes: List<Long>, totalPixels: Int, minPixels: Int): IntArray {
            val out = IntArray(bytes.size)
            val sum = bytes.sumOf { it.coerceAtLeast(0L) }
            if (sum <= 0L || totalPixels <= 0) return out
            var assigned = 0
            bytes.forEachIndexed { i, b ->
                if (b > 0L) {
                    val proportional = (b.toDouble() / sum * totalPixels).toInt()
                    out[i] = proportional.coerceAtLeast(minPixels.coerceAtMost(totalPixels))
                    assigned += out[i]
                }
            }
            val widest = bytes.indices.maxByOrNull { out[it] } ?: return out
            out[widest] = (out[widest] + totalPixels - assigned).coerceAtLeast(0)
            return out
        }
    }
}

/** A key to the bar: a swatch, a plain-words name and the amount, one line per part that has bytes. */
@Suppress("ViewConstructor")
internal class StorageLegendView(context: Context, private val format: (Long) -> String) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val labels = mapOf(
        BarKind.OTHER_USED to R.string.bar_other,
        BarKind.FAVOURITES to R.string.bar_favourites,
        BarKind.SAVED to R.string.bar_saved,
        BarKind.UNDO to R.string.bar_undo,
        BarKind.ROOM to R.string.bar_room,
        BarKind.UNALLOCATED to R.string.bar_unallocated,
        BarKind.RESERVED to R.string.bar_reserved
    )
    private val lines = BarKind.entries.associateWith { Line(it) }

    init {
        orientation = VERTICAL
        BarKind.entries.forEach { addView(lines.getValue(it).row) }
    }

    fun show(layout: StorageBarLayout) {
        BarKind.entries.forEach { kind ->
            val bytes = layout.bytes(kind)
            val line = lines.getValue(kind)
            line.row.visibility = if (layout.known && bytes > 0L) VISIBLE else GONE
            line.amount.text = format(bytes)
        }
    }

    private inner class Line(kind: BarKind) {
        val amount = TextView(context)
        val row = LinearLayout(context)

        init {
            val swatch = View(context).apply {
                background = GradientDrawable().apply {
                    setColor(BarColours.of(kind))
                    cornerRadius = 3f * density
                    setStroke((1 * density).toInt().coerceAtLeast(1), 0x66FFFFFF)
                }
            }
            val name = TextView(context).apply {
                setText(labels.getValue(kind))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(SettingsColors.SECONDARY)
            }
            amount.apply {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(android.graphics.Color.WHITE)
                gravity = Gravity.END
            }
            row.apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
                addView(swatch, LayoutParams((14 * density).toInt(), (14 * density).toInt()).apply {
                    marginEnd = (10 * density).toInt()
                })
                addView(name, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(amount)
            }
        }
    }
}
