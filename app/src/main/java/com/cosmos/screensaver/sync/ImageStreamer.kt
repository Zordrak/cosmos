package com.cosmos.screensaver.sync

import android.content.Context
import com.cosmos.screensaver.catalogue.CatalogueEntry
import com.cosmos.screensaver.catalogue.CatalogueStore
import com.cosmos.screensaver.settings.CosmosSettings
import java.io.File
import java.util.UUID

/**
 * The last-resort mode for when free space is too low to keep a library: each image is downloaded
 * on demand into a scratch directory, shown, and deleted straight away. Nothing is retained.
 */
internal class ImageStreamer(context: Context) {
    private val appContext = context.applicationContext
    private val settings = CosmosSettings(appContext)
    private val root = File(appContext.cacheDir, "stream")
    private val scratch = File(root, "$SESSION_PREFIX${UUID.randomUUID()}")
    private val store = CatalogueStore(scratch)
    private val queue = StreamQueue()
    private var refresher: CatalogueRefresher? = null
    private var fingerprint: String? = null
    @Volatile private var closed = false

    init {
        sweepAbandoned()
    }

    /** True when there is enough free space to hold one image while it is being shown. A failed probe means no. */
    fun hasRoom(): Boolean = (RefreshCoordinator.freeBytes(appContext) ?: 0L) >= MIN_FREE_BYTES

    /**
     * Downloads the next image, or returns null if none could be obtained. Delete it with [discard].
     * The sources and quality are re-read on every call, so a change in settings starts a fresh
     * discovery instead of serving leads found under the old ones.
     */
    fun next(): Streamed? {
        if (closed || !hasRoom()) return null
        val current = "${settings.enabledProviders.map { it.name }.sorted()}|${settings.selection}"
        val active = refresher?.takeIf { fingerprint == current } ?: run {
            queue.clear()
            // Streaming has its own floor: only the physical headroom check applies, not the library's reserve or size.
            RefreshCoordinator.createRefresher(appContext, store, LibraryStorage.userState(appContext), {
                val free = RefreshCoordinator.freeBytes(appContext)
                if (free == null) CacheBudget.UNKNOWN else CacheBudget.fixed(Long.MAX_VALUE, free)
            })
                .also {
                    refresher = it
                    fingerprint = current
                }
        }
        return active.streamNext(queue, settings.orderMode, settings::sourceWeight) { closed }
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

        /** One image of up to 60 MiB, plus room to decode it. */
        const val MIN_FREE_BYTES = 150L * 1024 * 1024

        /** While streaming, a new image is fetched no more often than this, to limit bandwidth. */
        const val MIN_INTERVAL_SECONDS = 60

        /** Streaming also tops up a library with fewer cached images than this. */
        const val SMALL_LIBRARY = 10
    }
}
