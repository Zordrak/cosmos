package com.cosmos.screensaver.screensaver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.cosmos.screensaver.settings.CaptionMode

/**
 * Cross-fades between pre-placed [Frame]s and draws the caption chosen in settings. The picture
 * is drawn into a surface whose buffer matches the panel's native resolution, so a 4K screen
 * shows 4K images pixel for pixel even though the app's logical display is 1080p.
 */
internal class SlideshowView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    private class Slide(
        val frame: Frame,
        val attribution: Attribution,
        val captionOnLeft: Boolean,
        /** Runs once, after a frame in which this slide is visibly (nonzero alpha) posted to the screen. */
        var onPosted: (() -> Unit)? = null
    ) {
        /** Uptime when the picture first became drawable; unset until a surface can actually show it. */
        var shownAt = UNSET
        var captionAlphaWhenReplaced = 0f
    }

    private fun elapsed(slide: Slide, now: Long): Long = if (slide.shownAt == UNSET) 0L else now - slide.shownAt

    /** Starts a slide's fade and caption clocks, once, at the moment a surface can really show it. */
    private fun startClock(slide: Slide, now: Long) {
        if (slide.shownAt != UNSET) return
        slide.shownAt = now
        mainHandler.removeCallbacks(captionFadeStarter)
        if (captionMode == CaptionMode.SUBTLE) mainHandler.postDelayed(captionFadeStarter, SUBTLE_HOLD_MILLIS)
    }

    var captionMode: CaptionMode = CaptionMode.SUBTLE
        set(value) {
            field = value
            captionCache.clear()
            requestDraw()
        }

    /** Size of the drawing buffer; frames should be decoded for exactly this size. */
    var bufferWidth = 0
        private set
    var bufferHeight = 0
        private set

    private val statusPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val destination = Rect()
    private val scrim = RectF()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val choreographer by lazy { Choreographer.getInstance() }

    private var current: Slide? = null
    private var previous: Slide? = null
    private var message: String? = null
    private var captionOnLeft = true
    private var surfaceReady = false
    private var lowResolution = false
    private var hardwareCanvasFailed = false
    private var frameLoopRunning = false

    private val frameCallback = Choreographer.FrameCallback {
        frameLoopRunning = false
        drawNow()
        if (isAnimating(SystemClock.uptimeMillis())) scheduleFrame()
    }
    private val captionFadeStarter = Runnable { requestDraw() }

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.RGBX_8888)
    }

    /** The panel's native mode, or the view's own size when that is unknown or unreasonable. */
    private fun nativeSize(): Pair<Int, Int> {
        val mode = display?.mode
        val (w, h) = if (mode != null) {
            maxOf(mode.physicalWidth, mode.physicalHeight) to minOf(mode.physicalWidth, mode.physicalHeight)
        } else {
            width to height
        }
        return if (w in MIN_EDGE..MAX_WIDTH && h in MIN_EDGE..MAX_HEIGHT) w to h else DEFAULT_SIZE
    }

    /** Falls back to a 1080p buffer when 4K could not be allocated or drawn. */
    fun useLowResolution() {
        if (lowResolution) return
        lowResolution = true
        Log.w(TAG, "Falling back to a ${LOW_SIZE.first}x${LOW_SIZE.second} buffer")
        applyBufferSize()
    }

    private fun applyBufferSize() {
        val (w, h) = if (lowResolution) LOW_SIZE else nativeSize()
        holder.setFixedSize(w, h)
        bufferWidth = w
        bufferHeight = h
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyBufferSize()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        bufferWidth = width
        bufferHeight = height
        captionCache.clear()
        requestDraw()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    /** Shows (or, with null, clears) a dim status line while there is no picture; it fades out into the first picture. */
    fun showMessage(text: String?) {
        if (current != null) return
        message = text
        requestDraw()
    }

    /**
     * [onPosted] runs once on the main thread after the surface has really received a frame in which this
     * picture is visible. It never runs if the picture is replaced first or the view detaches.
     */
    fun show(frame: Frame, attribution: Attribution, onPosted: (() -> Unit)? = null) {
        val now = SystemClock.uptimeMillis()
        previous?.frame?.bitmap?.takeIf { it !== current?.frame?.bitmap }?.recycle()
        current?.let { it.captionAlphaWhenReplaced = captionAlpha(it, now) }
        previous = current
        captionOnLeft = !captionOnLeft
        val slide = Slide(frame, attribution, captionOnLeft, onPosted)
        current = slide
        mainHandler.removeCallbacks(captionFadeStarter)
        if (surfaceReady) startClock(slide, now)
        requestDraw()
    }

    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacks(captionFadeStarter)
        choreographer.removeFrameCallback(frameCallback)
        frameLoopRunning = false
        super.onDetachedFromWindow()
    }

    private fun requestDraw() {
        drawNow()
        if (isAnimating(SystemClock.uptimeMillis())) scheduleFrame()
    }

    private fun scheduleFrame() {
        if (frameLoopRunning) return
        frameLoopRunning = true
        choreographer.postFrameCallback(frameCallback)
    }

    private fun isAnimating(now: Long): Boolean {
        val slide = current ?: return false
        if (slide.shownAt == UNSET) return false
        val elapsed = elapsed(slide, now)
        if (elapsed < FADE_MILLIS) return true
        return captionMode == CaptionMode.SUBTLE && elapsed in SUBTLE_HOLD_MILLIS until SUBTLE_HOLD_MILLIS + CAPTION_FADE_MILLIS
    }

    private fun imageFade(slide: Slide, now: Long) =
        (elapsed(slide, now).toFloat() / FADE_MILLIS).coerceIn(0f, 1f)

    private fun captionAlpha(slide: Slide, now: Long): Float {
        if (captionMode == CaptionMode.OFF) return 0f
        val elapsed = elapsed(slide, now).toFloat()
        val fadeIn = (elapsed / FADE_MILLIS).coerceIn(0f, 1f)
        if (captionMode == CaptionMode.DETAILED) return fadeIn
        val fadeOut = 1f - ((elapsed - SUBTLE_HOLD_MILLIS) / CAPTION_FADE_MILLIS).coerceIn(0f, 1f)
        return fadeIn * fadeOut
    }

    private fun drawNow() {
        if (!surfaceReady || bufferWidth <= 0 || bufferHeight <= 0) return
        val surfaceHolder = holder
        val canvas = lock(surfaceHolder) ?: return
        val now = SystemClock.uptimeMillis()
        current?.let { startClock(it, now) }
        var posted = false
        try {
            render(canvas, now)
        } finally {
            try {
                surfaceHolder.unlockCanvasAndPost(canvas)
                posted = true
            } catch (error: IllegalStateException) {
                Log.w(TAG, "Unable to post frame", error)
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "Unable to post frame", error)
            }
        }
        if (posted) notifyPosted(now)
    }

    private fun notifyPosted(now: Long) {
        val slide = current ?: return
        val callback = slide.onPosted ?: return
        if (imageFade(slide, now) <= 0f) return
        slide.onPosted = null
        callback()
    }

    private fun lock(surfaceHolder: SurfaceHolder): Canvas? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !hardwareCanvasFailed) {
            try {
                return surfaceHolder.lockHardwareCanvas()
            } catch (error: RuntimeException) {
                Log.w(TAG, "Hardware canvas unavailable; using software drawing", error)
                hardwareCanvasFailed = true
            }
        }
        return try {
            surfaceHolder.lockCanvas()
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to lock the surface", error)
            if (!lowResolution) useLowResolution()
            null
        }
    }

    private fun render(canvas: Canvas, now: Long) {
        canvas.drawColor(Color.BLACK)
        val slide = current
        if (slide == null) {
            message?.let { drawStatus(canvas, it, 1f) }
            return
        }
        val fade = imageFade(slide, now)
        previous?.takeIf { fade < 1f }?.let { drawSlide(canvas, it, 1f - fade, it.captionAlphaWhenReplaced * (1f - fade)) }
        drawSlide(canvas, slide, fade, captionAlpha(slide, now))
        message?.let { if (fade < 1f) drawStatus(canvas, it, 1f - fade) else message = null }
    }

    /** A quiet, left-aligned line inside the TV's safe area, never larger than the credits. */
    private fun drawStatus(canvas: Canvas, text: String, alpha: Float) {
        val unit = bufferHeight / 1080f
        val margin = 64f * unit
        statusPaint.textSize = 24f * unit
        statusPaint.alpha = (alpha * STATUS_ALPHA).toInt().coerceIn(0, 255)
        val width = (bufferWidth * 0.6f).toInt().coerceAtLeast(1)
        val layout = layout(text, statusPaint, width)
        canvas.save()
        canvas.translate(margin, bufferHeight - margin - layout.height)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawSlide(canvas: Canvas, slide: Slide, imageAlpha: Float, captionAlpha: Float) {
        val bitmap = slide.frame.bitmap
        if (bitmap.isRecycled) return
        val box = slide.frame.destination
        val scaleX = bufferWidth.toFloat() / slide.frame.targetWidth
        val scaleY = bufferHeight.toFloat() / slide.frame.targetHeight
        destination.set(
            (box.left * scaleX).toInt(), (box.top * scaleY).toInt(),
            (box.right * scaleX).toInt(), (box.bottom * scaleY).toInt()
        )
        imagePaint.alpha = (imageAlpha * 255).toInt().coerceIn(0, 255)
        canvas.drawBitmap(bitmap, null, destination, imagePaint)
        if (captionMode != CaptionMode.OFF && captionAlpha > 0f) drawCaption(canvas, slide, captionAlpha)
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
            setShadowLayer(3f * unit, 0f, unit, Color.argb(220, 0, 0, 0))
        }

    private fun captionLayout(slide: Slide): CaptionLayout {
        captionCache[slide]?.takeIf { it.width == bufferWidth && it.height == bufferHeight }?.let { return it }
        captionCache.keys.retainAll(setOfNotNull(current, previous))
        val unit = bufferHeight / 1080f
        val detailed = captionMode == CaptionMode.DETAILED
        val maxTextWidth = (bufferWidth * if (detailed) 0.5f else 0.4f).toInt().coerceAtLeast(1)
        val margin = (if (detailed) 40f else 28f) * unit
        val padding = (if (detailed) 14f else 0f) * unit
        val availableHeight = (bufferHeight - 2 * (margin + padding)).coerceAtLeast(1f)
        var scale = 1f
        var lines: List<StaticLayout>
        var gap: Float
        var textHeight: Float
        do {
            val textUnit = unit * scale
            lines = if (detailed) {
                listOf(
                    layout(slide.attribution.title, textPaint(22f, textUnit, "sans-serif-medium", DETAILED_ALPHA), maxTextWidth, 2),
                    layout(slide.attribution.credit, textPaint(17f, textUnit, "sans-serif", DETAILED_ALPHA), maxTextWidth),
                    layout(slide.attribution.source, textPaint(15f, textUnit, "sans-serif", SUBTLE_ALPHA), maxTextWidth)
                )
            } else {
                listOf(
                    layout(slide.attribution.title, textPaint(15f, textUnit, "sans-serif-medium", SUBTLE_ALPHA), maxTextWidth, 1),
                    layout(slide.attribution.credit, textPaint(13f, textUnit, "sans-serif", SUBTLE_ALPHA), maxTextWidth, 1)
                )
            }
            gap = (if (detailed) 6f else 2f) * textUnit
            textHeight = lines.sumOf { it.height } + gap * (lines.size - 1)
            if (textHeight > availableHeight) scale *= 0.75f
        } while (textHeight > availableHeight && scale > 0.01f)
        val textWidth = lines.maxOf(::lineWidth)
        return CaptionLayout(bufferWidth, bufferHeight, lines, textWidth, textHeight, gap)
            .also { captionCache[slide] = it }
    }

    private fun drawCaption(canvas: Canvas, slide: Slide, alpha: Float) {
        val unit = bufferHeight / 1080f
        val detailed = captionMode == CaptionMode.DETAILED
        val margin = (if (detailed) 40f else 28f) * unit
        val padding = (if (detailed) 14f else 0f) * unit
        val caption = captionLayout(slide)
        val boxWidth = caption.textWidth + 2 * padding
        val boxHeight = caption.textHeight + 2 * padding
        val left = if (slide.captionOnLeft) margin else bufferWidth - margin - boxWidth
        val top = bufferHeight - margin - boxHeight

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

    private fun layout(text: String, paint: TextPaint, width: Int, maxLines: Int? = null): StaticLayout =
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

    private fun lineWidth(layout: StaticLayout): Float =
        (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) } ?: 0f

    companion object {
        private const val TAG = "CosmosSlideshowView"
        const val FADE_MILLIS = 1_500L
        const val SUBTLE_HOLD_MILLIS = 15_000L
        const val CAPTION_FADE_MILLIS = 2_000L
        private const val SUBTLE_ALPHA = 170
        private const val STATUS_ALPHA = 170
        private const val UNSET = -1L
        private const val DETAILED_ALPHA = 225
        private const val DETAILED_SCRIM_ALPHA = 90
        private const val MIN_EDGE = 480
        private const val MAX_WIDTH = 3840
        private const val MAX_HEIGHT = 2160
        private val DEFAULT_SIZE = 1920 to 1080
        private val LOW_SIZE = 1920 to 1080
    }
}
