package com.cosmos.screensaver.sync

import android.content.Context
import com.cosmos.screensaver.catalogue.CachedOrder
import com.cosmos.screensaver.catalogue.CatalogueEntry
import com.cosmos.screensaver.catalogue.CatalogueStore
import com.cosmos.screensaver.screensaver.ImagePicker
import com.cosmos.screensaver.screensaver.PickCandidate
import com.cosmos.screensaver.settings.CosmosSettings
import com.cosmos.screensaver.settings.ImageSelection
import com.cosmos.screensaver.settings.SelectionSnapshot
import java.io.File
import java.util.UUID

/**
 * Play without saving, and the fallback for when free space is too low to keep a library. Pictures
 * come from the online index: the same catalogue format as the library, holding each qualifying
 * picture's metadata and download URL but not its file ([onlineStore]). Each one is downloaded
 * into a scratch directory, shown and deleted; the next is downloaded while the current one is up
 * ([prefetch]). Until the index has anything, pictures are found by live discovery instead.
 */
internal class ImageStreamer(context: Context) {
    private val appContext = context.applicationContext
    private val settings = CosmosSettings(appContext)
    private val root = File(appContext.cacheDir, "stream")
    private val scratch = File(root, "$SESSION_PREFIX${UUID.randomUUID()}")
    private val store = CatalogueStore(scratch)
    private val index = onlineStore(appContext)
    private val queue = StreamQueue()
    private var refresher: CatalogueRefresher? = null
    private var fingerprint: String? = null
    private var prefetched: Streamed? = null
    private val random = kotlin.random.Random.Default
    @Volatile private var closed = false

    init {
        sweepAbandoned()
    }

    /** True when there is enough free space to hold one image while it is being shown. A failed probe means no. */
    fun hasRoom(): Boolean = (RefreshCoordinator.freeBytes(appContext) ?: 0L) >= MIN_FREE_BYTES

    /**
     * The next image: the one fetched in advance if it still suits the settings, else one from the
     * online index, else one found by live discovery. Null if none could be obtained. Delete it with
     * [discard]. Settings are re-read on every call.
     */
    fun next(): Streamed? {
        if (closed || !hasRoom()) return null
        prefetched?.let { ready ->
            prefetched = null
            if (suits(ready.entry) && store.imageFile(ready.entry).isFile) return ready
            discard(ready.entry)
        }
        return fromIndex() ?: discover()
    }

    /** Downloads the picture after this one now, so it is ready the moment it is due. */
    fun prefetch() {
        if (closed || prefetched != null || !hasRoom()) return
        prefetched = fromIndex()
    }

    private fun suits(entry: CatalogueEntry): Boolean =
        entry.provider in settings.enabledProviders && settings.selection.meetsMinimum(entry) && settings.selection.licenceAccepts(entry)

    private fun discover(): Streamed? {
        val current = "${settings.enabledProviders.map { it.name }.sorted()}|${settings.selection}"
        val active = refresher?.takeIf { fingerprint == current } ?: run {
            queue.clear()
            // Streaming has its own floor: only the physical headroom check applies, not the library's reserve or size.
            RefreshCoordinator.createRefresher(appContext, store, LibraryStorage.userState(appContext), {
                val free = RefreshCoordinator.freeBytes(appContext)
                if (free == null) CacheBudget.UNKNOWN else CacheBudget.fixed(Long.MAX_VALUE, free)
            }, progress = RefreshProgress.streaming)
                .also {
                    refresher = it
                    fingerprint = current
                }
        }
        val streamed = active.streamNext(queue, settings.orderMode, settings::sourceWeight) { closed } ?: return null
        remember(streamed.entry)
        return streamed
    }

    /** Keeps what live discovery found in the online index, so it is never searched for again. */
    private fun remember(entry: CatalogueEntry) {
        try {
            index.update { current ->
                current.copy(entries = current.entries.filterNot { it.key == entry.key } + entry.copy(fileName = ""))
            }
        } catch (error: java.io.IOException) {
            // The index is an optimisation; playing continues without it.
        }
    }

    /**
     * Chooses a picture from the online index with the normal ordering, downloads its known URL and
     * checks the file. The first download records the picture's checksum; later ones must match it.
     */
    private fun fromIndex(): Streamed? {
        val catalogue = (index.read() as? com.cosmos.screensaver.catalogue.StoreRead.Ok)?.catalogue ?: return null
        if (catalogue.entries.isEmpty()) return null
        val userState = LibraryStorage.userState(appContext)
        val excluded = mutableSetOf<String>()
        repeat(MAX_INDEX_ATTEMPTS) {
            if (closed) return null
            val state = userState.load()
            val snapshot = SelectionSnapshot.build(
                catalogue.entries.map { it.copy(sha256 = "") }, settings.selection, settings.enabledProviders, state
            )
            val candidates = snapshot.eligible.filter { !state.isBlocked(it.key, null) }.map { entry ->
                val stats = catalogue.entries.firstOrNull { it.key == entry.key }?.sha256?.let { state.stats[it] }
                PickCandidate(
                    hash = entry.key,
                    provider = entry.provider.name,
                    title = entry.title,
                    weight = settings.sourceWeight(entry.provider),
                    admittedAtEpochMillis = entry.admittedAtEpochMillis,
                    shownCount = stats?.shownCount ?: 0L,
                    lastShownOrdinal = stats?.lastShownOrdinal ?: 0L
                )
            }
            val order = state.streamOrder
            val lastKey = order.lastAcceptedLead
            val pick = ImagePicker.pick(
                settings.orderMode, CachedOrder(order.fair, order.cursor), candidates,
                candidates.map { it.hash }.toSet(), lastKey, excluded, random
            ) ?: return null
            val entry = catalogue.entries.first { it.key == pick.hash }
            val shown = download(entry)
            if (shown == null) {
                excluded += pick.hash
                return@repeat
            }
            if (state.isBlocked(entry.key, shown.sha256) || state.lastShownHash == shown.sha256) {
                discard(shown)
                excluded += pick.hash
                return@repeat
            }
            return Streamed(shown, entry.key, order.copy(fair = pick.next.fair, cursor = pick.next.cursor))
        }
        return null
    }

