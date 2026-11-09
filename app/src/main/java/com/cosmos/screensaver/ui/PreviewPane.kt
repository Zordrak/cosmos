package com.cosmos.screensaver.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.cosmos.screensaver.R
import com.cosmos.screensaver.screensaver.Attribution
import com.cosmos.screensaver.screensaver.CaptionRenderer
import com.cosmos.screensaver.screensaver.previewPlacement
import com.cosmos.screensaver.settings.CaptionMode
import com.cosmos.screensaver.settings.DisplayMode
import java.util.Random

/**
 * A miniature of the TV showing a project-created sample picture with the user's current fit and credit
 * choices. The sample is deliberately not 16:9 so cropping and black bars are visible. It never touches the
 * saved library, shows no history or network effect, and is a layout preview, not a readability check.
 */
internal class PreviewPane(context: Context) : View(context) {
    private var mode = DisplayMode.FILL
    private var captionMode = CaptionMode.SUBTLE
    private var captionScale = 1f
    private val sample: Bitmap by lazy { SampleArt.create(SAMPLE_BITMAP_WIDTH, SAMPLE_BITMAP_HEIGHT) }
    private val attribution = Attribution(
        context.getString(R.string.preview_sample_title),
        context.getString(R.string.preview_sample_credit),
        context.getString(R.string.preview_sample_source)
    )
    private val captions = CaptionRenderer()
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = SettingsColors.MUTED
        strokeWidth = resources.displayMetrics.density
    }
    private val source = Rect()
    private val destination = Rect()
    private val border = RectF()
    private val panel = panelSize()

    init {
        contentDescription = context.getString(R.string.preview_description)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun update(mode: DisplayMode, captionMode: CaptionMode, captionScale: Float) {
        if (mode == this.mode && captionMode == this.captionMode && captionScale == this.captionScale) return
        this.mode = mode
        this.captionMode = captionMode
        this.captionScale = captionScale
        invalidate()
    }

    /** The physical panel, so Original size and the caption proportions match the real slideshow. */
    @Suppress("DEPRECATION")
    private fun panelSize(): Pair<Int, Int> {
        val display = (context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)?.defaultDisplay
        val current = display?.mode
        val w = current?.let { maxOf(it.physicalWidth, it.physicalHeight) } ?: 0
        val h = current?.let { minOf(it.physicalWidth, it.physicalHeight) } ?: 0
        return if (w >= 480 && h >= 270) w to h else 1920 to 1080
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, (width.toLong() * panel.second / panel.first).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val frameWidth = width
        val frameHeight = height
        if (frameWidth <= 0 || frameHeight <= 0) return
        canvas.drawColor(Color.BLACK)
        val bitmap = sample
        val placed = previewPlacement(
            mode, SAMPLE_WIDTH, SAMPLE_HEIGHT, panel.first, panel.second,
            bitmap.width, bitmap.height, frameWidth, frameHeight
        )
        source.set(placed.source.left, placed.source.top, placed.source.right, placed.source.bottom)
        destination.set(placed.destination.left, placed.destination.top, placed.destination.right, placed.destination.bottom)
        canvas.drawBitmap(bitmap, source, destination, imagePaint)
        canvas.save()
        canvas.scale(frameWidth.toFloat() / panel.first, frameHeight.toFloat() / panel.second)
        captions.draw(canvas, attribution, captionMode, captionScale, panel.first, panel.second, onLeft = false, alpha = 1f)
        canvas.restore()
        val half = borderPaint.strokeWidth / 2
        border.set(half, half, frameWidth - half, frameHeight - half)
        canvas.drawRect(border, borderPaint)
    }

    private companion object {
        // The sample stands in for a 4:3 picture of this size; the decoded bitmap is a third of it.
        const val SAMPLE_WIDTH = 3200
        const val SAMPLE_HEIGHT = 2400
        const val SAMPLE_BITMAP_WIDTH = 960
        const val SAMPLE_BITMAP_HEIGHT = 720
    }
}

/** A deterministic abstract picture with a border and corner marks, so anything trimmed away is obvious. */
internal object SampleArt {
    fun create(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(), 0xFF10224A.toInt(), 0xFF3A1650.toInt(), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        val random = Random(SEED)
        repeat(STAR_COUNT) {
            paint.color = Color.argb(120 + random.nextInt(135), 255, 255, 255)
            canvas.drawCircle(random.nextFloat() * width, random.nextFloat() * height, 0.8f + random.nextFloat() * 1.8f, paint)
        }
        paint.color = Color.argb(70, 120, 180, 255)
        canvas.drawCircle(width * 0.5f, height * 0.5f, height * 0.3f, paint)
        paint.color = Color.argb(90, 255, 170, 120)
        canvas.drawCircle(width * 0.5f, height * 0.5f, height * 0.16f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = height * 0.012f
        paint.color = Color.argb(200, 140, 200, 255)
        val inset = paint.strokeWidth
        canvas.drawRect(inset, inset, width - inset, height - inset, paint)
        val mark = height * 0.08f
        listOf(0f to 0f, width.toFloat() to 0f, 0f to height.toFloat(), width.toFloat() to height.toFloat()).forEach { (x, y) ->
            val dx = if (x == 0f) mark else -mark
            val dy = if (y == 0f) mark else -mark
            canvas.drawLine(x, y, x + dx, y + dy, paint)
        }
        return bitmap
    }

    private const val SEED = 20_260_509L
    private const val STAR_COUNT = 260
}
