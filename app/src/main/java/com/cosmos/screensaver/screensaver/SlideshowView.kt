package com.cosmos.screensaver.screensaver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.cosmos.screensaver.settings.CaptionMode

/**
 * Cross-fades between pre-placed [Frame]s and draws the caption chosen in settings. The surface
 * buffer matches the display's logical size (see [nativeSize] for why never more).
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

    private val captionRenderer = CaptionRenderer()

    var captionScale: Float = 1f
        set(value) {
            field = value
            requestDraw()
        }

    var captionMode: CaptionMode = CaptionMode.SUBTLE
        set(value) {
            field = value
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
    private val destination = Rect()
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

    /**
     * The display's logical (app) size. Never the panel's physical mode: on MediaTek TVs such as
     * the Sony X90L, a layer larger than the logical 1080p UI switches the compositor into its
     * "4K/2K UI coexist" mode, and that leaves every app's video mispositioned (a quarter of the
     * screen) until reboot, even after the layer is gone. The TV upscales this buffer instead.
     */
    private fun nativeSize(): Pair<Int, Int> {
        val point = android.graphics.Point()
        @Suppress("DEPRECATION")
        display?.getRealSize(point)
        val (w, h) = if (point.x > 0 && point.y > 0) {
            maxOf(point.x, point.y) to minOf(point.x, point.y)
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
        val layout = staticLayout(text, statusPaint, width)
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
        captionRenderer.draw(
            canvas, slide.attribution, captionMode, captionScale, bufferWidth, bufferHeight, slide.captionOnLeft, captionAlpha
        )
    }

    companion object {
        private const val TAG = "CosmosSlideshowView"
        const val FADE_MILLIS = 1_500L
        const val SUBTLE_HOLD_MILLIS = 15_000L
        const val CAPTION_FADE_MILLIS = 2_000L
        private const val STATUS_ALPHA = 170
        private const val UNSET = -1L
        private const val MIN_EDGE = 480
        private const val MAX_WIDTH = 3840
        private const val MAX_HEIGHT = 2160
        private val DEFAULT_SIZE = 1920 to 1080
        private val LOW_SIZE = 1920 to 1080
    }
}
