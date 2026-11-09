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
 *
 * As an [overlay] above a [SharpLayer] it shows no pictures itself: it dips to black, lets the
 * picture underneath change ([whenCovered]), then fades the black away once [sharpShown] says the
 * new picture is there, drawing only credits and status. If that layer fails ([sharpFailed]) it
 * reverts to drawing the pictures itself.
 */
internal class SlideshowView @JvmOverloads constructor(context: Context, private val overlay: Boolean = false) : SurfaceView(context), SurfaceHolder.Callback {
    private class Slide(
        val frame: Frame,
        val attribution: Attribution,
        val captionOnLeft: Boolean,
        val token: Long,
        /** Runs once, after a frame in which this slide is visibly (nonzero alpha) posted to the screen. */
        var onPosted: (() -> Unit)? = null
    ) {
        /** Uptime when the picture first became drawable; unset until a surface can actually show it. */
        var shownAt = UNSET
        var captionAlphaWhenReplaced = 0f

        /** The native-resolution layer underneath now shows this picture. */
        var sharpReady = false

        /** Uptime when the black cover started lifting to reveal the native-resolution picture. */
        var revealAt = UNSET

        /** Black cover level when this slide arrived; the dip continues from there. */
        var coverAtStart = 1f
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
    private var tokens = 0L
    private val coverPaint = Paint().apply { color = Color.BLACK }

    /** True while pictures are shown by the native-resolution layer underneath rather than drawn here. */
    private var videoOnly = overlay
    private var pendingCover: Triple<Long, () -> Unit, () -> Unit>? = null

    /** Lines shown in a small panel over everything, when diagnostics are on; null hides it. */
    var diagnostics: List<String>? = null
        set(value) {
            field = value
            requestDraw()
        }
    private val diagnosticsPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.MONOSPACE
    }
    private val diagnosticsBacking = Paint().apply { color = 0xC0000000.toInt() }

    private val frameCallback = Choreographer.FrameCallback {
        frameLoopRunning = false
        drawNow()
        if (isAnimating(SystemClock.uptimeMillis())) scheduleFrame()
    }
    private val captionFadeStarter = Runnable { requestDraw() }

