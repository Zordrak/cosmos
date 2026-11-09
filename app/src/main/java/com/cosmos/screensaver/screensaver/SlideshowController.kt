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
import com.cosmos.screensaver.catalogue.LibraryMaintenance
import com.cosmos.screensaver.catalogue.UserState
import com.cosmos.screensaver.rights.AssetProvider
import com.cosmos.screensaver.settings.CosmosSettings
import com.cosmos.screensaver.settings.SelectionSnapshot
import com.cosmos.screensaver.source.sha256Of
import com.cosmos.screensaver.sync.ImageStreamer
import com.cosmos.screensaver.sync.LibraryStorage
import com.cosmos.screensaver.sync.RefreshCoordinator
import com.cosmos.screensaver.sync.RefreshJobService
import com.cosmos.screensaver.sync.StreamQueue
import java.io.File
import java.io.IOException
import kotlin.random.Random

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
    private val userState = LibraryStorage.userState(context)
    private val damagedKeys = mutableSetOf<String>()
    private val random = Random.Default
    @Volatile private var generation = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var worker: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var shownAny = false
    private var streamer: ImageStreamer? = null
    private var streamTurn = false
    private var streamedLast = false

    fun start() {
        if (worker != null) return
        generation++
        view.captionMode = settings.captionMode
        RefreshJobService.ensureScheduled(context)
        val thread = HandlerThread("cosmos-slideshow").also { it.start() }
        worker = thread
        workerHandler = Handler(thread.looper).also { it.post(::advance) }
        RefreshCoordinator.requestRefresh(context)
    }

    fun stop() {
        generation++
        workerHandler?.removeCallbacksAndMessages(null)
        mainHandler.removeCallbacksAndMessages(null)
        worker?.quitSafely()
        worker = null
        workerHandler = null
        streamer?.close()
        streamer = null
    }

    private fun advance() {
        val handler = workerHandler ?: return
        val shown = showNextImage()
        if (shown) {
            shownAny = true
            val seconds = if (streamedLast && !settings.streamOnly) {
                maxOf(settings.intervalSeconds, ImageStreamer.MIN_INTERVAL_SECONDS)
            } else settings.intervalSeconds
            handler.postDelayed(::advance, seconds * 1000L)
        } else {
            if (!shownAny) {
                val message = when {
                    settings.streamOnly -> context.getString(
                        if (streamer().hasRoom()) R.string.slideshow_streaming_only else R.string.slideshow_no_space
                    )
                    RefreshCoordinator.lowOnSpace(context, store) -> context.getString(
                        if (streamer().hasRoom()) R.string.slideshow_streaming else R.string.slideshow_no_space
                    )
                    RefreshCoordinator.isRunning -> context.getString(R.string.slideshow_fetching)
                    else -> context.getString(R.string.slideshow_empty)
                }
                mainHandler.post { view.showMessage(message) }
            }
            handler.postDelayed(::advance, EMPTY_RETRY_MILLIS)
        }
    }

    private fun streamer(): ImageStreamer = streamer ?: ImageStreamer(context).also { streamer = it }

    /** Streaming replaces the library only when free space is critically low and few images are cached. */
    private fun shouldStream(cachedCount: Int): Boolean {
        if (cachedCount >= ImageStreamer.SMALL_LIBRARY) return false
        if (!RefreshCoordinator.lowOnSpace(context, store)) return false
        if (cachedCount == 0) return true
        streamTurn = !streamTurn
        return streamTurn
    }

    private fun streamImage(): Boolean {
        val gen = generation
        val streamer = streamer()
        val streamed = streamer.next() ?: return false
        val entry = streamed.entry
        try {
            if (gen != generation) return false
            val frame = frameFor(streamer.file(entry), entry) ?: return false
            val attribution = Attribution.of(entry)
            val library = store.load().entries.map(SelectionSnapshot::identity).toSet()
            mainHandler.post {
                view.show(frame, attribution) {
                    commitDisplay(gen, entry.sha256, library) { state ->
                        state.copy(
                            streamOrder = StreamQueue.withAccepted(
                                state.streamOrder, streamed.next, streamed.leadKey, entry.sha256
                            )
                        )
                    }
                }
            }
            return true
        } finally {
            streamer.discard(entry)
        }
    }

    private fun showNextImage(): Boolean {
        val enabled = settings.enabledProviders
        if (settings.streamOnly) {
            streamedLast = true
            return streamImage()
        }
        val cached = eligibleEntries(enabled)
        if (shouldStream(cached.size)) {
            if (streamImage()) {
                streamedLast = true
                return true
            }
            Log.w(TAG, "Streaming unavailable; falling back to cached images")
        }
        streamedLast = false
        return showCachedImage(enabled)
    }

    private fun showCachedImage(enabled: Set<AssetProvider>): Boolean {
        val gen = generation
        val excluded = mutableSetOf<String>()
        repeat(MAX_ATTEMPTS) {
            // Waits for earlier history writes so the pick sees the previous display.
            val (catalogue, state) = LibraryStorage.run { store.load() to userState.load() }
            val snapshot = SelectionSnapshot.build(catalogue.entries, settings.selection, enabled, state)
            val candidates = ImagePicker.candidates(snapshot, state, settings::sourceWeight, damagedKeys)
            val library = catalogue.entries.map(SelectionSnapshot::identity).toSet()
            val pick = ImagePicker.pick(
                settings.orderMode, state.cachedOrder, candidates, library, state.lastShownHash, excluded, random
            ) ?: return false
            val entry = snapshot.aliasesByIdentity[pick.hash]?.firstOrNull { it.key !in damagedKeys }
            val frame = entry?.let(::loadVerified)
            if (entry == null || frame == null) {
                excluded += pick.hash
                return@repeat
            }
            val attribution = Attribution.of(entry)
            mainHandler.post {
                view.show(frame, attribution) {
                    commitDisplay(gen, pick.hash, library) { state -> state.copy(cachedOrder = pick.next) }
                }
            }
            return true
        }
        return false
    }

    /** Records a display only after its frame reached the screen, and only for the session that chose it. */
    private fun commitDisplay(gen: Int, hash: String, library: Set<String>, order: (UserState) -> UserState) {
        if (gen != generation) return
        val now = System.currentTimeMillis()
        LibraryStorage.post {
            val committed = userState.commitDisplay(hash, now, library, order)
            if (!committed) Log.w(TAG, "Unable to record that a picture was shown")
        }
    }

    /** Pictures that can be shown now: one per distinct picture, excluding any found damaged this session. */
    private fun eligibleEntries(enabled: Set<AssetProvider>): List<CatalogueEntry> =
        SelectionSnapshot.build(store.load().entries, settings.selection, enabled, userState.load())
            .eligible.filter { it.key !in damagedKeys }

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
            // A favourite is never deleted for this; it is skipped until the library repairs it.
            damagedKeys += entry.key
            LibraryMaintenance.removeKey(store, userState, entry.key)
            return null
        }
        return frameFor(file, entry)
    }

    private fun frameFor(file: File, entry: CatalogueEntry): Frame? {
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