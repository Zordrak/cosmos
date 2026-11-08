package com.cosmos.screensaver.screensaver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.cosmos.screensaver.R
import com.cosmos.screensaver.catalogue.CatalogueEntry
import com.cosmos.screensaver.settings.CosmosSettings
import com.cosmos.screensaver.source.sha256Of
import com.cosmos.screensaver.sync.RefreshCoordinator
import java.io.IOException

/**
 * Drives a [SlideshowView] from the local catalogue. Decoding and integrity checks run on a
 * background thread; a cached image is shown only if its SHA-256 still matches the catalogue.
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
            val enabled = settings.enabledSites.map { it.provider }.toSet()
            val entries = store.load().entries.filter { it.provider in enabled }
            val key = order.next(entries.map { it.key }) ?: return false
            val entry = entries.first { it.key == key }
            val bitmap = loadVerified(entry)
            if (bitmap != null) {
                val attribution = Attribution.of(entry)
                mainHandler.post { view.show(bitmap, attribution) }
                return true
            }
        }
        return false
    }

    private fun loadVerified(entry: CatalogueEntry): Bitmap? {
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
        val metrics = context.resources.displayMetrics
        val targetWidth = minOf(maxOf(view.width, metrics.widthPixels), MAX_DECODE_WIDTH)
        val targetHeight = minOf(maxOf(view.height, metrics.heightPixels), MAX_DECODE_HEIGHT)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(entry.widthPx, entry.heightPx, targetWidth, targetHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (error: OutOfMemoryError) {
            Log.w(TAG, "Not enough memory to decode ${entry.key}", error)
            null
        }
    }

    companion object {
        private const val TAG = "CosmosSlideshow"
        private const val MAX_ATTEMPTS = 3
        private const val EMPTY_RETRY_MILLIS = 20_000L

        // Bounds bitmap memory (two slides are held during a cross-fade) on 4K panels.
        private const val MAX_DECODE_WIDTH = 2560
        private const val MAX_DECODE_HEIGHT = 1440
    }
}
