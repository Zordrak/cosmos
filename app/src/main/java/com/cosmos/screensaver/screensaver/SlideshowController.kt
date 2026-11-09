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
import android.os.SystemClock
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
import com.cosmos.screensaver.sync.RefreshProgress
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
    private val view: SlideshowView,
    /** Shows each picture at the panel's native resolution underneath [view], when the TV supports it. */
    private val sharp: SharpLayer? = null
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
    @Volatile private var shownAny = false
    private var pictureOnScreen = false
    private var streamer: ImageStreamer? = null
    private var streamTurn = false
    private var streamedLast = false

    private val startup = StartupStatus()
    private val freshness = DreamFreshness { SystemClock.elapsedRealtime() }
    private var startedAtMillis = 0L
    private val advanceTask = Runnable { advance() }

    /** Numbers each picture as the worker prepares it, so its native-resolution copy finds the right slide. */
    private var preparedSeq = 0L
    private var shownSeq = 0L
    private var shownToken = 0L

    private val diagnostics = SlideshowDiagnostics()
    private var diagnosticsOn = false
    private var pictureCount = 0

    private fun diagnosticsTick(gen: Int): Runnable = Runnable {
        if (gen != generation || !diagnosticsOn) return@Runnable
        val layer = sharp
        val buffer = "${view.bufferWidth}x${view.bufferHeight}"
        diagnostics.renderPath = when {
            layer == null -> "Standard: drawn at $buffer, the TV enlarges it to the panel"
            layer.usable -> "Full 4K: ${layer.frameWidth}x${layer.frameHeight} on the video plane (${layer.decoderName ?: "decoder starting"})"
            else -> "Full 4K failed (${layer.failure ?: "stopped"}); drawn at $buffer"
        }
        if (layer != null && layer.lastDecodeMillis > 0) {
            diagnostics.sharpConvertMillis = layer.lastConvertMillis
            diagnostics.sharpShowMillis = layer.lastDecodeMillis
        }
        if (RefreshCoordinator.isRunning) diagnostics.refresh = "looking for new pictures now"
        val runtime = Runtime.getRuntime()
        view.diagnostics = diagnostics.lines(
            (runtime.totalMemory() - runtime.freeMemory()) / MIB,
            runtime.maxMemory() / MIB,
            (SystemClock.uptimeMillis() - startedAtMillis) / 1000
        )
        mainHandler.postDelayed(diagnosticsTick(gen), DIAGNOSTICS_MILLIS)
    }

    private fun startDiagnostics(gen: Int) {
        diagnosticsOn = settings.showDiagnostics
        if (!diagnosticsOn) {
            view.diagnostics = null
            return
        }
        val display = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val mode = display?.mode
        val logical = android.graphics.Point().also {
            @Suppress("DEPRECATION")
            display?.getRealSize(it)
        }
        diagnostics.display = "panel ${mode?.physicalWidth ?: 0}x${mode?.physicalHeight ?: 0} · apps ${logical.x}x${logical.y}"
        diagnostics.order = settings.orderMode.name.lowercase()
        diagnostics.intervalSeconds = settings.intervalSeconds
        diagnostics.streaming = settings.streamOnly
        mainHandler.post(diagnosticsTick(gen))
    }

    fun start() {
        if (worker != null) return
        generation++
        val gen = generation
        shownAny = false
        pictureOnScreen = false
        view.captionMode = settings.captionMode
        view.captionScale = settings.captionSize.scale
        view.showMessage(null)
        // Armed before any decoding or network work so a slow start is never silent for long.
        startedAtMillis = SystemClock.uptimeMillis()
        startup.start(startedAtMillis, context.getString(R.string.slideshow_preparing))
        mainHandler.postDelayed(statusTick(gen), StartupStatus.DEFAULT_DELAY_MILLIS)
        mainHandler.postDelayed(lateTick(gen), STATUS_TICK_MILLIS)
        RefreshJobService.ensureScheduled(context)
        val thread = HandlerThread("cosmos-slideshow").also { it.start() }
        worker = thread
        workerHandler = Handler(thread.looper).also { it.post(advanceTask) }
        RefreshCoordinator.requestRefresh(context) { onRefreshFinished(gen) }
        mainHandler.postDelayed(freshnessTick(gen), DreamFreshness.CHECK_INTERVAL_MILLIS)
        startDiagnostics(gen)
    }

    private fun onRefreshFinished(gen: Int) {
        // A finished refresh may have filled the library: look again now rather than at the next retry.
        if (gen == generation && !shownAny) workerHandler?.let { handler ->
            handler.removeCallbacks(advanceTask)
            handler.post(advanceTask)
        }
    }

    /**
     * While the screensaver runs, now and then asks whether a refresh is due, so a TV left in
     * screensaver mode still gets new pictures. No wake lock or service is held; it stops with the session.
     */
    private fun freshnessTick(gen: Int): Runnable = Runnable {
        if (gen != generation) return@Runnable
        if (freshness.due()) {
            LibraryStorage.post { RefreshCoordinator.requestRefresh(context) { onRefreshFinished(gen) } }
        }
        mainHandler.postDelayed(freshnessTick(gen), DreamFreshness.CHECK_INTERVAL_MILLIS)
    }

    fun stop() {
        generation++
        startup.stop()
        workerHandler?.removeCallbacksAndMessages(null)
        mainHandler.removeCallbacksAndMessages(null)
        worker?.quitSafely()
        worker = null
        workerHandler = null
        streamer?.close()
        streamer = null
    }

    private fun refreshStatus() {
        val now = SystemClock.uptimeMillis()
        val base = startup.visibleText(now)
        view.showMessage(base?.let { liveProgress() ?: it })
    }

    /**
     * What the slideshow is waiting on right now, in the words the settings screens use: finding or
     * downloading a picture to play online, or saving pictures. Null when nothing is in progress.
     */
    private fun liveProgress(streamingOnly: Boolean = false): String? {
        val wall = System.currentTimeMillis()
        val streamed = com.cosmos.screensaver.ui.LibraryStatus.streaming(RefreshProgress.streaming.snapshot(), wall)
        val (model, prefix) = when {
            streamed != null -> streamed to "Online \u00B7 "
            !streamingOnly && RefreshCoordinator.isRunning ->
                com.cosmos.screensaver.ui.LibraryStatus.saving(RefreshProgress.saving.snapshot(), 0L, wall) to ""
            else -> return null
        }
        if (!model.busy) return null
        val text = com.cosmos.screensaver.ui.LibraryStatus.describe(model, prefix)
        return listOf(text.headline, text.detail, text.problem).filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun statusTick(gen: Int): Runnable = Runnable {
        if (gen != generation || pictureOnScreen) return@Runnable
        refreshStatus()
        mainHandler.postDelayed(statusTick(gen), STATUS_TICK_MILLIS)
    }

    /** Uptime when the worker started preparing the next picture; 0 while it is not. */
    @Volatile private var preparingSince = 0L

    /**
     * Once a picture is up, shows a small progress line whenever the next one is late (still being
     * found or downloaded a few seconds after it was due), and clears it as soon as it arrives.
     */
    private fun lateTick(gen: Int): Runnable = Runnable {
        if (gen != generation) return@Runnable
        val since = preparingSince
        val late = pictureOnScreen && since > 0L && SystemClock.uptimeMillis() - since >= LATE_AFTER_MILLIS
        view.progressLine = if (late) liveProgress(streamingOnly = true) ?: context.getString(R.string.slideshow_next_preparing) else null
        mainHandler.postDelayed(lateTick(gen), STATUS_TICK_MILLIS)
    }

    /** Runs on the main thread when the first picture of this session has reached the screen. */
    private fun firstPictureShown() {
        if (pictureOnScreen) return
        pictureOnScreen = true
        startup.pictureShown()
        Log.i(TAG, "First picture shown after ${SystemClock.uptimeMillis() - startedAtMillis} ms")
    }

    private fun showStatus(gen: Int, message: String) {
        mainHandler.post {
            if (gen != generation) return@post
            startup.update(message)
            refreshStatus()
        }
    }

    /** Shows [frame] on the main thread unless this session has ended meanwhile. Returns the picture's sequence number. */
    private fun postFrame(gen: Int, frame: Frame, attribution: Attribution, onShown: () -> Unit): Long {
        val seq = ++preparedSeq
        mainHandler.post {
            if (gen != generation) {
                frame.bitmap.recycle()
                return@post
            }
            shownToken = view.show(frame, attribution) {
                firstPictureShown()
                onShown()
            }
            shownSeq = seq
        }
        return seq
    }

    /**
     * Decodes picture [seq] again at the panel's native size and hands it to [sharp] once the screen is
     * black for it. Runs on the worker after the picture was posted; dropped if another picture is shown
     * meanwhile. Any failure switches the slideshow back to drawing pictures itself.
     */
    private fun sharpen(gen: Int, seq: Long, file: File, entry: CatalogueEntry) {
        val layer = sharp?.takeIf { it.usable } ?: return
        val place = placement(
            settings.displayMode, entry.widthPx, entry.heightPx, layer.frameWidth, layer.frameHeight, layer.frameWidth
        )
        val started = SystemClock.uptimeMillis()
        val bitmap = try {
            decode(file, place)
        } catch (error: OutOfMemoryError) {
            Log.w(TAG, "Not enough memory for a native-resolution copy of ${entry.key}", error)
            null
        }
        diagnostics.sharpDecodeMillis = SystemClock.uptimeMillis() - started
        mainHandler.post {
            if (gen != generation || shownSeq != seq) {
                bitmap?.recycle()
                return@post
            }
            if (bitmap == null) {
                view.sharpFailed()
                return@post
            }
            val token = shownToken
            view.whenCovered(
                token,
                action = { layer.present(bitmap, place.destination, onShown = { view.sharpShown(token) }, onFailed = { view.sharpFailed() }) },
                cancelled = { bitmap.recycle() }
            )
        }
    }

    private fun advance() {
        val handler = workerHandler ?: return
        val gen = generation
        preparingSince = SystemClock.uptimeMillis()
        val shown = try {
            showNextImage()
        } finally {
            preparingSince = 0L
        }
        if (shown) {
            shownAny = true
            val seconds = if (streamedLast && !settings.streamOnly) {
                maxOf(settings.intervalSeconds, ImageStreamer.MIN_INTERVAL_SECONDS)
            } else settings.intervalSeconds
            handler.postDelayed(advanceTask, seconds * 1000L)
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
                showStatus(gen, message)
            }
            handler.postDelayed(advanceTask, if (shownAny) EMPTY_RETRY_MILLIS else FIRST_PICTURE_RETRY_MILLIS)
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
            val seq = postFrame(gen, frame, attribution) {
                commitDisplay(gen, entry.sha256, library) { state ->
                    state.copy(
                        streamOrder = StreamQueue.withAccepted(
                            state.streamOrder, streamed.next, streamed.leadKey, entry.sha256
                        )
                    )
                }
            }
            sharpen(gen, seq, streamer.file(entry), entry)
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
            if (diagnosticsOn) {
                diagnostics.saved = library.size
                diagnostics.showable = candidates.size
                diagnostics.refresh = catalogue.lastSummary
            }
            val seq = postFrame(gen, frame, attribution) {
                commitDisplay(gen, pick.hash, library) { state -> state.copy(cachedOrder = pick.next) }
            }
            sharpen(gen, seq, store.imageFile(entry), entry)
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
        val started = SystemClock.uptimeMillis()
        return try {
            decode(file, place)?.let { Frame(it, place.destination, screenWidth, screenHeight) }?.also {
                if (diagnosticsOn) noteDiagnostics(entry, file, place, SystemClock.uptimeMillis() - started)
            }
        } catch (error: OutOfMemoryError) {
            Log.w(TAG, "Not enough memory to decode ${entry.key}", error)
            mainHandler.post { view.useLowResolution() }
            null
        }
    }

    private fun noteDiagnostics(entry: CatalogueEntry, file: File, place: Placement, millis: Long) {
        val box = place.destination
        diagnostics.pictureNumber = ++pictureCount
        diagnostics.title = entry.title.ifBlank { entry.providerId }
        diagnostics.source = "${entry.sourceLabel} ${entry.providerId}"
        diagnostics.fileWidth = entry.widthPx
        diagnostics.fileHeight = entry.heightPx
        diagnostics.fileBytes = file.length()
        diagnostics.fit = settings.displayMode.name.lowercase()
        diagnostics.drawnBox = "${box.width}x${box.height}+${box.left}+${box.top}"
        diagnostics.frameDecodeMillis = millis
        diagnostics.sharpDecodeMillis = -1
        diagnostics.sharpConvertMillis = -1
        diagnostics.sharpShowMillis = -1
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
        private const val DIAGNOSTICS_MILLIS = 1_000L
        private const val STATUS_TICK_MILLIS = 1_000L
        private const val LATE_AFTER_MILLIS = 2_500L
        private const val MIB = 1024L * 1024
        private const val MAX_ATTEMPTS = 3
        private const val EMPTY_RETRY_MILLIS = 20_000L
        private const val FIRST_PICTURE_RETRY_MILLIS = 2_000L

        // Two frames are held during a cross-fade; keep each within GPU texture limits.
        private const val MAX_FRAME_EDGE = 4096
    }
}

/** A decoded image ready to draw at [destination], in a [targetWidth] x [targetHeight] frame. */
internal class Frame(val bitmap: Bitmap, val destination: IntBox, val targetWidth: Int, val targetHeight: Int)