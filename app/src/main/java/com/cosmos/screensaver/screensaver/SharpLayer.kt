package com.cosmos.screensaver.screensaver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.nio.ByteBuffer

/**
 * Shows a still picture at the panel's native resolution on the TV's video plane.
 *
 * Android TVs that run apps at 1080p on a 4K panel upscale every app layer. Drawing a 4K app layer
 * instead is unsafe: on MediaTek TVs it switches the compositor into "4K/2K UI coexist" mode, which
 * leaves every app's video in a quarter of the screen until reboot. Video, though, reaches the
 * panel at full resolution on its own plane. So each picture is converted to BT.709 Y'CbCr and
 * wrapped as one uncompressed (I_PCM) H.264 frame, which the hardware decoder renders here.
 *
 * Sits behind the slideshow's 1080p overlay, which covers it whenever its content changes.
 * Any failure disables it for the session; the overlay alone then shows the pictures.
 */
@android.annotation.SuppressLint("ViewConstructor") // Created only in code, with its decoded frame size.
internal class SharpLayer(context: Context, val frameWidth: Int, val frameHeight: Int) :
    SurfaceView(context), SurfaceHolder.Callback {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("cosmos-sharp").also { it.start() }
    private val worker = Handler(thread.looper)
    @Volatile private var surfaceReady = false
    @Volatile private var failed = false
    @Volatile private var released = false
    private var codec: MediaCodec? = null

    init {
        holder.addCallback(this)
    }

    val usable: Boolean get() = !failed && !released

    /**
     * Converts and shows [image], which is placed at [destination] within the frame (black elsewhere).
     * Takes ownership of [image] and recycles it. [onShown] runs on the main thread once the frame
     * has been handed to the display; it never runs on failure.
     */
    fun present(image: Bitmap, destination: IntBox, onShown: () -> Unit) {
        if (!usable) {
            image.recycle()
            return
        }
        worker.post {
            try {
                if (!usable || !surfaceReady) return@post
                val planes = toPlanes(image, destination)
                image.recycle()
                val stream = PcmH264.frame(frameWidth, frameHeight, planes.y, planes.cb, planes.cr)
                if (decode(stream)) mainHandler.post { if (usable) onShown() }
            } catch (error: Exception) {
                fail("Unable to show a native-resolution picture", error)
            } catch (error: OutOfMemoryError) {
                fail("Not enough memory for a native-resolution picture", error)
            } finally {
                if (!image.isRecycled) image.recycle()
            }
        }
    }

    private fun toPlanes(image: Bitmap, destination: IntBox): Yuv709.Planes {
        val planes = Yuv709.Planes(frameWidth, frameHeight)
        val top = IntArray(frameWidth)
        val bottom = IntArray(frameWidth)
        val left = destination.left.coerceIn(0, frameWidth)
        val right = destination.right.coerceIn(left, frameWidth)
        fun row(target: IntArray, y: Int) {
            target.fill(BLACK)
            val sourceRow = y - destination.top
            if (right > left && y >= destination.top && y < destination.bottom && sourceRow < image.height) {
                val sourceLeft = left - destination.left
                val count = minOf(right - left, image.width - sourceLeft)
                if (count > 0) image.getPixels(target, left, frameWidth, sourceLeft, sourceRow, count, 1)
            }
        }
        for (pair in 0 until frameHeight / 2) {
            row(top, pair * 2)
            row(bottom, pair * 2 + 1)
            Yuv709.convertRowPair(top, bottom, frameWidth, pair, planes)
        }
        return planes
    }

    /** A fresh decoder per picture: the overlay hides any blank moment while it starts. */
    private fun decode(stream: PcmH264.Stream): Boolean {
        closeCodec()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, frameWidth, frameHeight).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, stream.slice.size + INPUT_SLACK_BYTES)
            setByteBuffer("csd-0", ByteBuffer.wrap(stream.sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(stream.pps))
        }
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec = decoder
        decoder.configure(format, holder.surface, null, 0)
        decoder.start()
        val input = decoder.dequeueInputBuffer(TIMEOUT_MICROS)
        if (input < 0) return fail("The decoder did not accept the picture")
        val buffer = decoder.getInputBuffer(input) ?: return fail("No decoder input buffer")
        if (buffer.capacity() < stream.slice.size) return fail("Decoder input buffer too small")
        buffer.clear()
        buffer.put(stream.slice)
        decoder.queueInputBuffer(input, 0, stream.slice.size, 0, 0)
        val end = decoder.dequeueInputBuffer(TIMEOUT_MICROS)
        if (end >= 0) decoder.queueInputBuffer(end, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        val info = MediaCodec.BufferInfo()
        val deadline = System.nanoTime() + DECODE_DEADLINE_NANOS
        var rendered = false
        while (System.nanoTime() < deadline && usable) {
            val output = decoder.dequeueOutputBuffer(info, POLL_MICROS)
            if (output >= 0) {
                val picture = info.size > 0 && !rendered
                decoder.releaseOutputBuffer(output, picture)
                if (picture) rendered = true
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        return rendered || fail("The decoder produced no picture")
    }

    private fun closeCodec() {
        codec?.let {
            try {
                it.stop()
            } catch (error: IllegalStateException) {
                // Already stopped.
            }
            it.release()
        }
        codec = null
    }

    private fun fail(message: String, error: Throwable? = null): Boolean {
        Log.w(TAG, "$message; using the standard layer only", error)
        failed = true
        return false
    }

    /** Stops decoding and frees the decoder. Call when the slideshow ends. */
    fun release() {
        released = true
        worker.post { closeCodec() }
        thread.quitSafely()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        // The decoder must let go of the surface before this returns.
        val done = java.util.concurrent.CountDownLatch(1)
        worker.post {
            closeCodec()
            done.countDown()
        }
        done.await(SURFACE_RELEASE_WAIT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    companion object {
        private const val TAG = "CosmosSharp"
        private const val BLACK = 0xFF000000.toInt()
        private const val TIMEOUT_MICROS = 2_000_000L
        private const val POLL_MICROS = 50_000L
        private const val DECODE_DEADLINE_NANOS = 5_000_000_000L
        private const val INPUT_SLACK_BYTES = 4096
        private const val SURFACE_RELEASE_WAIT_MILLIS = 1_000L
        private const val MAX_WIDTH = 3840
        private const val MAX_HEIGHT = 2160

        /**
         * The panel's native size when a native-resolution layer is worth having and possible: the
         * panel is larger than the size apps are drawn at, and a hardware H.264 decoder can show a
         * picture that large. Null otherwise.
         */
        fun nativeSize(context: Context): Pair<Int, Int>? = try {
            val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            val mode = display?.mode
            val logical = Point().also {
                @Suppress("DEPRECATION")
                display?.getRealSize(it)
            }
            val width = mode?.let { maxOf(it.physicalWidth, it.physicalHeight) } ?: 0
            val height = mode?.let { minOf(it.physicalWidth, it.physicalHeight) } ?: 0
            val appWidth = maxOf(logical.x, logical.y)
            when {
                width <= 0 || height <= 0 || appWidth <= 0 -> null
                width <= appWidth -> null
                width > MAX_WIDTH || height > MAX_HEIGHT -> null
                width % 16 != 0 || height % 16 != 0 -> null
                !hardwareDecoderSupports(width, height) -> null
                else -> width to height
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to check for native-resolution support", error)
            null
        }

        private fun hardwareDecoderSupports(width: Int, height: Int): Boolean =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder &&
                    info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } &&
                    (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q || info.isHardwareAccelerated) &&
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities?.isSizeSupported(width, height) == true
            }
    }
}
