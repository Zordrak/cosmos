package com.cosmos.screensaver.screensaver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.cosmos.screensaver.R
import com.cosmos.screensaver.catalogue.CatalogueEntry
import com.cosmos.screensaver.settings.CosmosSettings
import com.cosmos.screensaver.source.sha256Of
import com.cosmos.screensaver.sync.RefreshCoordinator
import com.cosmos.screensaver.sync.RefreshJobService
import java.io.File
import java.io.IOException

/**
 * Drives a [SlideshowView] from the local catalogue. Decoding and integrity checks run on a
 * background thread; a cached image is shown only if its SHA-256 still matches the catalogue.
 * Each image is decoded already cropped and scaled for the screen and the chosen display mode.
 */
internal class SlideshowController(
    private val context: Context,
    private val view: SlideshowView
) {
    private val settings = CosmosSettings(context)
    private val store = RefreshCoordinator.store(context)
    private val order = SlideshowOrder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var worker: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var shownAny = false

    fun start() {
        if (worker != null) return
        view.captionMode = settings.captionMode
        RefreshJobService.ensureScheduled(context)
        val thread = HandlerThread("cosmos-slideshow").also { it.start() }
        worker = thread
        workerHandler = Handler(thread.looper).also { it.post(::advance) }
        RefreshCoordinator.requestRefresh(context)
    }

    fun stop() {
        workerHandler?.removeCallbacksAndMessages(null)
        mainHandler.removeCallbacksAndMessages(null)
        worker?.quitSafely()
        worker = null
        workerHandler = null
    }

    private fun advance() {
        val handler = workerHandler ?: return
        val shown = showNextImage()
        if (shown) {
            shownAny = true
            handler.postDelayed(::advance, settings.intervalSeconds * 1000L)
        } else {
            if (!shownAny) {
                val message = if (RefreshCoordinator.isRunning) {
                    context.getString(R.string.slideshow_fetching)
                } else {
                    context.getString(R.string.slideshow_empty)
                }
                mainHandler.post { view.showMessage(message) }
            }
            handler.postDelayed(::advance, EMPTY_RETRY_MILLIS)
        }
    }

    private fun showNextImage(): Boolean {
        repeat(MAX_ATTEMPTS) {
            val enabled = settings.enabledProviders
            val entries = settings.selection.eligible(store.load().entries.filter { it.provider in enabled })
            val key = order.next(entries.map { it.key }) ?: return false
            val entry = entries.first { it.key == key }
            val frame = loadVerified(entry)
            if (frame != null) {
                val attribution = Attribution.of(entry)
                mainHandler.post { view.show(frame, attribution) }
                return true
            }
        }
        return false
    }

    private fun loadVerified(entry: CatalogueEntry): Frame? {
        val file = store.imageFile(entry)
        val intact = try {
            file.isFile && sha256Of(file) == entry.sha256
        } catch (error: IOException) {
            Log.w(TAG, "Unable to read ${entry.key}", error)
            false
        }
        if (!intact) {
            Log.w(TAG, "Integrity check failed for ${entry.key}; removing it")
            store.remove(entry.key)
            return null
        }
        val (screenWidth, screenHeight) = screenSize()
        val place = placement(
            settings.displayMode, entry.widthPx, entry.heightPx, screenWidth, screenHeight, physicalWidth(screenWidth)
        )
        return try {
            decode(file, place)?.let { Frame(it, place.destination, screenWidth, screenHeight) }
        } catch (error: OutOfMemoryError) {
            Log.w(TAG, "Not enough memory to decode ${entry.key}", error)
            mainHandler.post { view.useLowResolution() }
            null
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        val width = view.bufferWidth.takeIf { it > 0 } ?: view.width.takeIf { it > 0 } ?: metrics.widthPixels
        val height = view.bufferHeight.takeIf { it > 0 } ?: view.height.takeIf { it > 0 } ?: metrics.heightPixels
        return width.coerceIn(1, MAX_FRAME_EDGE) to height.coerceIn(1, MAX_FRAME_EDGE)
    }

    private fun physicalWidth(fallback: Int): Int =
        view.display?.mode?.let { maxOf(it.physicalWidth, it.physicalHeight) } ?: fallback

    /** Decodes only the visible region, subsampled, then scales it exactly to its destination. */
    private fun decode(file: File, place: Placement): Bitmap? {
        val source = place.source
        val target = place.destination
        val sample = sampleSizeFor(source.width, source.height, target.width, target.height)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val region = Rect(source.left, source.top, source.right, source.bottom)
        val decoded = decodeRegion(file, region, options) ?: decodeWhole(file, region, sample) ?: return null
        if (decoded.width == target.width && decoded.height == target.height) return decoded
        return Bitmap.createScaledBitmap(decoded, target.width, target.height, true).also {
            if (it !== decoded) decoded.recycle()
        }
    }

    private fun decodeRegion(file: File, region: Rect, options: BitmapFactory.Options): Bitmap? = try {
        val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(file.absolutePath)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(file.absolutePath, false)
        }
        try {
            decoder?.decodeRegion(region, options)
        } finally {
            decoder?.recycle()
        }
    } catch (error: IOException) {
        Log.w(TAG, "Region decoding unavailable for ${file.name}", error)
        null
    } catch (error: IllegalArgumentException) {
        Log.w(TAG, "Region decoding failed for ${file.name}", error)
        null
    }

    /** Fallback for files the region decoder cannot read: decode subsampled, then crop. */
    private fun decodeWhole(file: File, region: Rect, sample: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val whole = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        val crop = Rect(region.left / sample, region.top / sample, region.right / sample, region.bottom / sample)
        if (!crop.intersect(0, 0, whole.width, whole.height) || crop.isEmpty) {
            whole.recycle()
            return null
        }
        val cropped = Bitmap.createBitmap(crop.width(), crop.height(), Bitmap.Config.ARGB_8888)
        Canvas(cropped).drawBitmap(whole, crop, Rect(0, 0, crop.width(), crop.height()), Paint(Paint.FILTER_BITMAP_FLAG))
        whole.recycle()
        return cropped
    }

    companion object {
        private const val TAG = "CosmosSlideshow"
        private const val MAX_ATTEMPTS = 3
        private const val EMPTY_RETRY_MILLIS = 20_000L

        // Two frames are held during a cross-fade; keep each within GPU texture limits.
        private const val MAX_FRAME_EDGE = 4096
    }
}

/** A decoded image ready to draw at [destination], in a [targetWidth] x [targetHeight] frame. */
internal class Frame(val bitmap: Bitmap, val destination: IntBox, val targetWidth: Int, val targetHeight: Int)