    /** Downloads [entry]'s file into scratch and checks it; returns the entry for the file, or null. */
    private fun download(entry: CatalogueEntry): CatalogueEntry? {
        val tracker = RefreshProgress.streaming
        val run = tracker.begin()
        val partial = File(store.imagesDirectory, "download-${UUID.randomUUID()}.part")
        try {
            store.imagesDirectory.mkdirs()
            run?.downloading(entry.title, entry.sourceLabel)
            val fetcher = com.cosmos.screensaver.source.HttpsFetcher(userAgent(), tracker.observer)
            val started = android.os.SystemClock.uptimeMillis()
            val download = fetcher.download(entry.assetUrl, partial, MAX_DOWNLOAD_BYTES)
            val ended = android.os.SystemClock.uptimeMillis()
            RefreshProgress.lastStreamed = FinishedDownload(download.byteCount, ended - started, ended)
            run?.checking()
            if (entry.sha256.isNotBlank() && entry.sha256 != download.sha256) {
                forget(entry, "CHANGED_AT_SOURCE")
                return null
            }
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(partial.absolutePath, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            val required = settings.selection.quality.minimum
            val fits = width > 0 && height > 0 && ImageSelection.suitableAspect(width, height) &&
                ImageSelection.withinMaximum(width, height) && (required?.accepts(width, height) ?: true)
            if (!fits) {
                forget(entry, CatalogueRefresher.LOW_RESOLUTION)
                return null
            }
            val extension = if (bounds.outMimeType == "image/png") "png" else "jpg"
            val target = File(store.imagesDirectory, "${download.sha256}.$extension")
            if (!partial.renameTo(target)) return null
            val verified = entry.copy(
                sha256 = download.sha256, byteCount = download.byteCount, widthPx = width, heightPx = height
            )
            if (entry.sha256.isBlank() || entry.widthPx != width || entry.heightPx != height) {
                try {
                    index.update { current ->
                        current.copy(entries = current.entries.map { if (it.key == entry.key) verified else it })
                    }
                } catch (error: java.io.IOException) {
                    // Checked again next time.
                }
            }
            return verified.copy(fileName = target.name)
        } catch (error: java.io.IOException) {
            run?.error(ProgressErrorKind.DOWNLOAD, error.message ?: "download failed", entry.sourceLabel)
            return null
        } finally {
            partial.delete()
            run?.end()
        }
    }

    /** Drops a picture from the online index, remembering why so it is not recorded again for a while. */
    private fun forget(entry: CatalogueEntry, reason: String) {
        try {
            index.update { current ->
                current.copy(
                    entries = current.entries.filterNot { it.key == entry.key },
                    rejections = current.rejections + (entry.key to com.cosmos.screensaver.catalogue.RejectionRecord(reason, System.currentTimeMillis()))
                )
            }
        } catch (error: java.io.IOException) {
            // Tried again next time.
        }
    }

    private fun userAgent(): String {
        val version = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        return "Cosmos/$version (Android TV screensaver; +https://github.com/Zordrak/cosmos)"
    }

    fun file(entry: CatalogueEntry): File = store.imageFile(entry)

    fun discard(entry: CatalogueEntry) {
        store.imageFile(entry).delete()
    }

    fun discardAll() {
        store.imagesDirectory.listFiles()?.forEach { it.delete() }
    }

    /** Stops any download in progress at its next checkpoint and removes this streamer's scratch files. */
    fun close() {
        closed = true
        scratch.deleteRecursively()
    }

    /** Removes scratch left by earlier layouts or sessions that did not shut down; never touches a recent one. */
    private fun sweepAbandoned() {
        val cutoff = System.currentTimeMillis() - ABANDONED_AFTER_MILLIS
        root.listFiles()?.forEach { child ->
            val session = child.isDirectory && child.name.startsWith(SESSION_PREFIX)
            if (child == scratch) return@forEach
            if (!session || child.lastModified() < cutoff) child.deleteRecursively()
        }
    }

    companion object {
        private const val SESSION_PREFIX = "s-"
        private const val ABANDONED_AFTER_MILLIS = 10L * 60 * 1000
        private const val MAX_INDEX_ATTEMPTS = 4
        private const val MAX_DOWNLOAD_BYTES = 60L * 1024 * 1024

        /** The online index: picture metadata and download URLs, no files. Small, so it lives in app data. */
        fun onlineStore(context: Context): CatalogueStore =
            CatalogueStore(File(context.applicationContext.filesDir, "online"))

        /** One image of up to 60 MiB, plus room to decode it. */
        const val MIN_FREE_BYTES = 150L * 1024 * 1024

        /** While streaming, a new image is fetched no more often than this, to limit bandwidth. */
        const val MIN_INTERVAL_SECONDS = 60

        /** Streaming also tops up a library with fewer cached images than this. */
        const val SMALL_LIBRARY = 10
    }
}