    init {
        holder.addCallback(this)
        if (overlay) {
            // Above the native-resolution layer, below nothing else; transparent where uncovered.
            setZOrderMediaOverlay(true)
            holder.setFormat(PixelFormat.TRANSLUCENT)
        } else {
            holder.setFormat(PixelFormat.RGBX_8888)
        }
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
     * picture is visible. It never runs if the picture is replaced first or the view detaches. Returns a
     * token identifying this picture for [sharpShown].
     */
    fun show(frame: Frame, attribution: Attribution, onPosted: (() -> Unit)? = null): Long {
        val now = SystemClock.uptimeMillis()
        val coverNow = current?.let { cover(it, now) } ?: 1f
        previous?.frame?.bitmap?.takeIf { it !== current?.frame?.bitmap }?.recycle()
        current?.let { it.captionAlphaWhenReplaced = captionAlpha(it, now) }
        previous = current
        captionOnLeft = !captionOnLeft
        val slide = Slide(frame, attribution, captionOnLeft, ++tokens, onPosted)
        slide.coverAtStart = coverNow
        current = slide
        pendingCover?.let { if (it.first != slide.token) it.third() }
        pendingCover = null
        mainHandler.removeCallbacks(captionFadeStarter)
        if (surfaceReady) startClock(slide, now)
        requestDraw()
        return slide.token
    }

    /** The native-resolution layer underneath now shows picture [token]; the black cover lifts to reveal it. */
    fun sharpShown(token: Long) {
        val slide = current ?: return
        if (slide.token != token || !videoOnly) return
        slide.sharpReady = true
        requestDraw()
    }

    /** The native-resolution layer can no longer be used: draw the pictures here from now on. */
    fun sharpFailed() {
        if (!videoOnly) return
        videoOnly = false
        pendingCover?.third?.invoke()
        pendingCover = null
        requestDraw()
    }

    /**
     * Runs [action] as soon as the screen is fully black for picture [token], so the layer underneath
     * can change unseen; runs [cancelled] instead if another picture arrives first or that layer fails.
     */
    fun whenCovered(token: Long, action: () -> Unit, cancelled: () -> Unit) {
        if (!videoOnly || current?.token != token) {
            cancelled()
            return
        }
        pendingCover?.third?.invoke()
        pendingCover = Triple(token, action, cancelled)
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
        if (videoOnly) {
            if (pendingCover != null) return true
            if (!slide.sharpReady) return cover(slide, now) < 1f
            if (slide.revealAt == UNSET || now - slide.revealAt < DIP_MILLIS) return true
        } else if (elapsed < FADE_MILLIS) {
            return true
        }
        return captionMode == CaptionMode.SUBTLE && elapsed in SUBTLE_HOLD_MILLIS until SUBTLE_HOLD_MILLIS + CAPTION_FADE_MILLIS
    }

    /**
     * How black the screen is while pictures come from the layer underneath: rising from where the
     * last picture left it to fully black, holding until the new picture is there, then lifting.
     */
    private fun cover(slide: Slide, now: Long): Float {
        if (!videoOnly) return 1f
        if (!slide.sharpReady) {
            return (slide.coverAtStart + elapsed(slide, now).toFloat() / DIP_MILLIS).coerceIn(0f, 1f)
        }
        if (slide.revealAt == UNSET) slide.revealAt = now
        return 1f - ((now - slide.revealAt).toFloat() / DIP_MILLIS).coerceIn(0f, 1f)
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
        val visible = if (videoOnly) slide.sharpReady && cover(slide, now) < 1f else imageFade(slide, now) > 0f
        if (!visible) return
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
        val slide = current
        if (overlay) canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        if (videoOnly) {
            renderOverVideo(canvas, slide, now)
        } else {
            canvas.drawColor(Color.BLACK)
            renderPictures(canvas, slide, now)
        }
        diagnostics?.let { drawDiagnostics(canvas, it) }
    }

    private fun renderPictures(canvas: Canvas, slide: Slide?, now: Long) {
        if (slide == null) {
            message?.let { drawStatus(canvas, it, 1f) }
            return
        }
        val fade = imageFade(slide, now)
        previous?.takeIf { fade < 1f }?.let { drawSlide(canvas, it, 1f - fade, it.captionAlphaWhenReplaced * (1f - fade)) }
        drawSlide(canvas, slide, fade, captionAlpha(slide, now))
        message?.let { if (fade < 1f) drawStatus(canvas, it, 1f - fade) else message = null }
    }

    /** Black cover, credits and status only: the picture itself is on the layer underneath. */
    private fun renderOverVideo(canvas: Canvas, slide: Slide?, now: Long) {
        val cover = slide?.let { cover(it, now) } ?: 1f
        coverPaint.alpha = (cover * 255).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, 0f, bufferWidth.toFloat(), bufferHeight.toFloat(), coverPaint)
        if (slide != null && slide.sharpReady) {
            drawCaption(canvas, slide, captionAlpha(slide, now) * (1f - cover))
            message = null
        } else {
            previous?.let { if (it.sharpReady) drawCaption(canvas, it, it.captionAlphaWhenReplaced * (1f - cover)) }
            message?.let { drawStatus(canvas, it, 1f) }
        }
        val pending = pendingCover
        if (pending != null && slide != null && pending.first == slide.token && cover >= 1f) {
            pendingCover = null
            mainHandler.post(pending.second)
        }
    }

    private fun drawCaption(canvas: Canvas, slide: Slide, alpha: Float) {
        if (alpha <= 0f) return
        captionRenderer.draw(
            canvas, slide.attribution, captionMode, captionScale, bufferWidth, bufferHeight, slide.captionOnLeft, alpha
        )
    }

    private fun drawDiagnostics(canvas: Canvas, lines: List<String>) {
        val unit = bufferHeight / 1080f
        diagnosticsPaint.textSize = 18f * unit
        val lineHeight = diagnosticsPaint.textSize * 1.35f
        val pad = 14f * unit
        val width = lines.maxOfOrNull { diagnosticsPaint.measureText(it) } ?: 0f
        val left = 48f * unit
        val top = 40f * unit
        canvas.drawRect(left, top, left + width + pad * 2, top + lineHeight * lines.size + pad * 2, diagnosticsBacking)
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, left + pad, top + pad + lineHeight * (index + 1) - lineHeight * 0.3f, diagnosticsPaint)
        }
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
        /** Each half of a dip through black between native-resolution pictures. */
        const val DIP_MILLIS = 700L
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
